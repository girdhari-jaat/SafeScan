import { PageCorners, ScanFilterType, PageAdjustments } from '../types';

// AUDITED: Removed unused getDefaultCorners

/**
 * Solves a 3x3 homography matrix equation System M * C = B to map coordinates
 * from source points to destination points.
 */
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





/**
 * Computes the optimal threshold value using Otsu's binarization algorithm (for B&W document scans)
 */
function computeOtsuThreshold(pixels: Uint8ClampedArray): number {
  const hist = new Int32Array(256);
  const len = pixels.length;
  const total = len / 4;

  for (let i = 0; i < len; i += 4) {
    const r = pixels[i];
    const g = pixels[i + 1];
    const b = pixels[i + 2];
    const gray = Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    hist[gray]++;
  }

  let sum = 0;
  for (let i = 0; i < 256; i++) {
    sum += i * hist[i];
  }

  let sumB = 0;
  let wB = 0;
  let wF = 0;
  let varMax = 0;
  let threshold = 127;

  for (let t = 0; t < 256; t++) {
    wB += hist[t];
    if (wB === 0) continue;
    wF = total - wB;
    if (wF === 0) break;

    sumB += t * hist[t];
    const mB = sumB / wB;
    const mF = (sum - sumB) / wF;

    const varBetween = wB * wF * (mB - mF) * (mB - mF);
    if (varBetween > varMax) {
      varMax = varBetween;
      threshold = t;
    }
  }

  return threshold;
}



export function getCSSFilterString(filter: string, adjustments: any, _ignoreTemp = false): string {
  let presetCSS = '';
  switch (filter) {
    case 'pro-scan':
      presetCSS = 'contrast(115%) grayscale(100%)';
      break;
    case 'magic':
      presetCSS = 'contrast(125%) saturate(135%) brightness(105%)';
      break;
    case 'auto-enhance':
      presetCSS = 'contrast(110%) brightness(105%)';
      break;
    case 'bw':
      presetCSS = 'grayscale(100%) contrast(160%) brightness(110%)';
      break;
    case 'grayscale':
      presetCSS = 'grayscale(100%)';
      break;
    case 'noir':
      presetCSS = 'grayscale(100%) contrast(210%) brightness(85%)';
      break;
    case 'paper':
      presetCSS = 'contrast(115%) brightness(108%) saturate(90%)';
      break;
    case 'document':
      presetCSS = 'grayscale(100%) contrast(185%) brightness(95%)';
      break;
    case 'cnic':
      presetCSS = 'grayscale(100%) contrast(140%) sepia(12%) hue-rotate(180deg) saturate(180%)';
      break;
    case 'original':
    default:
      presetCSS = '';
      break;
  }

  const b = 100 + (adjustments?.brightness || 0);
  const c = 100 + (adjustments?.contrast || 0);
  const s = 100 + (adjustments?.saturation || 0);
  const g = adjustments?.grayscale || 0;
  
  let shadowCSS = '';
  const shd = adjustments?.shadows || 0;
  if (shd > 0) {
    shadowCSS = `drop-shadow(0px 0px ${shd / 4}px rgba(255,255,255, ${(shd/100) * 0.5}))`;
  } else if (shd < 0) {
    shadowCSS = `drop-shadow(0px 0px ${-shd / 4}px rgba(0,0,0, ${(-shd/100) * 0.5}))`;
  }

  // Use CSS filter function syntax
  let filterStr = `brightness(${b / 100}) contrast(${c / 100}) saturate(${s / 100}) grayscale(${g / 100})`;
  if (presetCSS !== '') {
    filterStr = presetCSS + ' ' + filterStr;
  }
  if (shadowCSS) {
    filterStr += ' ' + shadowCSS;
  }
  
  return filterStr.trim();
}

/**
 * Applies a built-in scan filter to ImageData.
 * Processes pixel buffer in-place using fast Uint8ClampedArray loops for optimal worker compatibility.
 */
