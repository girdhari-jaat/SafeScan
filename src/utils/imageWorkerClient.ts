// AUDITED: Removed unused imports
import * as Comlink from 'comlink';
import { addLog } from './renderStats';

let workerInstance: Worker | null = null;
let apiInstance: any = null;

function getWorkerAPI() {
  if (!apiInstance) {
    workerInstance = new Worker(
      new URL('./image.worker.ts', import.meta.url),
      { type: 'module' }
    );
    apiInstance = Comlink.wrap(workerInstance);
  }
  return apiInstance;
}

export function initWorker() {
  getWorkerAPI();
}

/**
 * Executes high-speed corner detection in a background Web Worker
 */
export interface DetectCornersResult {
  corners: { x: number; y: number }[] | null;
  debugDelta?: { top: number; bottom: number };
  width: number;
  height: number;
  originalWidth: number;
  originalHeight: number;
  aiSucceeded?: boolean;
  aiErrorMsg?: string | null;
  usedAi?: boolean;
}

export async function detectCornersOffThread(
  bitmap: ImageBitmap, 
  scanMode: 'paper' | 'card' | 'grid' | 'cnic' | 'idcard' | 'a4' = 'paper',
  isRealtime: boolean = false
): Promise<DetectCornersResult | null> {
  const api = getWorkerAPI();
  const res = await api.detectCorners(
    Comlink.transfer(bitmap, [bitmap]),
    scanMode,
    isRealtime
  );
  if (res?.corners && res.corners.length === 4) {
    // Only log if something found, but not every time to avoid flood. 
    // Usually throttled by caller anyway.
    // addLog(`Geometric scan: Detected ${scanMode} edges`);
  }
  return res;
}

/**
 * Runs the full perspectivity homography and pixel adjustment filter off-thread
 */
export function generatePageHash(page: any): string {
  if (!page) return '';
  const parts = [
    page.id,
    page.rotation || 0,
    page.filter || 'none',
    JSON.stringify(page.corners),
    JSON.stringify(page.adjustments || {}),
  ];
  return parts.join('|');
}

const previewCache = new Map<string, Promise<ImageBitmap>>();
const blobCache = new Map<string, Promise<Blob>>();

export async function processFinalImageOffThread(
  sourceImage: HTMLImageElement | HTMLCanvasElement | ImageBitmap,
  corners: any,
  rotation: number,
  filter: any,
  adjustments: any,
  sourceType?: string
): Promise<Blob> {
  const isShadowRemoveEnabled = typeof localStorage !== 'undefined' && localStorage.getItem("shadowRemoveEnabled") === "true";
  const enhancedAdjustments = {
    ...adjustments,
    shadowRemove: adjustments?.shadowRemove ?? isShadowRemoveEnabled,
    autoAdjust: adjustments?.autoAdjust ?? isShadowRemoveEnabled,
  };

  const hash = `process|${rotation}|${filter}|${JSON.stringify(corners)}|${JSON.stringify(enhancedAdjustments)}|${sourceType}`;
  
  if (blobCache.has(hash)) {
    if (sourceImage instanceof ImageBitmap) {
      try { sourceImage.close(); } catch (e) {}
    }
    return blobCache.get(hash)!;
  }

  const workPromise = (async () => {
    const api = getWorkerAPI();
    
    const logs = [`Warping active (Rot: ${rotation}°, Filter: ${filter})`];
    if (enhancedAdjustments?.shadowRemove) logs.push('Pipeline: Shadow removal engaged');
    if (enhancedAdjustments?.autoAdjust) logs.push('Pipeline: Auto-brightness engaged');
    addLog(logs.join(' | '));

    let bitmap: ImageBitmap;
    
    if (sourceImage instanceof ImageBitmap) {
      bitmap = sourceImage;
    } else {
      bitmap = await createImageBitmap(sourceImage);
    }

    return api.processFinalImage(
      Comlink.transfer(bitmap, [bitmap]),
      corners,
      rotation,
      filter,
      enhancedAdjustments,
      sourceType
    );
  })();

  blobCache.set(hash, workPromise);
  setTimeout(() => blobCache.delete(hash), 3000); // 3s cache
  return workPromise;
}

import { ScanPage } from './../types';

/**
 * Compiles a completely encrypted high-resolution PDF document fully off-thread inside the worker
 */
