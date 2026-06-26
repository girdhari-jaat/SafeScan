// AUDITED: Removed 5 unused exports (clearMemoryCache, createBlobUrl, revokeBlobUrl, blobToBase64, getSharedCanvas)
import { ScanDocument, ScanPage } from '../types';

const DB_NAME = 'OfflineCamScannerDB';
const STORE_NAME = 'images';
const DB_VERSION = 5; // Migration: added imageCache store

let dbPromise: Promise<IDBDatabase> | null = null;


/**
 * Initialize IndexedDB for raw and processed image storage
 */
export function initDB(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise;

  dbPromise = new Promise((resolve, reject) => {
    try {
      if (typeof indexedDB === 'undefined' || !window.indexedDB) {
        throw new Error('IndexedDB is not supported or is blocked in this browser mode.');
      }
      const request = indexedDB.open(DB_NAME, DB_VERSION);

      request.onerror = (e) => {
        dbPromise = null;
        console.error('IndexedDB open error:', e);
        reject(new Error('Failed to open database. Please enable storage permissions.'));
      };

      request.onsuccess = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        
        db.onversionchange = () => {
          db.close();
          dbPromise = null;
        };

        resolve(db);
      };

      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        try {
          if (!db.objectStoreNames.contains(STORE_NAME)) {
            db.createObjectStore(STORE_NAME, { keyPath: 'id' });
          }
          if (!db.objectStoreNames.contains('pages')) {
            db.createObjectStore('pages', { keyPath: 'id' });
          }
          if (!db.objectStoreNames.contains('display-cache')) {
            db.createObjectStore('display-cache', { keyPath: 'id' });
          }
          if (!db.objectStoreNames.contains('imageCache')) {
            db.createObjectStore('imageCache', { keyPath: 'id' });
          }
        } catch (e) {
             console.error('IndexedDB migration error:', e);
        }
      };
    } catch (e) {
      console.error('IndexedDB init error:', e);
      reject(e);
    }
  });

  return dbPromise;
}

// In-memory cache map for instant blob retrieval with LRU eviction
const blobInMemoryCache = new Map<string, Blob>();
const blobCacheKeys: string[] = [];

function addToBlobCache(id: string, blob: Blob) {
  removeFromCacheKeys(id);
  blobInMemoryCache.set(id, blob);
  blobCacheKeys.push(id);

  // If cache exceeds limit, remove oldest entries to free RAM immediately (Limit set to 8 for low-memory 2GB platforms)
  if (blobInMemoryCache.size > 8) {
    for (let i = 0; i < 4; i++) {
        const oldestKey = blobCacheKeys.shift();
        if (oldestKey) {
            blobInMemoryCache.delete(oldestKey);
        }
    }
  }
}



function getFromBlobCache(id: string): Blob | null {
  if (blobInMemoryCache.has(id)) {
    removeFromCacheKeys(id);
    blobCacheKeys.push(id);
    return blobInMemoryCache.get(id) || null;
  }
  return null;
}

function removeFromBlobCache(id: string) {
  blobInMemoryCache.delete(id);
  removeFromCacheKeys(id);
}

function removeFromCacheKeys(key: string) {
  const idx = blobCacheKeys.indexOf(key);
  if (idx !== -1) {
    blobCacheKeys.splice(idx, 1);
  }
}

/**
 * Save an image blob to IndexedDB (with instant LRU memory cache)
 */
export async function saveImageBlob(id: string, blob: Blob): Promise<void> {
  addToBlobCache(id, blob);
  
  try {
    const db = await initDB();
    return await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction([STORE_NAME], 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      
      // Wait for transaction complete, not request success, to ensure disk flush
      transaction.oncomplete = () => resolve();
      transaction.onerror = (e) => {
        const error = (e.target as any).error;
        console.error('IndexedDB saveImageBlob error:', error);
        if (error && error.name === 'QuotaExceededError') {
             reject(error);
        } else {
             reject(new Error(`Failed to save image: ${id}`));
        }
      };

      store.put({ id, blob });
    });
  } catch (error: any) {
    console.error('saveImageBlob caught error:', error);
    if (error.name === 'QuotaExceededError') {
        // High-heat recovery: clear 20% of oldest blobs and retry once
        await reclaimStorage(0.2);
        return saveImageBlob(id, blob);
    }
    throw error;
  }
}

/**
 * Save multiple image blobs in a single low-latency transaction (highly optimized for batch operations)
 */
export async function batchSaveBlobs(items: { id: string; blob: Blob }[]): Promise<void> {
  if (items.length === 0) return;
  for (const item of items) {
    addToBlobCache(item.id, item.blob);
  }
  
  try {
    const db = await initDB();
    return await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction([STORE_NAME], 'readwrite');
      const store = transaction.objectStore(STORE_NAME);

      transaction.oncomplete = () => resolve();
      transaction.onerror = (e) => {
        const error = (e.target as any).error;
        console.error('IndexedDB batchSaveBlobs error:', error);
        if (error && error.name === 'QuotaExceededError') {
             reject(error);
        } else {
             reject(new Error('Failed to run batch save IndexedDB transaction.'));
        }
      };

      for (const item of items) {
        store.put({ id: item.id, blob: item.blob });
      }
    });
  } catch (error: any) {
    console.error('batchSaveBlobs caught error:', error);
    if (error.name === 'QuotaExceededError') {
         await reclaimStorage(0.3); // Reclaim more since this is a batch
         return batchSaveBlobs(items);
    }
    throw error;
  }
}

