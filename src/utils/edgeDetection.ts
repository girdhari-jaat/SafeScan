import { PAPER_RATIOS, CARD_RATIOS } from '../constants';
import { Point, ImageQualityReport } from './edge/types';
import { getDefaultQuad, orderPoints, filterBestQuad, refineSkewedCorner } from './edge/geometry';
import { bilateralFilter } from './edge/filters';
import { fitLineRANSAC, intersectLines } from './edge/ransac';
import { profilingImageQuality } from './edge/profiler';
import { computeOtsuThreshold, binarizeImage } from './edge/threshold';
import { dilate, erode, morphologicalClose } from './edge/morphology';

// Re-export common types and geometric fallback functions for application usage
export type { Point, ImageQualityReport };
export { 
  getDefaultQuad,
  computeOtsuThreshold,
  binarizeImage,
  dilate,
  erode,
  morphologicalClose
};

/**
 * Estimates the foreground object boundaries using percentiles of morphologically closed values.
 * This predicts exactly where the document sits, allowing dynamic search regions similar to CamScanner/Adobe Scan.
 */
function estimateForegroundPercentages(closed: Uint8Array, w: number, h: number): { leftPct: number, rightPct: number, topPct: number, bottomPct: number } | null {
  let bgPointsCount = 0;
  let bgWhiteSum = 0;
  const marginW = Math.max(1, Math.round(w * 0.03));
  const marginH = Math.max(1, Math.round(h * 0.03));
  
  for (let x = 0; x < w; x++) {
    for (let y = 0; y < marginH; y++) {
      bgPointsCount++;
      if (closed[y * w + x] === 255) bgWhiteSum++;
    }
    for (let y = h - marginH; y < h; y++) {
      bgPointsCount++;
      if (closed[y * w + x] === 255) bgWhiteSum++;
    }
  }
  for (let y = marginH; y < h - marginH; y++) {
    for (let x = 0; x < marginW; x++) {
      bgPointsCount++;
      if (closed[y * w + x] === 255) bgWhiteSum++;
    }
    for (let x = w - marginW; x < w; x++) {
      bgPointsCount++;
      if (closed[y * w + x] === 255) bgWhiteSum++;
    }
  }
  
  const isForegroundWhite = bgPointsCount > 0 && (bgWhiteSum / bgPointsCount) < 0.5;
  const targetVal = isForegroundWhite ? 255 : 0;

  const xCoords: number[] = [];
  const yCoords: number[] = [];
  
  for (let y = Math.round(h * 0.04); y < h - Math.round(h * 0.04); y += 3) {
    for (let x = Math.round(w * 0.04); x < w - Math.round(w * 0.04); x += 3) {
      if (closed[y * w + x] === targetVal) {
        xCoords.push(x);
        yCoords.push(y);
      }
    }
  }

  if (xCoords.length < (w * h * 0.005)) {
    return null;
  }

  xCoords.sort((a, b) => a - b);
  yCoords.sort((a, b) => a - b);

  const pctLow = 0.03;
  const pctHigh = 0.97;

  const minX = xCoords[Math.floor(xCoords.length * pctLow)];
  const maxX = xCoords[Math.floor(xCoords.length * pctHigh)];
  const minY = yCoords[Math.floor(yCoords.length * pctLow)];
  const maxY = yCoords[Math.floor(yCoords.length * pctHigh)];

  if (maxX - minX < w * 0.20 || maxY - minY < h * 0.20) {
    return null;
  }

  return {
    leftPct: Math.max(0.02, minX / w),
    rightPct: Math.min(0.98, maxX / w),
    topPct: Math.max(0.02, minY / h),
    bottomPct: Math.min(0.98, maxY / h)
  };
}

/**
 * High-Precision Computer Vision Edge Detection Pipeline
 * 
 * Orchestrates multi-step image pre-processing, gradient estimation, and RANSAC geometric
 * line extraction to accurately identify document or card contours in raw frames.
 */
