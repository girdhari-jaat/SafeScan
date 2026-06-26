import * as Comlink from 'comlink';
import { PAPER_RATIOS, CARD_RATIOS } from '../constants';
import { detectCornersFromImageData } from './edgeDetection';
import { processFinalImage, applyFilter, lightShadowRemoval, autoEnhanceImageData } from './imageProcess';
import { jsPDF } from 'jspdf';
import { PDFDocument } from 'pdf-lib';

// Fixed pre-allocated memory pool of 48MB for 2GB RAM phones to bypass GC overhead of new ArrayBuffer allocations on high-res images
const SHARED_BUFFER_SIZE = 48 * 1024 * 1024; // 48MB
const sharedProcessBuffer = new ArrayBuffer(SHARED_BUFFER_SIZE);

function solveHomography(
  src: { x: number; y: number }[],
  dst: { x: number; y: number }[]
): number[] | null {
  const M: number[][] = [];
  const B: number[] = [];

  for (let i = 0; i < 4; i++) {
    const s = src[i];
    const d = dst[i];
    M.push([s.x, s.y, 1, 0, 0, 0, -s.x * d.x, -s.y * d.x]);
    B.push(d.x);
    M.push([0, 0, 0, s.x, s.y, 1, -s.x * d.y, -s.y * d.y]);
    B.push(d.y);
  }

  const n = 8;
  for (let i = 0; i < n; i++) {
    let maxEl = Math.abs(M[i][i]);
    let maxRow = i;
    for (let k = i + 1; k < n; k++) {
      if (Math.abs(M[k][i]) > maxEl) {
        maxEl = Math.abs(M[k][i]);
        maxRow = k;
      }
    }

    // Swap rows
    for (let k = i; k < n; k++) {
      const tmp = M[maxRow][k];
      M[maxRow][k] = M[i][k];
      M[i][k] = tmp;
    }
    const tmpB = B[maxRow];
    B[maxRow] = B[i];
    B[i] = tmpB;

    if (Math.abs(M[i][i]) < 1e-10) {
      return null;
    }

    for (let k = i + 1; k < n; k++) {
      const c = -M[k][i] / M[i][i];
      for (let j = i; j < n; j++) {
        if (i === j) {
          M[k][j] = 0;
        } else {
          M[k][j] += c * M[i][j];
        }
      }
      B[k] += c * B[i];
    }
  }

  const C = new Array(n).fill(0);
  for (let i = n - 1; i >= 0; i--) {
    let sum = 0;
    for (let k = i + 1; k < n; k++) {
      sum += M[i][k] * C[k];
    }
    C[i] = (B[i] - sum) / M[i][i];
  }

  return C;
}

function getSelectedQuality(sourceType?: string): 'Fast' | 'Standard' | 'High' {
  // Check the selected quality mode
  console.log('Quality check - SourceType:', sourceType);
  if (sourceType) {
    if (sourceType.includes('_Fast')) return 'Fast';
    if (sourceType.includes('_Standard')) return 'Standard';
    if (sourceType.includes('_High')) return 'High';
  }
  return 'Fast';
}

const workerProcessCache = new Map<string, Blob>();
const workerWarpCache = new Map<string, ImageBitmap>();