/**
 * Deletes a percentage of old blobs to free space
 */
async function reclaimStorage(percentage: number): Promise<void> {
  try {
    const db = await initDB();
    const tx = db.transaction([STORE_NAME], 'readwrite');
    const store = tx.objectStore(STORE_NAME);
    const keysRequest = store.getAllKeys();

    return new Promise((resolve) => {
      keysRequest.onsuccess = () => {
        const keys = keysRequest.result as string[];
        const countToDelete = Math.ceil(keys.length * percentage);
        for (let i = 0; i < countToDelete; i++) {
          store.delete(keys[i]);
          removeFromBlobCache(keys[i]);
        }
        tx.oncomplete = () => resolve();
      };
    });
  } catch (e) {
    console.error('Storage reclamation failed:', e);
  }
}

/**
 * Retrieve an image blob from IndexedDB (checks memory cache first)
 */
export async function getImageBlob(id: string): Promise<Blob | null> {
  const cached = getFromBlobCache(id);
  if (cached) {
    return cached;
  }
  
  try {
    const db = await initDB();
    return await new Promise((resolve, reject) => {
      const transaction = db.transaction([STORE_NAME], 'readonly');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.get(id);

      request.onsuccess = () => {
        const result = request.result;
        if (result && result.blob) {
          addToBlobCache(id, result.blob);
          resolve(result.blob); // Returns Blob safely
        } else {
          resolve(null);
        }
      };
      
      request.onerror = (e) => {
        console.error('IndexedDB getImageBlob error:', e);
        reject(new Error(`Failed to retrieve image: ${id}`));
      };
    });
  } catch (error) {
    console.error('getImageBlob caught error:', error);
    return null;
  }
}

/**
 * Delete an image blob from IndexedDB
 */
export async function deleteImageBlob(id: string): Promise<void> {
  removeFromBlobCache(id);
  
  // First, delete from display-cache and imageCache to keep them in sync
  try {
    const baseId = id.replace(/^(raw_|proc_)/, '');
    await deleteDisplayCacheBlob(id);
    await deleteImageCacheBlob(id);
    if (baseId !== id) {
      await deleteDisplayCacheBlob(baseId);
      await deleteImageCacheBlob(baseId);
    }
  } catch (err) {
    console.warn('Failed to clear associated caches during deleteImageBlob:', err);
  }

  try {
    const db = await initDB();
    return await new Promise((resolve, reject) => {
      const transaction = db.transaction([STORE_NAME], 'readwrite');
      const store = transaction.objectStore(STORE_NAME);
      const request = store.delete(id);

      transaction.oncomplete = () => resolve();
      request.onerror = (e) => {
        console.error('IndexedDB deleteImageBlob error:', e);
        reject(new Error(`Failed to delete image: ${id}`));
      };
    });
  } catch (error) {
    console.error('deleteImageBlob caught error:', error);
  }
}

/**
 * Save an image to the display cache
 */
export async function saveDisplayCacheBlob(id: string, blob: Blob): Promise<void> {
  try {
    const db = await initDB();
    return await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(['display-cache'], 'readwrite');
      const store = transaction.objectStore('display-cache');
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(new Error(`Failed to save display cache: ${id}`));
      store.put({ id, blob });
    });
  } catch (error) {
    console.error('saveDisplayCacheBlob error:', error);
  }
}

/**
 * Retrieve an image from the display cache
 */
export async function getDisplayCacheBlob(id: string): Promise<Blob | null> {
  try {
    const db = await initDB();
    return await new Promise((resolve) => {
      const transaction = db.transaction(['display-cache'], 'readonly');
      const store = transaction.objectStore('display-cache');
      const request = store.get(id);
      request.onsuccess = () => {
        resolve(request.result ? request.result.blob : null);
      };
      request.onerror = () => resolve(null);
    });
  } catch (error) {
    console.error('getDisplayCacheBlob error:', error);
    return null;
  }
}

/**
 * Delete an image from the image cache
 */
export async function deleteImageCacheBlob(id: string): Promise<void> {
  try {
    const db = await initDB();
    return await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(['imageCache'], 'readwrite');
      const store = transaction.objectStore('imageCache');
      
      store.delete(id);

      const request = store.openCursor();
      request.onsuccess = (event) => {
        const cursor = (event.target as any).result;
        if (cursor) {
          const key = cursor.key;
          if (typeof key === 'string' && (key === id || key.startsWith(id) || key.includes(id))) {
            cursor.delete();
          }
          cursor.continue();
        }
      };

      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(new Error(`Failed to delete image cache: ${id}`));
    });
  } catch (error) {
    console.error('deleteImageCacheBlob error:', error);
  }
}