export function applyFilter(imageData: ImageData, filterName: string): ImageData {
  const data = imageData.data;
  const len = data.length;
  
  if (filterName === 'original') {
    return imageData;
  }
  
  if (filterName === 'pro-scan') {
    for (let i = 0; i < len; i += 4) {
      const r = data[i];
      const g = data[i+1];
      const b = data[i+2];
      const cr = (r - 128) * 1.3 + 128;
      const cg = (g - 128) * 1.3 + 128;
      const cb = (b - 128) * 1.3 + 128;
      const brightness = 0.299 * cr + 0.587 * cg + 0.114 * cb;
      if (brightness > 190) {
        data[i] = 255;
        data[i+1] = 255;
        data[i+2] = 255;
      } else {
        const gray = Math.min(255, (0.299 * r + 0.587 * g + 0.114 * b) + 32);
        const gClamped = gray < 0 ? 0 : gray;
        data[i] = gClamped;
        data[i+1] = gClamped;
        data[i+2] = gClamped;
      }
    }
    return imageData;
  }
  
  let otsu = 127;
  if (filterName === 'bw' || filterName === 'document') {
    otsu = computeOtsuThreshold(data);
  }
  
  for (let i = 0; i < len; i += 4) {
    let r = data[i];
    let g = data[i+1];
    let b = data[i+2];
    const gray = 0.299 * r + 0.587 * g + 0.114 * b;
    
    if (filterName === 'grayscale') {
      r = g = b = gray;
    } else if (filterName === 'bw') {
      r = g = b = gray >= otsu ? 255 : 0;
    } else if (filterName === 'noir') {
      const v = (gray - 75) * (255 / 120);
      r = g = b = Math.max(0, Math.min(255, v));
    } else if (filterName === 'paper') {
      if (r > 130) r = Math.min(255, r + (255 - r) * 0.48);
      if (g > 130) g = Math.min(255, g + (255 - g) * 0.48);
      if (b > 130) b = Math.min(255, b + (255 - b) * 0.48);
    } else if (filterName === 'document') {
      r = g = b = gray > otsu - 15 ? 255 : Math.max(0, gray * 0.55);
    } else if (filterName === 'magic') {
      const bR = gray + 1.25 * (r - gray);
      const bG = gray + 1.25 * (g - gray);
      const bB = gray + 1.25 * (b - gray);
      if ((bR + bG + bB) / 3 > 155) {
        r = Math.min(255, bR + (255 - bR) * 0.35);
        g = Math.min(255, bG + (255 - bG) * 0.35);
        b = Math.min(255, bB + (255 - bB) * 0.35);
      } else {
        r = Math.max(0, Math.min(255, bR));
        g = Math.max(0, Math.min(255, bG));
        b = Math.max(0, Math.min(255, bB));
      }
    } else if (filterName === 'cnic') {
      const cv = Math.max(0, Math.min(255, (gray - 128) * 1.35 + 128));
      r = Math.round(cv * 0.90);
      g = Math.round(cv * 0.95);
      b = Math.round(cv * 1.05);
    } else if (filterName === 'auto-enhance') {
      r = Math.max(0, Math.min(255, (r - 128) * 1.15 + 128 + 15));
      g = Math.max(0, Math.min(255, (g - 128) * 1.15 + 128 + 15));
      b = Math.max(0, Math.min(255, (b - 128) * 1.15 + 128 + 15));
    }
    
    data[i] = r;
    data[i+1] = g;
    data[i+2] = b;
  }
  
  return imageData;
}

function getSharedCanvas(w: number, h: number): HTMLCanvasElement | OffscreenCanvas {
  const canvas = typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(w, h) : document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  return canvas;
}

function getSelectedQuality(sourceType?: string): 'Fast' | 'Standard' | 'High' {
  if (sourceType) {
    if (sourceType.includes('_Fast')) return 'Fast';
    if (sourceType.includes('_Standard')) return 'Standard';
    if (sourceType.includes('_High')) return 'High';
  }
  if (typeof window !== 'undefined' && window.localStorage) {
    const saved = window.localStorage.getItem('hdMode');
    if (saved === 'Standard' || saved === '8MP') return 'Standard';
    if (saved === 'High' || saved === '12MP') return 'High';
    return 'Fast';
  }
  return 'Fast';
}

/**
 * Core Heavy-duty Processing Pipeline: 
 * Runs EXACTLY ONCE on the full resolution image when clicking Apply.
 * Performs perspective warping, rotation, and high-performance pixel filtering.
 */
