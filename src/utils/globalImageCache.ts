// High-Performance In-Memory Zero-latency Cache with LRU Eviction for UI Object URLs
import { generatePageHash } from './imageWorkerClient';
import { ScanPage } from '../types';

class GlobalImageCache {
  private urlCache = new Map<string, string>(); // hash -> objectURL
  private blobCache = new Map<string, Blob>(); // hash -> Blob
  private keysQueue: string[] = [];
  
  // High-performance threshold supporting smooth UX on 2GB/3GB low-spec devices
  private readonly MAX_SIZE = 100;

  public getUrl(hash: string): string | null {
    if (this.urlCache.has(hash)) {
      // Refresh item priority in LRU
      const idx = this.keysQueue.indexOf(hash);
      if (idx !== -1) {
        this.keysQueue.splice(idx, 1);
      }
      this.keysQueue.push(hash);
      return this.urlCache.get(hash) || null;
    }
    return null;
  }

  public getBlob(hash: string): Blob | null {
    return this.blobCache.get(hash) || null;
  }

  public put(hash: string, blob: Blob): string {
    if (this.urlCache.has(hash)) {
      return this.urlCache.get(hash)!;
    }

    const url = URL.createObjectURL(blob);
    this.blobCache.set(hash, blob);
    this.urlCache.set(hash, url);
    this.keysQueue.push(hash);

    // Enforce strict memory limits for 3GB low-end phones
    if (this.urlCache.size > this.MAX_SIZE) {
      const oldestHash = this.keysQueue.shift();
      if (oldestHash) {
        const urlToRevoke = this.urlCache.get(oldestHash);
        if (urlToRevoke) {
          URL.revokeObjectURL(urlToRevoke);
        }
        this.urlCache.delete(oldestHash);
        this.blobCache.delete(oldestHash);
      }
    }

    return url;
  }

  public getPageUrlSynchronous(page: ScanPage): string | null {
    const hash = generatePageHash(page);
    return this.getUrl(hash);
  }

  public clear() {
    this.urlCache.forEach((url) => URL.revokeObjectURL(url));
    this.urlCache.clear();
    this.blobCache.clear();
    this.keysQueue = [];
  }
}

export const globalImageCache = new GlobalImageCache();
