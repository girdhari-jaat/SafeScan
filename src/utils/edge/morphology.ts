/**
 * Morphological Dilation Helper
 * Expands binary (or grayscale high intensity) regions. Useful for joining
 * slightly broken outline segments.
 * Uses a standard 3x3 square structuring element.
 */
export function dilate(image: Uint8Array, width: number, height: number): Uint8Array {
  const result = new Uint8Array(image.length);

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      let maxVal = 0;

      // 3x3 structuring element
      for (let ky = -1; ky <= 1; ky++) {
        const ny = y + ky;
        if (ny < 0 || ny >= height) continue;

        for (let kx = -1; kx <= 1; kx++) {
          const nx = x + kx;
          if (nx < 0 || nx >= width) continue;

          const val = image[ny * width + nx];
          if (val > maxVal) {
            maxVal = val;
          }
        }
      }
      result[y * width + x] = maxVal;
    }
  }

  return result;
}

/**
 * Morphological Erosion Helper
 * Shrinks binary regions. Useful for stripping out single-pixel noise specs
 * and separating falsely-merged features.
 */
export function erode(image: Uint8Array, width: number, height: number): Uint8Array {
  const result = new Uint8Array(image.length);

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      let minVal = 255;

      // 3x3 structuring element
      for (let ky = -1; ky <= 1; ky++) {
        const ny = y + ky;
        if (ny < 0 || ny >= height) continue;

        for (let kx = -1; kx <= 1; kx++) {
          const nx = x + kx;
          if (nx < 0 || nx >= width) continue;

          const val = image[ny * width + nx];
          if (val < minVal) {
            minVal = val;
          }
        }
      }
      result[y * width + x] = minVal;
    }
  }

  return result;
}

/**
 * Morphological Closing Helper (Dilation followed by Erosion)
 * Fills in minor gaps, cracks, and holes within dark lines or contours.
 */
export function morphologicalClose(image: Uint8Array, width: number, height: number): Uint8Array {
  const dilated = dilate(image, width, height);
  return erode(dilated, width, height);
}