export async function processFinalImage(
  sourceImage: HTMLImageElement | HTMLCanvasElement | ImageBitmap | OffscreenCanvas,
  corners: PageCorners,
  rotation: number,
  filter: ScanFilterType,
  adjustments: PageAdjustments,
  sourceType?: string
): Promise<Blob> {
  let activeSource: any = sourceImage;
  let w = (sourceImage as any).naturalWidth || (sourceImage as any).width || 1200;
  let h = (sourceImage as any).naturalHeight || (sourceImage as any).height || 1600;

  // 1. Downscale large images before processing to reduce CPU/Memory heat
  const quality = getSelectedQuality(sourceType);
  let MAX_PROC_DIM = 2400;
  if (sourceType === 'preview') {
    MAX_PROC_DIM = 1080;
  } else if (quality === 'Fast') {
    MAX_PROC_DIM = 1600;
  } else if (quality === 'Standard') {
    MAX_PROC_DIM = 2400;
  } else if (quality === 'High') {
    MAX_PROC_DIM = 3600;
  }

  if (w > MAX_PROC_DIM || h > MAX_PROC_DIM) {
    const scale = MAX_PROC_DIM / Math.max(w, h);
    const targetW = Math.round(w * scale);
    const targetH = Math.round(h * scale);
    
    // Use Canvas 3 for downscaling to avoid collision with later steps
    const downCanvas = getSharedCanvas(targetW, targetH);
    const downCtx = downCanvas.getContext('2d') as any;
    if (downCtx) {
      downCtx.drawImage(sourceImage as any, 0, 0, targetW, targetH);
      activeSource = downCanvas;
      w = targetW;
      h = targetH;
    }
  }

  // Adjust corners to current processed size
  const tl = { x: (corners.tl.x / 100) * w, y: (corners.tl.y / 100) * h };
  const tr = { x: (corners.tr.x / 100) * w, y: (corners.tr.y / 100) * h };
  const br = { x: (corners.br.x / 100) * w, y: (corners.br.y / 100) * h };
  const bl = { x: (corners.bl.x / 100) * w, y: (corners.bl.y / 100) * h };

  const getDist = (p1: { x: number; y: number }, p2: { x: number; y: number }) => Math.sqrt((p1.x - p2.x) ** 2 + (p1.y - p2.y) ** 2);

  // Enforce standard ratios and exact resolutions based on sourceType and quality
  const isCNIC = !!(sourceType?.includes('cnic') || sourceType?.includes('idcard') || sourceType?.includes('slot'));
  
  let warpWidth = 1240;
  let warpHeight = 1754;

  if (isCNIC) {
    const origW = Math.max(Math.round(Math.max(getDist(tl, tr), getDist(bl, br))), 100);
    const origH = Math.max(Math.round(Math.max(getDist(tl, bl), getDist(tr, br))), 100);
    const isPortrait = origH > origW;

    switch (quality) {
      case 'Fast': 
        warpWidth = isPortrait ? 783 : 1240; 
        warpHeight = isPortrait ? 1240 : 783; 
        break;
      case 'Standard': 
        warpWidth = isPortrait ? 1044 : 1654; 
        warpHeight = isPortrait ? 1654 : 1044; 
        break;
      case 'High': 
        warpWidth = isPortrait ? 1287 : 2040; 
        warpHeight = isPortrait ? 2040 : 1287; 
        break;
    }
  } else if (sourceType === 'preview') {
    // Keep preview resolution low for quick rendering with aspect ratio preservation
    const origW = Math.max(Math.round(Math.max(getDist(tl, tr), getDist(bl, br))), 100);
    const origH = Math.max(Math.round(Math.max(getDist(tl, bl), getDist(tr, br))), 100);
    const cropRatio = origW / origH;
    
    if (origH > origW) {
      warpHeight = 1080;
      warpWidth = Math.round(1080 * cropRatio);
    } else {
      warpWidth = 1080;
      warpHeight = Math.round(1080 / cropRatio);
    }
  } else {
    // A4 Document paper/scan or fallback with absolute aspect-ratio preservation!
    const origW = Math.max(Math.round(Math.max(getDist(tl, tr), getDist(bl, br))), 100);
    const origH = Math.max(Math.round(Math.max(getDist(tl, bl), getDist(tr, br))), 100);
    const cropRatio = origW / origH;

    let baseDim = 1800; // Default Standard max edge (matching standard A4 scaled down)
    switch (quality) {
      case 'Fast':
        baseDim = 1200;
        break;
      case 'Standard':
        baseDim = 1800;
        break;
      case 'High':
        baseDim = 2400;
        break;
    }

    if (origH > origW) {
      // Portrait
      warpHeight = baseDim;
      warpWidth = Math.round(baseDim * cropRatio);
    } else {
      // Landscape
      warpWidth = baseDim;
      warpHeight = Math.round(baseDim / cropRatio);
    }
  }

  // Step 1: Source pixels (Canvas 1)
  // Ensure Canvas 1 is used for pixels and Canvas 2/3 for source if possible
  const srcCanvas = getSharedCanvas(w, h);
  const srcCtx = srcCanvas.getContext('2d', { willReadFrequently: true }) as any;
  if (!srcCtx) throw new Error('Source context failed');
  srcCtx.drawImage(activeSource as any, 0, 0, w, h);
  let srcData = srcCtx.getImageData(0, 0, w, h);
  let src32 = new Uint32Array(srcData.data.buffer);

  // Step 2: Warped target (use a local canvas for warped intermediate to be absolutely safe from collisions)
  const warpedCanvas = typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(warpWidth, warpHeight) : document.createElement('canvas');
  warpedCanvas.width = warpWidth;
  warpedCanvas.height = warpHeight;
  
  const warpedCtx = warpedCanvas.getContext('2d') as any;
  if (!warpedCtx) throw new Error('Warped context failed');
  let dstData = warpedCtx.createImageData(warpWidth, warpHeight);
  let dst32 = new Uint32Array(dstData.data.buffer);

  // Step 3: Backward projection warping 
  const dstPts = [{x:0,y:0}, {x:warpWidth,y:0}, {x:warpWidth,y:warpHeight}, {x:0,y:warpHeight}];
  const srcPts = [tl, tr, br, bl];
  const hMatrix = solveHomography(dstPts, srcPts);
  if (!hMatrix) throw new Error('Matrix failed');
  const [h0, h1, h2, h3, h4, h5, h6, h7] = [hMatrix[0], hMatrix[1], hMatrix[2], hMatrix[3], hMatrix[4], hMatrix[5], hMatrix[6], hMatrix[7]];

  for (let y = 0; y < warpHeight; y++) {
    const rowOffset = y * warpWidth;
    const h1y_h2 = h1 * y + h2, h4y_h5 = h4 * y + h5, h7y_1 = h7 * y + 1;
    let sxa = h1y_h2 / h7y_1, sya = h4y_h5 / h7y_1;

    for (let x = 0; x < warpWidth; x += 2) {
      const isx = (sxa + 0.5) | 0, isy = (sya + 0.5) | 0;
      if (isx >= 0 && isx < w && isy >= 0 && isy < h) dst32[rowOffset + x] = src32[isy * w + isx];

      if (x + 1 < warpWidth) {
        const x2 = x + 2 < warpWidth ? x + 2 : x + 1;
        const den2 = h6 * x2 + h7y_1;
        const sxb = (h0 * x2 + h1y_h2) / den2, syb = (h3 * x2 + h4y_h5) / den2;
        const misx = ((sxa + sxb) / 2 + 0.5) | 0, misy = ((sya + syb) / 2 + 0.5) | 0;
        if (misx >= 0 && misx < w && misy >= 0 && misy < h) dst32[rowOffset + x + 1] = src32[misy * w + misx];
        sxa = sxb; sya = syb;
      }
    }
  }
  if (adjustments?.shadowRemove || adjustments?.autoAdjust) {
    if (adjustments.shadowRemove) {
      lightShadowRemoval(dstData);
    }
    if (adjustments.autoAdjust) {
      autoEnhanceImageData(dstData);
    }
  }
  warpedCtx.putImageData(dstData, 0, 0);
  (srcData as any) = null; (src32 as any) = null; (dstData as any) = null; (dst32 as any) = null;

  // Step 4: Filters
  // Use Canvas 3 for final output
  const finalCanvas = getSharedCanvas(warpWidth, warpHeight);
  const finalCtx = finalCanvas.getContext('2d') as any;
  if (!finalCtx) throw new Error('Final context failed');

  // Fast apply CSS filters that perfectly match Crop preview
  finalCtx.filter = getCSSFilterString(filter, adjustments, false);
  finalCtx.drawImage(warpedCanvas as any, 0, 0);
  finalCtx.filter = 'none';

  let finalCanvasToExport: HTMLCanvasElement | OffscreenCanvas = finalCanvas;

  if (rotation !== 0) {
    const is90or270 = (rotation / 90) % 2 !== 0;
    const finalWidth = is90or270 ? warpHeight : warpWidth;
    const finalHeight = is90or270 ? warpWidth : warpHeight;

    const rotCanvas = typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(finalWidth, finalHeight) : document.createElement('canvas');
    rotCanvas.width = finalWidth;
    rotCanvas.height = finalHeight;
    const rotCtx = rotCanvas.getContext('2d') as any;
    if (rotCtx) {
      rotCtx.save();
      rotCtx.translate(finalWidth / 2, finalHeight / 2);
      rotCtx.rotate((rotation * Math.PI) / 180);
      rotCtx.drawImage(finalCanvas as any, -warpWidth / 2, -warpHeight / 2);
      rotCtx.restore();
      finalCanvasToExport = rotCanvas;
    }
  }

  const blob = await new Promise<Blob>((resolve, reject) => {
    if (finalCanvasToExport instanceof OffscreenCanvas) {
      finalCanvasToExport.convertToBlob({ type: 'image/jpeg', quality: 1.0 }).then(resolve).catch(reject);
    } else {
      (finalCanvasToExport as HTMLCanvasElement).toBlob((b: any) => b ? resolve(b) : reject(new Error('Blob fail')), 'image/jpeg', 1.0);
    }
  });

  // Zero-out intermediate canvases for immediate memory release
  (srcCanvas as any).width = 0; (srcCanvas as any).height = 0;
  (warpedCanvas as any).width = 0; (warpedCanvas as any).height = 0;
  if (finalCanvas !== finalCanvasToExport) {
     (finalCanvas as any).width = 0; (finalCanvas as any).height = 0;
  }
  if (activeSource !== sourceImage) {
     if (typeof (activeSource as any).width !== 'undefined') {
         // It's the downCanvas
         (activeSource as any).width = 0; (activeSource as any).height = 0;
     }
  }
  // Clean up the final exported canvas too
  (finalCanvasToExport as any).width = 0; (finalCanvasToExport as any).height = 0;

  // Return blob
  return blob;
}

