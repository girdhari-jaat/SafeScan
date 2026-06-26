export interface Point {
  x: number;
  y: number;
}

export interface Line {
  m: number; // Slope
  c: number; // Intercept
}

export interface ImageQualityReport {
  brightness: number;     // Average pixel value (0 - 255)
  contrast: number;       // Standard deviation of pixel values
  sharpness: number;      // Gradient magnitude variance
  isBlurred: boolean;     // True if sharpness indicator is below threshold
  isLowLight: boolean;    // True if average brightness is insufficient
  detectedType: 'white_paper' | 'colored_card' | 'unknown';
}