const workerAPI = {
  async detectCorners(bitmap: ImageBitmap, scanMode: 'paper' | 'card' | 'grid' | 'cnic' | 'idcard' | 'a4' = 'paper', isRealtime: boolean = false) {
    let w = 0;
    let h = 0;
    let cropX = 0;
    let cropY = 0;
    let cropW = 0;
    let cropH = 0;
    let croppedW = 0;
    let croppedH = 0;
    let canvas: OffscreenCanvas | null = null;
    let ctx: OffscreenCanvasRenderingContext2D | null = null;
    let imageData: ImageData | null = null;
    let detectedCorners: any = null;
    let adjustedCorners: any = null;
    let debugDelta: any = null;

    try {
      w = bitmap.width;
      h = bitmap.height;

      cropX = 0;
      cropY = 0;
      cropW = w;
      cropH = h;
      croppedW = w;
      croppedH = h;
      
      const scale = 0.25;
      const sw = Math.floor(w * scale);
      const sh = Math.floor(h * scale);

      canvas = new OffscreenCanvas(sw, sh);
      ctx = canvas.getContext('2d', { willReadFrequently: true });
      if (!ctx) {
        return null;
      }
      
      // Use CSS grayscale filter in worker!
      ctx.filter = 'grayscale(100%)';
      ctx.drawImage(bitmap, cropX, cropY, cropW, cropH, 0, 0, sw, sh);

      // Try Gemini AI first for robust detection
      let aiSucceeded = false;
      let aiErrorMsg: string | null = null;
      if (!isRealtime) {
        try {
          // Compress aggressively to save bandwidth
          const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.1 });
          const reader = new FileReader();
          const base64Promise = new Promise<string>((resolve, reject) => {
            reader.onloadend = () => {
              const res = reader.result as string;
              resolve(res.split(',')[1]);
            };
            reader.onerror = reject;
          });
          reader.readAsDataURL(blob);
          const base64 = await base64Promise;

          const response = await fetch('/api/gemini/detect-edges', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ imageBase64: base64 })
          });
          
          if (response.ok) {
            const data = await response.json();
            if (data.points && Array.isArray(data.points) && data.points.length === 4) {
              // Convert and normalize points dynamically (handles 0.0-1.0, 0-100%, or downscaled pixel space)
              adjustedCorners = data.points.map((pt: any) => {
                let px = parseFloat(pt.x);
                let py = parseFloat(pt.y);
                if (isNaN(px) || isNaN(py)) {
                  px = 0.5;
                  py = 0.5;
                }
                
                // Check if coords are in pixels or percentage (> 1.0)
                if (px > 1.0 || py > 1.0) {
                  if (px <= 100.0 && py <= 100.0) {
                    // Normalize percentage to 0.0 - 1.0
                    px /= 100.0;
                    py /= 100.0;
                  } else {
                    // Normalize downscaled pixel coordinates relative to sw and sh
                    px /= sw;
                    py /= sh;
                  }
                }
                
                // Clamp strictly to [0, 1] range to avoid out of bounds mapping
                px = Math.max(0, Math.min(1, px));
                py = Math.max(0, Math.min(1, py));

                return {
                  x: px * w,
                  y: py * h
                };
              });
              aiSucceeded = true;
            } else {
              aiErrorMsg = "Invalid response structure from server";
            }
          } else {
            try {
              const errData = await response.json();
              aiErrorMsg = errData.error || `Server returned status ${response.status}`;
            } catch (jsonErr) {
              aiErrorMsg = `Server returned status ${response.status}`;
            }
          }
        } catch (err: any) {
          console.warn('Gemini edge detection failed, falling back to local vision engine:', err);
          aiErrorMsg = err.message || "Network request failed";
        }
      }

      // Fallback to local CV engine if AI failed
      if (!aiSucceeded) {
        // Do all pixel reading off-thread on small pre-grayscaled image
        imageData = ctx.getImageData(0, 0, sw, sh);

        // Pass the downscaled grayscale data to the detector
        const detectOutput = detectCornersFromImageData(
          { data: imageData.data, width: sw, height: sh, originalWidth: croppedW, originalHeight: croppedH },
          scanMode
        );
        
        if (detectOutput && detectOutput.points !== undefined) {
          detectedCorners = detectOutput.points;
          debugDelta = detectOutput.debugDelta;
        } else {
          detectedCorners = detectOutput;
        }

        adjustedCorners = detectedCorners ? detectedCorners.map((pt: any) => ({
          x: pt.x + cropX,
          y: pt.y + cropY
        })) : null;
      }

      // MEMORY LEAK FIX: Explicitly zero out dimensions and references
      canvas.width = 0; canvas.height = 0;

      const resultObj = {
        corners: adjustedCorners,
        debugDelta,
        width: croppedW,
        height: croppedH,
        originalWidth: w,
        originalHeight: h,
        aiSucceeded,
        aiErrorMsg,
        usedAi: !isRealtime
      };

      // Explicitly clear local heavy objects
      imageData = null;
      canvas = null;
      ctx = null;

      return resultObj;
    } finally {
      if (bitmap) {
        bitmap.close();
      }
    }
  },
  applyFilter(
    bitmap: ImageBitmap,
    filterName: string
  ) {
    const w = bitmap.width;
    const h = bitmap.height;
    const canvas = new OffscreenCanvas(w, h);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) {
      const b = bitmap;
      b.close();
      return null;
    }
    ctx.drawImage(bitmap, 0, 0);
    bitmap.close();

    const imageData = ctx.getImageData(0, 0, w, h);
    const resultImageData = applyFilter(imageData, filterName);
    ctx.putImageData(resultImageData, 0, 0);
    const resultBitmap = canvas.transferToImageBitmap();
    
    // MEMORY LEAK FIX
    canvas.width = 0; canvas.height = 0;
    
    return Comlink.transfer(resultBitmap, [resultBitmap]);
  },
