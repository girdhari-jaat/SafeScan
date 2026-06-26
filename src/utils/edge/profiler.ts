import { ImageQualityReport } from './types';

/**
 * Evaluates image sharpness, average brightness, contrast range, and identifies whether
 * the target object is a regular White Paper or a solid Colored Card.
 */
export function profilingImageQuality(gray: Uint8Array, width: number, height: number): ImageQualityReport {
  let sum = 0;
  for (let i = 0; i < gray.length; i++) sum += gray[i];
  const brightness = sum / gray.length;
  
  // Contrast: Standard Deviation
  let sumSqDiff = 0;
  for (let i = 0; i < gray.length; i++) {
    const diff = gray[i] - brightness;
    sumSqDiff += diff * diff;
  }
  const contrast = Math.sqrt(sumSqDiff / gray.length);
  
  // Sharpness assessment via Sobel-based energy levels (Variance of Gradients)
  let gradSum = 0;
  let gradSqSum = 0;
  let sampleCount = 0;
  const stride = 2; // Performance optimization
  
  for (let y = 1; y < height - 1; y += stride) {
    for (let x = 1; x < width - 1; x += stride) {
      const gx = -gray[(y-1)*width + (x-1)] + gray[(y-1)*width + (x+1)]
                 -2*gray[y*width + (x-1)] + 2*gray[y*width + (x+1)]
                 -gray[(y+1)*width + (x-1)] + gray[(y+1)*width + (x+1)];
      const gy = -gray[(y-1)*width + (x-1)] - 2*gray[(y-1)*width + x] - gray[(y-1)*width + (x+1)]
                 +gray[(y+1)*width + (x-1)] + 2*gray[(y+1)*width + x] + gray[(y+1)*width + (x+1)];
      
      const mag = Math.sqrt(gx*gx + gy*gy);
      gradSum += mag;
      gradSqSum += mag * mag;
      sampleCount++;
    }
  }
  
  const avgGrad = gradSum / (sampleCount || 1);
  const sharpness = (gradSqSum / (sampleCount || 1)) - (avgGrad * avgGrad);
  
  const isBlurred = sharpness < 12.0;
  const isLowLight = brightness < 45.0;
  
  // Histographical categorization: White Paper vs. Colored/Saturated ID Card
  let brightPixels = 0;
  let midTonePixels = 0;
  for (let i = 0; i < gray.length; i++) {
    if (gray[i] > 175) brightPixels++;
    else if (gray[i] > 70) midTonePixels++;
  }
  
  const brightRatio = brightPixels / gray.length;
  const midRatio = midTonePixels / gray.length;
  
  let detectedType: 'white_paper' | 'colored_card' | 'unknown' = 'unknown';
  if (brightRatio > 0.60 && contrast > 30) {
    detectedType = 'white_paper';
  } else if (midRatio > 0.45 || (brightRatio < 0.35 && contrast > 18)) {
    detectedType = 'colored_card';
  }
  
  return {
    brightness,
    contrast,
    sharpness,
    isBlurred,
    isLowLight,
    detectedType
  };
}