export function detectCornersFromImageData(
  imageData: { data: Uint8ClampedArray; width: number; height: number; originalWidth: number; originalHeight: number },
  scanMode: string = 'paper',
  buffers?: {
    gray?: Uint8Array;
    blurred?: Uint8Array;
    magnitudes?: Float32Array;
    magnitudesX?: Float32Array;
    magnitudesY?: Float32Array;
  }
): { points: Point[] | null, debugDelta?: { top: number, bottom: number }, confidence?: number } {
  
  const isManualCrop = scanMode.startsWith('manual_');
  const cleanScanMode = isManualCrop ? scanMode.replace('manual_', '') : scanMode;
  
  const { width: sw, height: sh, data } = imageData;
  
  // 1. Extract Grayscale values from pixel data array
  const gray = buffers?.gray || new Uint8Array(sw * sh);
  let minG = 255, maxG = 0;
  for (let i = 0; i < sw * sh; i++) {
    const val = data[i * 4];
    gray[i] = val;
    if (val < minG) minG = val;
    if (val > maxG) maxG = val;
  }
  
  // 2. Profile quality metrics, ambient constraints, and detect document category
  const qualityReport = profilingImageQuality(gray, sw, sh);
  // console.log(`[CV Engine] Type: ${qualityReport.detectedType}, Brightness: ${qualityReport.brightness.toFixed(1)}, Contrast: ${qualityReport.contrast.toFixed(1)}, Sharpness: ${qualityReport.sharpness.toFixed(1)}, Blur: ${qualityReport.isBlurred}`);
  
  // 3. Normalize Contrast (Min-Max Stretch) to make text bounds and physical edges pop
  const range = maxG - minG || 1;
  const stretchFactor = qualityReport.detectedType === 'colored_card' ? 260 : 255;
  for (let i = 0; i < gray.length; i++) {
    gray[i] = Math.round(((gray[i] - minG) / range) * stretchFactor);
    if (gray[i] > 255) gray[i] = 255;
  }
  
  // 4. Adapt bilateral smoothing parameters to filter surface noise while highlighting margins
  let blurDiameter = 5;
  let blurSigmaI = 20;
  let blurSigmaS = 10;
  
  if (qualityReport.detectedType === 'white_paper') {
    // Crisp paper backgrounds: delicate smoothing prevents fading out fine border line details
    blurDiameter = 3;
    blurSigmaI = 15;
  } else if (qualityReport.detectedType === 'colored_card' || qualityReport.isBlurred) {
    // Heavy card textures: aggressive density smoothing eliminates high frequency background noise
    blurDiameter = 5;
    blurSigmaI = 25;
  }
  
  const blurred = bilateralFilter(gray, sw, sh, blurDiameter, blurSigmaI, blurSigmaS);
  
  // 4b. Otsu Dynamic Auto-Thresholding & Morphological Closing
  const otsuThreshold = computeOtsuThreshold(blurred, sw, sh);
  const binary = binarizeImage(blurred, otsuThreshold);
  const closed = morphologicalClose(binary, sw, sh);
  
  // 5. Apply Horizontal and Vertical Sobel operator kernels
  const Gx = [
    -1, 0, 1,
    -2, 0, 2,
    -1, 0, 1
  ];
  const Gy = [
    -1, -2, -1,
     0,  0,  0,
     1,  2,  1
  ];
  
  const magnitudesX = buffers?.magnitudesX || new Float32Array(sw * sh);
  const magnitudesY = buffers?.magnitudesY || new Float32Array(sw * sh);
  
  // 2% safe border margin inside dimensions limit to prevent edge aliasing
  const borderY = Math.round(sh * 0.02);
  const borderX = Math.round(sw * 0.02);
  
  for (let y = borderY; y < sh - borderY; y++) {
    for (let x = borderX; x < sw - borderX; x++) {
      let gx = 0;
      let gy = 0;
      for (let ky = -1; ky <= 1; ky++) {
        for (let kx = -1; kx <= 1; kx++) {
          const val = blurred[(y + ky) * sw + (x + kx)];
          gx += val * Gx[(ky + 1) * 3 + (kx + 1)];
          gy += val * Gy[(ky + 1) * 3 + (kx + 1)];
        }
      }
      magnitudesX[y * sw + x] = Math.abs(gx);
      magnitudesY[y * sw + x] = Math.abs(gy);
    }
  }
  
  // 6. Estimate Dynamic Gradient Threshold parameters based on distribution percentiles
  const sampledX: number[] = [];
  const sampledY: number[] = [];
  const stride = Math.max(1, Math.floor((sw * sh) / 600));
  for (let i = 0; i < sw * sh; i += stride) {
    if (magnitudesX[i] > 5) sampledX.push(magnitudesX[i]);
    if (magnitudesY[i] > 5) sampledY.push(magnitudesY[i]);
  }
  
  let thresholdX = 14;
  if (sampledX.length > 30) {
    sampledX.sort((a, b) => a - b);
    thresholdX = sampledX[Math.floor(sampledX.length * 0.84)];
  }
  
  let thresholdY = 14;
  if (sampledY.length > 30) {
    sampledY.sort((a, b) => a - b);
    thresholdY = sampledY[Math.floor(sampledY.length * 0.84)];
  }
  
  // Scale down search threshold dynamically under low light to avoid missing dim borders
  if (qualityReport.isLowLight) {
    thresholdX *= 0.70;
    thresholdY *= 0.70;
  }
  
  // ==========================================================================
  // SCAN SUB-REGION GENERATION & LINE RECONSTRUCTION PIPELINESTAGE
  // ==========================================================================
  const scanTarget = (
    leftPct: number,
    rightPct: number,
    topPct: number,
    bottomPct: number
  ): { corners: Point[]; confidence: number; debugDelta?: { top: number, bottom: number } } | null => {
    
    // Arrays tracking candidates along physical region margins
    const leftPoints: Point[] = [];
    const rightPoints: Point[] = [];
    const topPoints: Point[] = [];
    const bottomPoints: Point[] = [];
    
    const targetLeft = sw * leftPct;
    const targetRight = sw * rightPct;
    const targetTop = sh * topPct;
    const targetBottom = sh * bottomPct;
    
    // Proximity search space depth
    const marginX = Math.round(sw * 0.15);
    const marginY = Math.round(sh * 0.05);
    
    const searchLeftStart = Math.max(0, Math.round(targetLeft - marginX));
    const searchLeftEnd = Math.min(sw, Math.round(targetLeft + marginX));
    const searchRightStart = Math.max(0, Math.round(targetRight - marginX));
    const searchRightEnd = Math.min(sw, Math.round(targetRight + marginX));
    
    const searchTopStart = Math.max(0, Math.round(targetTop - marginY));
    const searchTopEnd = Math.min(sh, Math.round(targetTop + marginY));
    const searchBottomStart = Math.max(0, Math.round(targetBottom - marginY));
    const searchBottomEnd = Math.min(sh, Math.round(targetBottom + marginY));
    
    // Define bounds for lateral sweep
    const scanYStart = Math.max(0, Math.round(sh * (topPct - 0.04)));
    const scanYEnd = Math.min(sh, Math.round(sh * (bottomPct + 0.04)));
    
    const scanStride = 3;
    for (let y = scanYStart; y < scanYEnd; y += scanStride) {
      if (y < 0 || y >= sh) continue;
      
      // Left coordinate gradient + morph transition scan
      let maxScoreL = -1, maxXL = -1;
      for (let x = searchLeftStart; x < searchLeftEnd; x++) {
        const val = magnitudesX[y * sw + x];
        const isTransition = x > 0 && x < sw - 1 && (
          closed[y * sw + x] !== closed[y * sw + (x - 1)] ||
          closed[y * sw + x] !== closed[y * sw + (x + 1)]
        );
        const score = val * (isTransition ? 2.5 : 1.0);
        if (score > maxScoreL) { maxScoreL = score; maxXL = x; }
      }
      if (maxXL !== -1 && maxScoreL > thresholdX * 0.45) {
        leftPoints.push({ x: maxXL, y });
      }
      
      // Right coordinate gradient + morph transition scan
      let maxScoreR = -1, maxXR = -1;
      for (let x = searchRightStart; x < searchRightEnd; x++) {
        const val = magnitudesX[y * sw + x];
        const isTransition = x > 0 && x < sw - 1 && (
          closed[y * sw + x] !== closed[y * sw + (x - 1)] ||
          closed[y * sw + x] !== closed[y * sw + (x + 1)]
        );
        const score = val * (isTransition ? 2.5 : 1.0);
        if (score > maxScoreR) { maxScoreR = score; maxXR = x; }
      }
      if (maxXR !== -1 && maxScoreR > thresholdX * 0.45) {
        rightPoints.push({ x: maxXR, y });
      }
    }
    
    // Define bounds for horizontal sweep
    const scanXStart = Math.max(0, Math.round(sw * (leftPct - 0.04)));
    const scanXEnd = Math.min(sw, Math.round(sw * (rightPct + 0.04)));
    
    for (let x = scanXStart; x < scanXEnd; x += scanStride) {
      if (x < 0 || x >= sw) continue;
      
      // Top coordinate gradient + morph transition scan
      let maxScoreT = -1, foundYT = -1;
      for (let y = searchTopStart; y < searchTopEnd; y++) {
        const val = magnitudesY[y * sw + x];
        const isTransition = y > 0 && y < sh - 1 && (
          closed[y * sw + x] !== closed[(y - 1) * sw + x] ||
          closed[y * sw + x] !== closed[(y + 1) * sw + x]
        );
        const score = val * (isTransition ? 2.5 : 1.0);
        if (score > maxScoreT) { maxScoreT = score; foundYT = y; }
      }
      if (foundYT !== -1 && maxScoreT > thresholdY * 0.45) {
        topPoints.push({ x, y: foundYT });
      }
      
      // Bottom coordinate gradient + morph transition scan
      let maxScoreB = -1, foundYB = -1;
      for (let y = searchBottomStart; y < searchBottomEnd; y++) {
        const val = magnitudesY[y * sw + x];
        const isTransition = y > 0 && y < sh - 1 && (
          closed[y * sw + x] !== closed[(y - 1) * sw + x] ||
          closed[y * sw + x] !== closed[(y + 1) * sw + x]
        );
        const score = val * (isTransition ? 2.5 : 1.0);
        if (score > maxScoreB) { maxScoreB = score; foundYB = y; }
      }
      if (foundYB !== -1 && maxScoreB > thresholdY * 0.45) {
        bottomPoints.push({ x, y: foundYB });
      }
    }
    
    if (topPoints.length < 120 && bottomPoints.length < 120) {
      // console.warn("[CV Engine Warning] Low edge indicators detected. Improve ambient ligthing.");
    }
    
    let runDebugDelta: { top: number, bottom: number } | undefined;
    if (topPoints.length > 0 && bottomPoints.length > 0) {
      const avgTop = topPoints.reduce((sum, p) => sum + p.y, 0) / topPoints.length;
      const avgBottom = bottomPoints.reduce((sum, p) => sum + p.y, 0) / bottomPoints.length;
      runDebugDelta = { top: avgTop - targetTop, bottom: avgBottom - targetBottom };
    }
    
    // Core RANSAC Line Fit & Multi-point Intersect Stage
    if (
      leftPoints.length >= 4 &&
      rightPoints.length >= 4 &&
      topPoints.length >= 4 &&
      bottomPoints.length >= 4
    ) {
      const isHighSparsity = leftPoints.length < 8 || rightPoints.length < 8;
      const ransacIters = isHighSparsity ? 45 : 25;
      
      const leftRes = fitLineRANSAC(leftPoints, true, ransacIters, 2.0);
      const rightRes = fitLineRANSAC(rightPoints, true, ransacIters, 2.0);
      const topRes = fitLineRANSAC(topPoints, false, ransacIters, 2.0);
      const bottomRes = fitLineRANSAC(bottomPoints, false, ransacIters, 2.0);
      
      if (leftRes && rightRes && topRes && bottomRes) {
        const tl = intersectLines(leftRes.line, topRes.line, targetLeft, targetTop);
        const tr = intersectLines(rightRes.line, topRes.line, targetRight, targetTop);
        const br = intersectLines(rightRes.line, bottomRes.line, targetRight, targetBottom);
        const bl = intersectLines(leftRes.line, bottomRes.line, targetLeft, targetBottom);
        
        // Quad Bounds Sanity checks
        const isValidSmart = (
          tl.x >= -sw * 0.50 && tl.x < sw * (leftPct + 0.60) && tl.y >= -sh * 0.50 && tl.y < sh * (topPct + 0.60) &&
          tr.x > sw * (rightPct - 0.60) && tr.x <= sw * 1.50 && tr.y >= -sh * 0.50 && tr.y < sh * (topPct + 0.60) &&
          br.x > sw * (rightPct - 0.60) && br.x <= sw * 1.50 && br.y > sh * (bottomPct - 0.60) && br.y <= sh * 1.50 &&
          bl.x >= -sw * 0.50 && bl.x < sw * (leftPct + 0.60) && bl.y > sh * (bottomPct - 0.60) && bl.y <= sh * 1.50
        );
        
        const dist = (p1: Point, p2: Point) => Math.sqrt((p1.x - p2.x) ** 2 + (p1.y - p2.y) ** 2);
        const wTop = dist(tl, tr);
        const wBottom = dist(bl, br);
        const hLeft = dist(tl, bl);
        const hRight = dist(tr, br);
        
        const isGoodSize = (
          wTop >= sw * 0.15 &&
          wBottom >= sw * 0.15 &&
          hLeft >= sh * 0.15 &&
          hRight >= sh * 0.15
        );
        
        const confAverage = (
          (leftRes.inliersCount / leftPoints.length) +
          (rightRes.inliersCount / rightPoints.length) +
          (topRes.inliersCount / topPoints.length) +
          (bottomRes.inliersCount / bottomPoints.length)
        ) / 4;
        
        const confThreshold = isManualCrop ? 0.15 : 0.32;
        
        if (isValidSmart && isGoodSize && confAverage > confThreshold) {
          const avgWidth = (wTop + wBottom) / 2;
          const avgHeight = (hLeft + hRight) / 2;
          const rectRatio = avgWidth / avgHeight;
          
          const isCardMode = ['card', 'grid', 'cnic', 'idcard'].includes(cleanScanMode);
          const tolerance = isManualCrop ? 0.45 : 0.25; // Far more relaxed ratio tolerance for manual crop
          
          let isValidRatio = false;
          if (isCardMode) {
            const cardRatio = CARD_RATIOS.LANDSCAPE; // 1.586
            isValidRatio = Math.abs(rectRatio - cardRatio) < tolerance || Math.abs(rectRatio - (1 / cardRatio)) < tolerance;
          } else {
            const ratio34 = PAPER_RATIOS.THREE_FOUR; // 0.75
            const ratioA4 = PAPER_RATIOS.A4; // 0.707
            isValidRatio = 
              Math.abs(rectRatio - ratio34) < tolerance || Math.abs(rectRatio - (1 / ratio34)) < tolerance ||
              Math.abs(rectRatio - ratioA4) < tolerance || Math.abs(rectRatio - (1 / ratioA4)) < tolerance ||
              isManualCrop; // Manual mode bypasses ratio locks if general rectangle forms
          }
          
          if (!isValidRatio) return null;
          
          let pts = [tl, tr, br, bl].map(p => ({
            x: Math.max(0, Math.min(sw, p.x)),
            y: Math.max(0, Math.min(sh, p.y))
          }));
          
          pts = orderPoints(pts);
          pts = refineSkewedCorner(pts);
          
          const validQuad = filterBestQuad(pts, sw, sh, scanMode);
          if (validQuad.length !== 4) return null;
          
          // Re-project coordinates safely back to raw original dimensions
          const realScaleX = imageData.originalWidth / sw;
          const realScaleY = imageData.originalHeight / sh;
          
          const scaledPts = validQuad.map(p => ({
            x: p.x * realScaleX,
            y: p.y * realScaleY
          }));
          
          return { corners: scaledPts, confidence: confAverage, debugDelta: runDebugDelta };
        }
      }
    }
    
    return { corners: null, confidence: 0, debugDelta: runDebugDelta };
  };
  
  const isCardMode = ['card', 'grid', 'cnic', 'idcard'].includes(cleanScanMode);
  
  // Try dynamic foreground estimation first (CamScanner / Adobe Scan technique)
  const est = estimateForegroundPercentages(closed, sw, sh);
  if (est) {
    const dynamicRes = scanTarget(est.leftPct, est.rightPct, est.topPct, est.bottomPct);
    if (dynamicRes && dynamicRes.corners) {
      return { points: dynamicRes.corners, debugDelta: dynamicRes.debugDelta, confidence: dynamicRes.confidence };
    }
    
    // Multi-scale crop adjustment (sweep offset search relative to dynamic center block)
    const paddingX = Math.min(0.06, (est.rightPct - est.leftPct) * 0.08);
    const paddingY = Math.min(0.06, (est.bottomPct - est.topPct) * 0.08);
    
    const dynamicRes2 = scanTarget(
      Math.max(0.01, est.leftPct - paddingX),
      Math.min(0.99, est.rightPct + paddingX),
      Math.max(0.01, est.topPct - paddingY),
      Math.min(0.99, est.bottomPct + paddingY)
    );
    if (dynamicRes2 && dynamicRes2.corners) {
      return { points: dynamicRes2.corners, debugDelta: dynamicRes2.debugDelta, confidence: dynamicRes2.confidence };
    }
  }

  if (isCardMode) {
    // 1. Scanning strategy layout for Card Mode
    const r1 = scanTarget(0.025, 0.975, 0.225, 0.775);
    if (r1 && r1.corners) return { points: r1.corners, debugDelta: r1.debugDelta };
    
    const r2 = scanTarget(0.075, 0.925, 0.250, 0.750);
    if (r2 && r2.corners) return { points: r2.corners, debugDelta: r2.debugDelta };
    
    const r3 = scanTarget(0.125, 0.875, 0.275, 0.725);
    if (r3 && r3.corners) return { points: r3.corners, debugDelta: r3.debugDelta };
    
    const r4 = scanTarget(0.175, 0.825, 0.300, 0.700);
    if (r4 && r4.corners) return { points: r4.corners, debugDelta: r4.debugDelta };
    
    if (isManualCrop) {
      const fallbackPts = findRobustForegroundBoundingBox(closed, sw, sh);
      const realScaleX = imageData.originalWidth / sw;
      const realScaleY = imageData.originalHeight / sh;
      const scaledPts = fallbackPts.map(p => ({
        x: p.x * realScaleX,
        y: p.y * realScaleY
      }));
      return { points: scaledPts, confidence: 0.1 };
    }
    return { points: null, debugDelta: r1?.debugDelta };
  } else {
    // 2. Scanning strategy layout for Paper / Regular Document Mode
    const r1 = scanTarget(0.0525, 0.9475, 0.025, 0.975);
    if (r1 && r1.corners) return { points: r1.corners, debugDelta: r1.debugDelta, confidence: r1.confidence };
    
    const r2 = scanTarget(0.098, 0.902, 0.075, 0.925);
    if (r2 && r2.corners) return { points: r2.corners, debugDelta: r2.debugDelta, confidence: r2.confidence };
    
    const r3 = scanTarget(0.125, 0.875, 0.125, 0.875);
    if (r3 && r3.corners) return { points: r3.corners, debugDelta: r3.debugDelta, confidence: r3.confidence };
    
    if (isManualCrop) {
      const fallbackPts = findRobustForegroundBoundingBox(closed, sw, sh);
      const realScaleX = imageData.originalWidth / sw;
      const realScaleY = imageData.originalHeight / sh;
      const scaledPts = fallbackPts.map(p => ({
        x: p.x * realScaleX,
        y: p.y * realScaleY
      }));
      return { points: scaledPts, confidence: 0.1 };
    }
    return { points: null, confidence: 0 };
  }
}