/**
 * High-performance light shadow removal from captured canvas frame using local block-based illumination estimation.
 * Divides the image into blocks, finds local background paper brightness, smooths, and normalizes.
 */
export function lightShadowRemoval(imageData: ImageData): void {
  const data = imageData.data;
  const w = imageData.width;
  const h = imageData.height;

  // Divide into 32x32 blocks for quick local estimation
  const blockSize = 32;
  const gridW = Math.ceil(w / blockSize);
  const gridH = Math.ceil(h / blockSize);

  const bgGrid = new Float32Array(gridW * gridH);

  // 1. Calculate the 90th percentile of brightness in each block to estimate local background color
  for (let gy = 0; gy < gridH; gy++) {
    for (let gx = 0; gx < gridW; gx++) {
      const startX = gx * blockSize;
      const startY = gy * blockSize;
      const endX = Math.min(w, startX + blockSize);
      const endY = Math.min(h, startY + blockSize);

      const vals: number[] = [];
      for (let y = startY; y < endY; y++) {
        for (let x = startX; x < endX; x++) {
          const idx = (y * w + x) * 4;
          const r = data[idx];
          const g = data[idx + 1];
          const b = data[idx + 2];
          const brightness = 0.299 * r + 0.587 * g + 0.114 * b;
          vals.push(brightness);
        }
      }

      if (vals.length > 0) {
        vals.sort((a, b) => a - b);
        // Use 90th percentile to get the bright paper color, avoiding the dark ink text
        const percent90 = vals[Math.floor(vals.length * 0.9)];
        bgGrid[gy * gridW + gx] = Math.max(80, percent90);
      } else {
        bgGrid[gy * gridW + gx] = 200;
      }
    }
  }

  // 2. Smooth the background grid using a box blur to avoid harsh borders/blockiness
  const smoothedGrid = new Float32Array(gridW * gridH);
  const blurRadius = 1;
  for (let gy = 0; gy < gridH; gy++) {
    for (let gx = 0; gx < gridW; gx++) {
      let sum = 0;
      let count = 0;
      for (let dy = -blurRadius; dy <= blurRadius; dy++) {
        for (let dx = -blurRadius; dx <= blurRadius; dx++) {
          const ny = gy + dy;
          const nx = gx + dx;
          if (ny >= 0 && ny < gridH && nx >= 0 && nx < gridW) {
            sum += bgGrid[ny * gridW + nx];
            count++;
          }
        }
      }
      smoothedGrid[gy * gridW + gx] = sum / count;
    }
  }

  // 3. Bilinearly interpolate back the local background for each pixel and perform homomorphic flat normalization
  for (let y = 0; y < h; y++) {
    const gy = y / blockSize;
    const gy0 = Math.floor(gy);
    const gy1 = Math.min(gridH - 1, gy0 + 1);
    const ty = gy - gy0;

    for (let x = 0; x < w; x++) {
      const gx = x / blockSize;
      const gx0 = Math.floor(gx);
      const gx1 = Math.min(gridW - 1, gx0 + 1);
      const tx = gx - gx0;

      const val00 = smoothedGrid[gy0 * gridW + gx0];
      const val10 = smoothedGrid[gy0 * gridW + gx1];
      const val01 = smoothedGrid[gy1 * gridW + gx0];
      const val11 = smoothedGrid[gy1 * gridW + gx1];

      const bg = (1 - tx) * (1 - ty) * val00 +
                 tx * (1 - ty) * val10 +
                 (1 - tx) * ty * val01 +
                 tx * ty * val11;

      const idx = (y * w + x) * 4;
      const r = data[idx];
      const g = data[idx + 1];
      const b = data[idx + 2];

      const targetBg = 245;
      const factor = targetBg / Math.max(1, bg);

      let nr = r * factor;
      let ng = g * factor;
      let nb = b * factor;

      // Keep strong dark details/text from being blown out or lightened too much
      const origBrightness = 0.299 * r + 0.587 * g + 0.114 * b;
      if (origBrightness < 65) {
        const blend = origBrightness / 65;
        nr = nr * blend + r * (1 - blend);
        ng = ng * blend + g * (1 - blend);
        nb = nb * blend + b * (1 - blend);
      }

      data[idx] = Math.max(0, Math.min(255, nr));
      data[idx + 1] = Math.max(0, Math.min(255, ng));
      data[idx + 2] = Math.max(0, Math.min(255, nb));
    }
  }
}