/**
 * Delete an image from the display cache
 */
export async function deleteDisplayCacheBlob(id: string): Promise<void> {
  try {
    const db = await initDB();
    return await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(['display-cache'], 'readwrite');
      const store = transaction.objectStore('display-cache');
      
      store.delete(id);

      const request = store.openCursor();
      request.onsuccess = (event) => {
        const cursor = (event.target as any).result;
        if (cursor) {
          const key = cursor.key;
          if (typeof key === 'string' && (key === id || key.startsWith(id) || key.includes(id))) {
            cursor.delete();
          }
          cursor.continue();
        }
      };

      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(new Error(`Failed to delete display cache: ${id}`));
    });
  } catch (error) {
    console.error('deleteDisplayCacheBlob error:', error);
  }
}

/**
 * Clear the display-cache and imageCache stores ONLY (preserving original images in 'images' store)
 */
export async function clearDisplayCache(): Promise<void> {
  try {
    const db = await initDB();
    return await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(['display-cache', 'imageCache'], 'readwrite');
      const displayStore = transaction.objectStore('display-cache');
      const imageCacheStore = transaction.objectStore('imageCache');
      
      displayStore.clear();
      imageCacheStore.clear();
      
      transaction.oncomplete = () => resolve();
      transaction.onerror = (e) => {
        console.error('IndexedDB clearDisplayCache error:', e);
        reject(new Error('Failed to clear display cache.'));
      };
    });
  } catch (error) {
    console.error('clearDisplayCache caught error:', error);
  }
}

/**
 * Save an image to the image cache
 */
export async function saveImageCacheBlob(id: string, blob: Blob): Promise<void> {
  try {
    const db = await initDB();
    return await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(['imageCache'], 'readwrite');
      const store = transaction.objectStore('imageCache');
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(new Error(`Failed to save image cache: ${id}`));
      store.put({ id, blob });
    });
  } catch (error) {
    console.error('saveImageCacheBlob error:', error);
  }
}

/**
 * Retrieve an image from the image cache
 */
export async function getImageCacheBlob(id: string): Promise<Blob | null> {
  try {
    const db = await initDB();
    return await new Promise((resolve) => {
      const transaction = db.transaction(['imageCache'], 'readonly');
      const store = transaction.objectStore('imageCache');
      const request = store.get(id);
      request.onsuccess = () => {
        resolve(request.result ? request.result.blob : null);
      };
      request.onerror = () => resolve(null);
    });
  } catch (error) {
    console.error('getImageCacheBlob error:', error);
    return null;
  }
}
const DOCS_LS_KEY = 'offline_scanner_documents_list';
const PAGES_LS_KEY = 'offline_scanner_pages_list';

let saveDocsTimeout: any = null;
let savePagesTimeout: any = null;

export function getOfflineDocuments(): ScanDocument[] {
  try {
    const data = localStorage.getItem(DOCS_LS_KEY);
    return data ? JSON.parse(data) : [];
  } catch (e) {
    return [];
  }
}

export function saveOfflineDocuments(docs: ScanDocument[]): void {
  try {
    localStorage.setItem(DOCS_LS_KEY, JSON.stringify(docs));
  } catch (e) {
    console.error('Error saving documents to LocalStorage:', e);
  }
}

export function getOfflinePages(): ScanPage[] {
  try {
    const data = localStorage.getItem(PAGES_LS_KEY);
    return data ? JSON.parse(data) : [];
  } catch (e) {
    return [];
  }
}

export function saveOfflinePages(pages: ScanPage[]): void {
  try {
    localStorage.setItem(PAGES_LS_KEY, JSON.stringify(pages));
  } catch (e) {
    console.error('Error saving pages to LocalStorage:', e);
  }
}



/**
 * Save page metadata ONLY (non-destructive)
 */
export async function savePageMeta(id: string, meta: any): Promise<void> {
  try {
    const db = await initDB();
    return await new Promise((resolve, reject) => {
      const transaction = db.transaction(['pages'], 'readwrite');
      const store = transaction.objectStore('pages');
      
      transaction.oncomplete = () => resolve();
      transaction.onerror = (e) => {
        console.error('IndexedDB savePageMeta error:', e);
        reject(new Error(`Failed to save page meta: ${id}`));
      };

      store.put({ id, meta });
    });
  } catch (error) {
    console.error('savePageMeta caught error:', error);
    throw error;
  }
}

/**
 * Retrieve page metadata from DB
 */
export async function getPageMeta(id: string): Promise<any | null> {
  try {
    const db = await initDB();
    return await new Promise((resolve, reject) => {
      const transaction = db.transaction(['pages'], 'readonly');
      const store = transaction.objectStore('pages');
      const request = store.get(id);

      request.onsuccess = () => {
        const result = request.result;
        resolve(result ? result.meta : null);
      };
      
      request.onerror = (e) => {
        console.error('IndexedDB getPageMeta error:', e);
        reject(new Error(`Failed to retrieve page meta: ${id}`));
      };
    });
  } catch (error) {
    console.error('getPageMeta caught error:', error);
    return null;
  }
}
