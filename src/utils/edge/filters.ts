/**
 * Advanced Bilateral Filter to smooth textures and noise while preserving strong edge boundaries.
 * Inspired by OpenCV's bilateralFilter.
 */
export function bilateralFilter(
  gray: Uint8Array,
  width: number,
  height: number,
  diameter: number,
  sigmaI: number,
  sigmaS: number
): Uint8Array {
  const result = new Uint8Array(width * height);
  const radius = Math.floor(diameter / 2);
  
  // Spatial Gaussian Kernel
  const spatialWeights = new Float32Array((radius * 2 + 1) * (radius * 2 + 1));
  let idx = 0;
  for (let i = -radius; i <= radius; i++) {
    for (let j = -radius; j <= radius; j++) {
      spatialWeights[idx++] = Math.exp(-(i * i + j * j) / (2 * sigmaS * sigmaS));
    }
  }
  
  // Range (Intensity) Gaussian Kernel
  const intensityWeights = new Float32Array(256);
  for (let i = 0; i < 256; i++) {
    intensityWeights[i] = Math.exp(-(i * i) / (2 * sigmaI * sigmaI));
  }
  
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      let sumWeight = 0;
      let sumValue = 0;
      const centerVal = gray[y * width + x];
      
      idx = 0;
      for (let i = -radius; i <= radius; i++) {
        const cy = y + i;
        if (cy < 0 || cy >= height) { idx += (radius * 2 + 1); continue; }
        
        for (let j = -radius; j <= radius; j++) {
          const cx = x + j;
          if (cx < 0 || cx >= width) { idx++; continue; }
          
          const val = gray[cy * width + cx];
          const weight = spatialWeights[idx++] * intensityWeights[Math.abs(val - centerVal)];
          sumWeight += weight;
          sumValue += weight * val;
        }
      }
      result[y * width + x] = Math.round(sumValue / sumWeight);
    }
  }
  return result;
}