// AUDITED: Removed processRawImage, added canvas cleanup
  async handleCapturedFrame(
    imageBitmap: ImageBitmap,
    aspectRatio: number,
    isLowMemory: boolean,
    enhancements?: { shadowRemove?: boolean; autoAdjust?: boolean }
  ): Promise<Blob> {
    // Log captured frame dimensions to verify input size
    console.log('handleCapturedFrame - Input dimensions:', imageBitmap.width, 'x', imageBitmap.height);
    try {
      const w = imageBitmap.width;
      const h = imageBitmap.height;
      const currentAspect = w / h;

      // Target dimensions
      let cropW = w;
      let cropH = h;
      let sx = 0;
      let sy = 0;

      // Check if crop needed (e.g. 4:3 to 1:1.414 portrait)
      if (Math.abs(currentAspect - aspectRatio) > 0.001) {
        if (currentAspect > aspectRatio) {
          // Too wide (crop excess width)
          cropH = h;
          cropW = h * aspectRatio;
          sx = (w - cropW) / 2;
          sy = 0;
        } else {
          // Too tall (crop excess height)
          cropW = w;
          cropH = w / aspectRatio;
          sx = 0;
          sy = (h - cropH) / 2;
        }
      }

      // Downsample if low memory
      let finalW = cropW;
      let finalH = cropH;
      if (isLowMemory) {
        const maxDim = 1600;
        if (cropW > maxDim || cropH > maxDim) {
          const ratio = Math.min(maxDim / cropW, maxDim / cropH);
          finalW = Math.round(cropW * ratio);
          finalH = Math.round(cropH * ratio);
        }
      }

      const canvas = new OffscreenCanvas(finalW, finalH);
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      if (!ctx) throw new Error('Cannot get context');
      
      ctx.drawImage(imageBitmap, sx, sy, cropW, cropH, 0, 0, finalW, finalH);
      
      // Apply enhancements if requested
      if (enhancements?.shadowRemove || enhancements?.autoAdjust) {
        const imageData = ctx.getImageData(0, 0, finalW, finalH);
        if (enhancements.shadowRemove) {
          lightShadowRemoval(imageData);
        }
        if (enhancements.autoAdjust) {
          autoEnhanceImageData(imageData);
        }
        ctx.putImageData(imageData, 0, 0);
      }
      
      const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 1.0 });
      
      canvas.width = 0; canvas.height = 0;
      
      return blob;
    } finally {
      imageBitmap.close();
    }
  },

  async processFinalImage(
    imageBitmap: ImageBitmap,
    corners: any,
    rotation: number,
    filter: any,
    adjustments: any,
    sourceType?: string
  ) {
    const hashData = { corners, rotation, filter, adjustments, sourceType };
    const hash = `wk_proc|${JSON.stringify(hashData)}`;
    
    if (workerProcessCache.has(hash)) {
      imageBitmap.close();
      return workerProcessCache.get(hash)!;
    }

    const blob = await processFinalImage(imageBitmap, corners, rotation, filter, adjustments, sourceType);
    imageBitmap.close();
    
    workerProcessCache.set(hash, blob);
    if (workerProcessCache.size > 20) {
      const first = workerProcessCache.keys().next().value;
      if (first) workerProcessCache.delete(first);
    }
    return blob;
  },
  async warpPreview(
    imageBitmap: ImageBitmap,
    meta: { cropPoints: any; rotate: number; filter: string; adjustments: any; scanMode?: 'paper' | 'card' | 'grid' | 'idcard' | 'a4' | 'cnic' }
  ) {
    const hash = `wk_warp|${JSON.stringify(meta)}`;
    if (workerWarpCache.has(hash)) {
      const cached = workerWarpCache.get(hash)!;
      imageBitmap.close();
      return createImageBitmap(cached);
    }

    const w = imageBitmap.width;
    const h = imageBitmap.height;

    // Default corners if none are provided
    const corners = meta.cropPoints || {
      tl: { x: 0, y: 0 },
      tr: { x: 100, y: 0 },
      br: { x: 100, y: 100 },
      bl: { x: 0, y: 100 }
    };

    const tl = { x: (corners.tl.x / 100) * w, y: (corners.tl.y / 100) * h };
    const tr = { x: (corners.tr.x / 100) * w, y: (corners.tr.y / 100) * h };
    const br = { x: (corners.br.x / 100) * w, y: (corners.br.y / 100) * h };
    const bl = { x: (corners.bl.x / 100) * w, y: (corners.bl.y / 100) * h };

    const getDist = (p1: { x: number; y: number }, p2: { x: number; y: number }) => Math.sqrt((p1.x - p2.x) ** 2 + (p1.y - p2.y) ** 2);
    let warpWidth = Math.max(Math.round(Math.max(getDist(tl, tr), getDist(bl, br))), 100);
    let warpHeight = Math.max(Math.round(Math.max(getDist(tl, bl), getDist(tr, br))), 100);

    const isCard = meta.scanMode === 'card' || meta.scanMode === 'grid' || meta.scanMode === 'idcard' || meta.filter === 'card';
    const targetRatio = isCard ? CARD_RATIOS.LANDSCAPE : PAPER_RATIOS.A4_PORTRAIT;

    if (warpHeight > warpWidth) {
      warpHeight = Math.round(warpWidth * targetRatio);
    } else {
      warpWidth = Math.round(warpHeight * targetRatio);
    }

    const destCanvas = new OffscreenCanvas(warpWidth, warpHeight);
    const destCtx = destCanvas.getContext('2d');
    if (!destCtx) throw new Error('Failed to get 2D context for dest canvas');

    // High-performance true Projective/Perspective Warp using backward homography projection
    const srcCanvas = new OffscreenCanvas(w, h);
    const srcCtx = srcCanvas.getContext('2d');
    if (!srcCtx) throw new Error('Source context failed in worker');
    srcCtx.drawImage(imageBitmap, 0, 0);
    const srcData = srcCtx.getImageData(0, 0, w, h);
    const src32 = new Uint32Array(srcData.data.buffer);

    const dstData = destCtx.createImageData(warpWidth, warpHeight);
    const dst32 = new Uint32Array(dstData.data.buffer);

    const dstPts = [{ x: 0, y: 0 }, { x: warpWidth, y: 0 }, { x: warpWidth, y: warpHeight }, { x: 0, y: warpHeight }];
    const srcPts = [tl, tr, br, bl];
    
    const hMatrix = solveHomography(dstPts, srcPts);
    if (!hMatrix) throw new Error('Homography matrix calculation failed in worker');
    
    const [h0, h1, h2, h3, h4, h5, h6, h7] = hMatrix;

    for (let y = 0; y < warpHeight; y++) {
      const rowOffset = y * warpWidth;
      const h1y_h2 = h1 * y + h2;
      const h4y_h5 = h4 * y + h5;
      const h7y_1 = h7 * y + 1;

      for (let x = 0; x < warpWidth; x += 2) {
        const denA = h6 * x + h7y_1;
        const sxa = (h0 * x + h1y_h2) / denA;
        const sya = (h3 * x + h4y_h5) / denA;
        const isx = (sxa + 0.5) | 0;
        const isy = (sya + 0.5) | 0;

        if (isx >= 0 && isx < w && isy >= 0 && isy < h) {
          dst32[rowOffset + x] = src32[isy * w + isx];
        }

        if (x + 1 < warpWidth) {
          const x2 = x + 1;
          const denB = h6 * x2 + h7y_1;
          const sxb = (h0 * x2 + h1y_h2) / denB;
          const syb = (h3 * x2 + h4y_h5) / denB;
          const misx = (sxb + 0.5) | 0;
          const misy = (syb + 0.5) | 0;

          if (misx >= 0 && misx < w && misy >= 0 && misy < h) {
            dst32[rowOffset + x + 1] = src32[misy * w + misx];
          }
        }
      }
    }
    
    if (meta.adjustments?.shadowRemove || meta.adjustments?.autoAdjust) {
      if (meta.adjustments.shadowRemove) {
        lightShadowRemoval(dstData);
      }
      if (meta.adjustments.autoAdjust) {
        autoEnhanceImageData(dstData);
      }
    }
    
    destCtx.putImageData(dstData, 0, 0);

    // Apply adjustments using CSS filters perfectly on the GPU
    const filter = meta.filter || 'original';
    
    // We'll calculate the CSS filter string
    let presetCSS = '';
    switch (filter) {
      case 'pro-scan': presetCSS = 'contrast(115%) grayscale(100%)'; break;
      case 'magic': presetCSS = 'contrast(125%) saturate(135%) brightness(105%)'; break;
      case 'auto-enhance': presetCSS = 'contrast(110%) brightness(105%)'; break;
      case 'bw': presetCSS = 'grayscale(100%) contrast(160%) brightness(110%)'; break;
      case 'grayscale': presetCSS = 'grayscale(100%)'; break;
      case 'noir': presetCSS = 'grayscale(100%) contrast(210%) brightness(85%)'; break;
      case 'paper': presetCSS = 'contrast(115%) brightness(108%) saturate(90%)'; break;
      case 'document': presetCSS = 'grayscale(100%) contrast(185%) brightness(95%)'; break;
      case 'card': presetCSS = 'grayscale(100%) contrast(140%) sepia(12%) hue-rotate(180deg) saturate(180%)'; break;
    }
  
    const bAmt = 100 + (meta.adjustments?.brightness || 0);
    const cAmt = 100 + (meta.adjustments?.contrast || 0);
    const sAmt = 100 + (meta.adjustments?.saturation || 0);
    
    let filterStr = `brightness(${bAmt / 100}) contrast(${cAmt / 100}) saturate(${sAmt / 100})`;
    if (presetCSS !== '') { filterStr = presetCSS + ' ' + filterStr; }

    const filterCanvas = new OffscreenCanvas(warpWidth, warpHeight);
    const filterCtx = filterCanvas.getContext('2d');
    if (filterCtx) {
      filterCtx.filter = filterStr;
      filterCtx.drawImage(destCanvas, 0, 0);
    }

    // Apply Rotation
    let rotatedCanvas = filterCanvas;
    if (meta.rotate && meta.rotate !== 0) {
      const angleRad = (meta.rotate * Math.PI) / 180;
      const is90or270 = (meta.rotate / 90) % 2 !== 0;
      const rotW = is90or270 ? warpHeight : warpWidth;
      const rotH = is90or270 ? warpWidth : warpHeight;
      
      rotatedCanvas = new OffscreenCanvas(rotW, rotH);
      const rotCtx = rotatedCanvas.getContext('2d');
      if (rotCtx) {
        rotCtx.save();
        rotCtx.translate(rotW / 2, rotH / 2);
        rotCtx.rotate(angleRad);
        rotCtx.drawImage(filterCanvas, -warpWidth / 2, -warpHeight / 2);
        rotCtx.restore();
      }
    }

    const resultBitmap = rotatedCanvas.transferToImageBitmap();
    
    // Store in cache
    workerWarpCache.set(hash, resultBitmap);
    if (workerWarpCache.size > 15) {
      const first = workerWarpCache.keys().next().value;
      if (first) {
        const old = workerWarpCache.get(first);
        if (old) old.close();
        workerWarpCache.delete(first);
      }
    }

    imageBitmap.close(); // Immediate cleanup!

    // Return a copy so the cache stays valid (Zero-copy transfer of the copy)
    const transferBitmap = await createImageBitmap(resultBitmap);
    return Comlink.transfer(transferBitmap, [transferBitmap]);
  },
  async generatePDFOffThread(
    pagesData: { blob: Blob; page: any }[],
    options: {
      pageSize: 'a4' | 'letter' | 'fit';
      orientation: 'portrait' | 'landscape' | 'auto';
      quality: number;
      password?: string;
    }
  ): Promise<ArrayBuffer> {
    const { pageSize, orientation, quality, password } = options;
    let pdf: any = null;
    
    for (let i = 0; i < pagesData.length; i++) {
      const pageBlob = pagesData[i].blob;
      const pageMeta = pagesData[i].page;
      
      const bitmap = await createImageBitmap(pageBlob);
      
      // NON-DESTRUCTIVE ARCHITECTURE: process final image on the fly during export!
      const processedBlob = await processFinalImage(
        bitmap,
        pageMeta.corners,
        pageMeta.rotation,
        pageMeta.filter,
        pageMeta.adjustments,
        pageMeta.sourceType
      );
      bitmap.close();
      
      const pBitmap = await createImageBitmap(processedBlob);
      const imgW = pBitmap.width;
      const imgH = pBitmap.height;
      
      const qualityMode = getSelectedQuality(pageMeta.sourceType);
      let maxDimension = 2200;
      if (qualityMode === 'Fast') {
        maxDimension = 1500;
      } else if (qualityMode === 'Standard') {
        maxDimension = 2200;
      } else if (qualityMode === 'High') {
        maxDimension = 3500;
      }

      let targetWidth = imgW;
      let targetHeight = imgH;
      if (imgW > imgH) {
        if (imgW > maxDimension) {
          targetHeight = Math.round((imgH * maxDimension) / imgW);
          targetWidth = maxDimension;
        }
      } else {
        if (imgH > maxDimension) {
          targetWidth = Math.round((imgW * maxDimension) / imgH);
          targetHeight = maxDimension;
        }
      }
      
      const canvas = new OffscreenCanvas(targetWidth, targetHeight);
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.fillStyle = '#FFFFFF';
        ctx.fillRect(0, 0, targetWidth, targetHeight);
        ctx.drawImage(pBitmap, 0, 0, targetWidth, targetHeight);
      }
      pBitmap.close();
      
      let exportQuality = quality || 0.9;
      // Removed hard caps to ensure user slider works across full range
      
      const compressedBlob = await canvas.convertToBlob({
        type: 'image/jpeg',
        quality: exportQuality
      });
      // MEMORY LEAK FIX: Zero out canvas
      canvas.width = 0; canvas.height = 0;
      
      // Reader helper
      const arrayBuffer = await compressedBlob.arrayBuffer();
      const tempU8 = new Uint8Array(arrayBuffer);
      // Zero-copy view mapping using the pre-allocated sharedProcessBuffer pool instead of new ArrayBuffer instantiation
      const u8 = new Uint8Array(sharedProcessBuffer, 0, tempU8.length);
      u8.set(tempU8);
      
      // Determine orientation
      let pageOrientation: 'p' | 'l' = 'p';
      if (orientation === 'auto') {
        pageOrientation = imgW >= imgH ? 'l' : 'p';
      } else {
        pageOrientation = orientation === 'landscape' ? 'l' : 'p';
      }
      
      // Determine dimensions in mm
      let formatSize: string | [number, number] = 'a4';
      let pdfWidth = 210;
      let pdfHeight = 297;
      if (pageSize === 'letter') {
        formatSize = 'letter';
        pdfWidth = 215.9;
        pdfHeight = 279.4;
      } else if (pageSize === 'fit') {
        const scale = 0.264583;
        pdfWidth = imgW * scale;
        pdfHeight = imgH * scale;
        formatSize = [pdfWidth, pdfHeight];
      }
      
      if (pageOrientation === 'l' && pageSize !== 'fit') {
        const tmp = pdfWidth;
        pdfWidth = pdfHeight;
        pdfHeight = tmp;
      }
      
      if (!pdf) {
        pdf = new jsPDF({
          orientation: pageOrientation,
          unit: 'mm',
          format: formatSize,
          compress: true
        });
      } else {
        pdf.addPage(formatSize, pageOrientation);
      }
      
      let destW = pdfWidth;
      let destH = pdfHeight;
      let startX = 0;
      let startY = 0;
      if (pageSize !== 'fit') {
        const pageRatio = pdfWidth / pdfHeight;
        const imgRatio = imgW / imgH;
        if (imgRatio > pageRatio) {
          destW = pdfWidth;
          destH = pdfWidth / imgRatio;
          startY = (pdfHeight - destH) / 2;
        } else {
          destH = pdfHeight;
          destW = pdfHeight * imgRatio;
          startX = (pdfWidth - destW) / 2;
        }
      }
      
      // Add image to PDF page directly from Uint8Array!
      pdf.addImage(
        u8,
        'JPEG',
        startX,
        startY,
        destW,
        destH,
        undefined,
        (quality || 0.9) > 0.8 ? 'FAST' : 'SLOW'
      );
    }
    
    if (!pdf) {
      throw new Error('Failed to assemble PDF contents');
    }
    
    let pdfArrayBuffer = pdf.output('arraybuffer');
    
    if (password) {
      const pdfDoc = await PDFDocument.load(pdfArrayBuffer);
      (pdfDoc as any).encrypt({ userPassword: password, ownerPassword: password });
      const encryptedBytes = await pdfDoc.save();
      pdfArrayBuffer = encryptedBytes.buffer;
    }
    
    return Comlink.transfer(pdfArrayBuffer, [pdfArrayBuffer]);
  },
  async generateCardPDFOffThread(
    cardsData: { blob: Blob; card: any }[],
    options: {
      mode: 'idcard' | 'grid';
      title: string;
      quality: number;
    }
  ): Promise<Blob> {
    const { mode, quality } = options;

    let canvas: OffscreenCanvas | null = null;
    const processedBitmaps = new Map<number, ImageBitmap>();

    try {
      if (mode === 'idcard' || mode === 'grid') {
        const W = 2480;
        const H = 3508;
        canvas = new OffscreenCanvas(W, H);
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('Context error');

        ctx.fillStyle = '#FFFFFF';
        ctx.fillRect(0, 0, W, H);

        if (!cardsData || cardsData.length === 0) throw new Error('No images available for PDF');

        // Constants for ISO ID-1 (85.6mm x 53.98mm) at 300 DPI
        const cardW = 1011; // 85.6mm exactly
        const cardH = 638;  // 54.0mm exactly
        
        // A4 is 2480x3508 at 300 DPI
        // We have 2 columns. Cards take 2022px.
        // Total available width for gaps = 2480 - 2022 = 458px.
        // If we want 5mm (59px) on each side, gutter = 458 - (59*2) = 340px.
        // That's a huge gap. Typically, we want them more centered with balanced gutters.
        
        // Let's use the user's 5mm margin as MINIMUM and center the grid.
        const gutterX = 120; // ~10mm gap between cards (horizontal)
        const gridWidth = (cardW * 2) + gutterX;
        const startX = (W - gridWidth) / 2;
        
        const gutterY = 100; // ~8.5mm gap between cards (vertical)
        const gridHeight = (cardH * 4) + (gutterY * 3);
        const startY = (H - gridHeight) / 2;

        const positions: [number, number][] = [];
        for (let r = 0; r < 4; r++) {
          positions.push([startX, startY + (r * (cardH + gutterY))]);
        }

        // Process function to avoid duplication and apply perspective warping and filters
        const getProcessedBitmap = async (item: { blob: Blob; card: any }) => {
          if (!item || !item.blob) return null;
          const originalIndex = item.card?.originalIndex ?? -1;
          
          if (originalIndex >= 0 && processedBitmaps.has(originalIndex)) {
            return processedBitmaps.get(originalIndex);
          }

          const rawBmp = await createImageBitmap(item.blob);
          const processedBlob = await processFinalImage(
            rawBmp,
            item.card.corners,
            item.card.rotation,
            item.card.filter,
            item.card.adjustments,
            item.card.sourceType
          );
          rawBmp.close();
          const finalBmp = await createImageBitmap(processedBlob);
          if (originalIndex >= 0) {
            processedBitmaps.set(originalIndex, finalBmp);
          }
          return finalBmp;
        };

        for (let i = 0; i < 4; i++) {
          const frontIdx = i * 2;
          const backIdx = i * 2 + 1;
          
          const frontItem = frontIdx < cardsData.length ? cardsData[frontIdx] : null;
          const backItem = backIdx < cardsData.length ? cardsData[backIdx] : null;

          const [x, y] = positions[i];

          if (frontItem) {
            const bmp = await getProcessedBitmap(frontItem);
            if (bmp) ctx.drawImage(bmp, x, y, cardW, cardH);
          }

          if (backItem) {
            const bmp = await getProcessedBitmap(backItem);
            if (bmp) ctx.drawImage(bmp, x + cardW + gutterX, y, cardW, cardH);
          }
        }

        const canvasBlob = await canvas.convertToBlob({ type: 'image/jpeg', quality: quality });
        const arrayBuffer = await canvasBlob.arrayBuffer();
        
        const pdf = new jsPDF({
          orientation: 'portrait',
          unit: 'mm',
          format: 'a4',
          compress: true
        });

        pdf.addImage(new Uint8Array(arrayBuffer), 'JPEG', 0, 0, 210, 297, undefined, 'FAST');
        return pdf.output('blob');
      }
      throw new Error('Unsupported mode: ' + mode);

    } finally {
      // Release memory
      processedBitmaps.forEach(bmp => {
        try { bmp.close(); } catch(e) {}
      });
      processedBitmaps.clear();

      if (canvas) {
        canvas.width = 0;
        canvas.height = 0;
        canvas = null;
      }
    }
  }

};

Comlink.expose(workerAPI);