/**
 * Automatically adjusts brightness and contrast of the image data using robust percentile-based contrast stretching.
 */
export function autoEnhanceImageData(imageData: ImageData): void {
  const data = imageData.data;
  const len = data.length;
  const totalPixels = len / 4;

  // 1. Compute cumulative histogram of luminance to ignore outlier hot/dead pixels
  const hist = new Int32Array(256);
  for (let i = 0; i < len; i += 4) {
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const gray = Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    hist[gray]++;
  }

  // 2. Find the 1st and 99th percentile values
  let p1 = 0;
  let p99 = 255;
  
  let count = 0;
  const target1 = totalPixels * 0.01;
  for (let i = 0; i < 256; i++) {
    count += hist[i];
    if (count >= target1) {
      p1 = i;
      break;
    }
  }

  count = 0;
  const target99 = totalPixels * 0.99;
  for (let i = 0; i < 256; i++) {
    count += hist[i];
    if (count >= target99) {
      p99 = i;
      break;
    }
  }

  // 3. Stretch contrast between p1 and p99
  const range = p99 - p1;
  if (range > 10 && range < 255) {
    const factor = 255 / range;
    for (let i = 0; i < len; i += 4) {
      const r = data[i];
      const g = data[i + 1];
      const b = data[i + 2];

      data[i] = Math.max(0, Math.min(255, (r - p1) * factor));
      data[i + 1] = Math.max(0, Math.min(255, (g - p1) * factor));
      data[i + 2] = Math.max(0, Math.min(255, (b - p1) * factor));
    }
  }

  // 4. Auto brightness: adjust average luminance to make documents look clean and white (target mean ~190)
  let sum = 0;
  for (let i = 0; i < len; i += 4) {
    sum += (data[i] + data[i + 1] + data[i + 2]) / 3;
  }
  const avg = sum / totalPixels;

  if (avg < 170) {
    const boost = 190 / avg;
    for (let i = 0; i < len; i += 4) {
      data[i] = Math.max(0, Math.min(255, data[i] * boost));
      data[i + 1] = Math.max(0, Math.min(255, data[i + 1] * boost));
      data[i + 2] = Math.max(0, Math.min(255, data[i + 2] * boost));
    }
  }
}