export async function generatePDFOffThread(
  pagesData: { blob: Blob; page: ScanPage }[],
  options: {
    pageSize: 'a4' | 'letter' | 'fit';
    orientation: 'portrait' | 'landscape' | 'auto';
    quality: number;
    password?: string;
  }
): Promise<Blob> {
  const api = getWorkerAPI();
  addLog(`Export: Starting high-res PDF bundle (${pagesData.length} pages, Quality: ${options.quality * 100}%)`);
  
  // We can just pass the Blobs directly via postMessage; they are structured-cloneable 
  // and natively reference the identical underlying memory or disk backing.
  const payloadPages = pagesData.map(p => ({
    blob: p.blob,
    page: p.page
  }));

  const pdfArrayBuffer = await api.generatePDFOffThread(
    payloadPages,
    options
  );

  return new Blob([pdfArrayBuffer], { type: 'application/pdf' });
}

/**
 * Runs the centralized applyFilter off-thread in the worker
 */
export async function applyFilterOffThread(bitmap: ImageBitmap, filterName: string): Promise<ImageBitmap> {
  const api = getWorkerAPI();
  addLog(`Pixel Processing: Applying ${filterName} filter`);
  return api.applyFilter(
    Comlink.transfer(bitmap, [bitmap]),
    filterName
  );
}

/**
 * High-performance off-thread client-side perspective warping, rotation, filtering, and adjustment preview.
 * This includes a deduplication layer to skip redundant worker hits for identical image states.
 */
export async function warpPreview(
  bitmap: ImageBitmap, 
  meta: { cropPoints: any; rotate: number; filter: string; adjustments: any; scanMode?: 'paper' | 'card' | 'grid' | 'idcard' | 'a4' | 'cnic' }
): Promise<ImageBitmap> {
  const isShadowRemoveEnabled = typeof localStorage !== 'undefined' && localStorage.getItem("shadowRemoveEnabled") === "true";
  const enhancedMeta = {
    ...meta,
    adjustments: {
      ...meta.adjustments,
      shadowRemove: meta.adjustments?.shadowRemove ?? isShadowRemoveEnabled,
      autoAdjust: meta.adjustments?.autoAdjust ?? isShadowRemoveEnabled,
    }
  };

  const hash = `warp|${enhancedMeta.rotate}|${enhancedMeta.filter}|${JSON.stringify(enhancedMeta.cropPoints)}|${JSON.stringify(enhancedMeta.adjustments)}`;
  
  if (previewCache.has(hash)) {
    try { bitmap.close(); } catch (e) {}
    const original = await previewCache.get(hash)!;
    return createImageBitmap(original);
  }

  const workPromise = (async () => {
    const api = getWorkerAPI();
    addLog(`Warp UI: Quick preview update (Filter: ${enhancedMeta.filter})`);
    return api.warpPreview(
      Comlink.transfer(bitmap, [bitmap]),
      enhancedMeta
    );
  })();

  previewCache.set(hash, workPromise);
  setTimeout(() => previewCache.delete(hash), 5000); // 5s cache
  
  return workPromise;
}

/**
 * High-performance off-thread capture and crop of camera frame.
 */
export async function handleCapturedFrameOffThread(
  bitmap: ImageBitmap,
  aspectRatio: number,
  isLowMemory: boolean,
  enhancements?: { shadowRemove?: boolean; autoAdjust?: boolean }
): Promise<Blob> {
  const api = getWorkerAPI();
  
  const msg = [`Capture: Processing raw frame (${isLowMemory ? 'Fast-buffer' : 'High-res'})`];
  if (enhancements?.shadowRemove) msg.push('Shadow removal active');
  addLog(msg.join(' | '));

  return api.handleCapturedFrame(
    Comlink.transfer(bitmap, [bitmap]),
    aspectRatio,
    isLowMemory,
    enhancements
  );
}

/**
 * High-performance off-thread card/grid compilation directly to PDF inside Web Worker
 */
export async function generateCardPDFOffThread(
  cardsData: { blob: Blob; card: any }[],
  options: {
    mode: 'idcard' | 'grid';
    title: string;
    quality: number;
  }
): Promise<Blob> {
  const api = getWorkerAPI();
  addLog('Sending generate card PDF request to worker');
  const finalBlob = await api.generateCardPDFOffThread(
    cardsData,
    options
  );

  return finalBlob;
}