/**
 * Computes a robust bounding box of the foreground document using the binary morphological closed map.
 * This is extremely resilient to heavy blur, low contrast, and noisy background tables!
 */
function findRobustForegroundBoundingBox(closed: Uint8Array, w: number, h: number): Point[] {
  // 1. Determine background color by sampling outer 3% margins
  let bgPointsCount = 0;
  let bgWhiteSum = 0;
  const marginW = Math.max(1, Math.round(w * 0.03));
  const marginH = Math.max(1, Math.round(h * 0.03));
  
  for (let x = 0; x < w; x++) {
    for (let y = 0; y < marginH; y++) {
      bgPointsCount++;
      if (closed[y * w + x] === 255) bgWhiteSum++;
    }
    for (let y = h - marginH; y < h; y++) {
      bgPointsCount++;
      if (closed[y * w + x] === 255) bgWhiteSum++;
    }
  }
  for (let y = marginH; y < h - marginH; y++) {
    for (let x = 0; x < marginW; x++) {
      bgPointsCount++;
      if (closed[y * w + x] === 255) bgWhiteSum++;
    }
    for (let x = w - marginW; x < w; x++) {
      bgPointsCount++;
      if (closed[y * w + x] === 255) bgWhiteSum++;
    }
  }
  
  // If outer margins are mostly white (>= 50%), then foreground is dark (0).
  // Otherwise, foreground is light (255).
  const isForegroundWhite = bgPointsCount > 0 && (bgWhiteSum / bgPointsCount) < 0.5;
  const targetVal = isForegroundWhite ? 255 : 0;
  
  // 2. Scan and collect foreground pixel coordinates (excluding outermost 4%)
  const xCoords: number[] = [];
  const yCoords: number[] = [];
  const startX = Math.round(w * 0.04);
  const endX = Math.round(w * 0.96);
  const startY = Math.round(h * 0.04);
  const endY = Math.round(h * 0.96);
  
  for (let y = startY; y < endY; y += 2) {
    for (let x = startX; x < endX; x += 2) {
      if (closed[y * w + x] === targetVal) {
        xCoords.push(x);
        yCoords.push(y);
      }
    }
  }
  
  // 3. Fallback to standard 4% frame padding if too few pixels are detected
  if (xCoords.length < (w * h * 0.005)) {
    return [
      { x: w * 0.04, y: h * 0.04 },
      { x: w * 0.96, y: h * 0.04 },
      { x: w * 0.96, y: h * 0.96 },
      { x: w * 0.04, y: h * 0.96 }
    ];
  }
  
  // 4. Sort coordinates to find robust percentiles (2% and 98% percentile to cut outlier noise)
  xCoords.sort((a, b) => a - b);
  yCoords.sort((a, b) => a - b);
  
  const pctLow = 0.02;
  const pctHigh = 0.98;
  
  const minX = xCoords[Math.floor(xCoords.length * pctLow)];
  const maxX = xCoords[Math.floor(xCoords.length * pctHigh)];
  const minY = yCoords[Math.floor(yCoords.length * pctLow)];
  const maxY = yCoords[Math.floor(yCoords.length * pctHigh)];
  
  // Standard bound clamping to make sure it's sensible
  const finalMinX = Math.max(w * 0.03, Math.min(minX, w * 0.35));
  const finalMaxX = Math.min(w * 0.97, Math.max(maxX, w * 0.65));
  const finalMinY = Math.max(h * 0.03, Math.min(minY, h * 0.35));
  const finalMaxY = Math.min(h * 0.97, Math.max(maxY, h * 0.65));
  
  return [
    { x: finalMinX, y: finalMinY },
    { x: finalMaxX, y: finalMinY },
    { x: finalMaxX, y: finalMaxY },
    { x: finalMinX, y: finalMaxY }
  ];
}
