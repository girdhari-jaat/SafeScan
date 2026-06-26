/**
 * Types & Interfaces for SafeScan
 */

export interface Vector2D {
  x: number; // Percentage relative to image width (0 to 100)
  y: number; // Percentage relative to image height (0 to 100)
}

export interface PageCorners {
  tl: Vector2D;
  tr: Vector2D;
  br: Vector2D;
  bl: Vector2D;
}

export type ScanFilterType = 'original' | 'auto-enhance' | 'magic' | 'bw' | 'grayscale' | 'noir' | 'paper' | 'document' | 'card' | 'pro-scan';

export interface PageAdjustments {
  brightness: number; // -100 to 100 (default 0)
  contrast: number;   // -100 to 100 (default 0)
  saturation: number; // -100 to 100 (default 0)
  grayscale?: number; // 0 to 100 (default 0)
  sharpness?: number; // -100 to 100 (default 0)
  shadows?: number;   // -100 to 100 (default 0)
  temperature?: number; // -100 to 100 (default 0)
  shadowRemove?: boolean;
  autoAdjust?: boolean;
}

export interface ScanPage {
  id: string;
  docId: string;
  originalImageId: string;  // IndexedDB ID for original image blob
  processedImageId: string; // IndexedDB ID for processed image blob (flattened + filtered)
  corners: PageCorners;
  rotation: number; // 0, 90, 180, 270 degrees
  filter: ScanFilterType;
  adjustments: PageAdjustments;
  addedAt: number;
  meta?: any;
}

export interface ScanDocument {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  pageIds: string[];
  tags: string[];
}



