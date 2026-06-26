/**
 * Otsu's Dynamic Auto-Thresholding
 * Computes an optimal binarization threshold for a grayscale image by maximizing
 * the inter-class variance between background and foreground pixel pools.
 */
export function computeOtsuThreshold(gray: Uint8Array, width: number, height: number): number {
  const total = width * height;
  const histogram = new Int32Array(256);

  // 1. Build grayscale histogram
  for (let i = 0; i < total; i++) {
    histogram[gray[i]]++;
  }

  // 2. Compute cumulative sums and weights
  let sum = 0;
  for (let i = 0; i < 256; i++) {
    sum += i * histogram[i];
  }

  let sumB = 0;
  let wB = 0;
  let wF = 0;

  let maxVariance = 0;
  let threshold = 0;

  for (let t = 0; t < 256; t++) {
    wB += histogram[t];
    if (wB === 0) continue;

    wF = total - wB;
    if (wF === 0) break;

    sumB += t * histogram[t];

    const mB = sumB / wB;
    const mF = (sum - sumB) / wF;

    // Inter-class variance
    const varianceBetween = wB * wF * (mB - mF) * (mB - mF);

    if (varianceBetween > maxVariance) {
      maxVariance = varianceBetween;
      threshold = t;
    }
  }

  return threshold;
}

/**
 * Applies a binary threshold to a grayscale image using a computed or static threshold.
 */
export function binarizeImage(gray: Uint8Array, threshold: number): Uint8Array {
  const binary = new Uint8Array(gray.length);
  for (let i = 0; i < gray.length; i++) {
    binary[i] = gray[i] >= threshold ? 255 : 0;
  }
  return binary;
}
