// AUDITED: Fixed canvas leaks and removed unused exports
import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { ScanDocument, ScanPage, PageCorners } from '../types';
import { getImageBlob, getDisplayCacheBlob, saveDisplayCacheBlob } from '../utils/db';
import { exportDocumentToPDF, shareOrDownloadFile, PDFExportOptions } from '../utils/pdfExport';
import { generatePageHash } from '../utils/imageWorkerClient';
import { globalImageCache } from '../utils/globalImageCache';

interface UseEditorHookProps {
  activeDocument: ScanDocument;
  pages: ScanPage[];
  onBack: () => void;
  onUpdatePage: (updatedPage: ScanPage) => void;
  onReorderPages: (pageIds: string[]) => void;
  onDeletePage: (pageId: string) => void;
  initialCroppingPageId?: string;
  onClearInitialCropping?: () => void;
}

export function useEditorHook({
  activeDocument,
  pages,
  onUpdatePage,
  onReorderPages,
  initialCroppingPageId,
  onClearInitialCropping,
}: UseEditorHookProps) {
  const docPages = useMemo(() => {
    const filtered = pages.filter((p) => p.docId === activeDocument.id);
    if (!activeDocument.pageIds || activeDocument.pageIds.length === 0) return filtered;
    
    // Sort based on pageIds order
    return [...filtered].sort((a, b) => {
      const idxA = activeDocument.pageIds.indexOf(a.id);
      const idxB = activeDocument.pageIds.indexOf(b.id);
      if (idxA === -1) return 1;
      if (idxB === -1) return -1;
      return idxA - idxB;
    });
  }, [pages, activeDocument.id, activeDocument.pageIds]);

  const [showExportModal, setShowExportModal] = useState(false);
  const [exportOptions, setExportOptions] = useState<PDFExportOptions>({
    pageSize: 'a4',
    orientation: 'auto',
    quality: 0.9,
    documentTitle: activeDocument.title || 'Scanned_Document',
  });
  const [pdfProgress, setPdfProgress] = useState({ current: 0, total: 0, building: false });

  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const [showDeleteConfirmInLightbox, setShowDeleteConfirmInLightbox] = useState<boolean>(false);
  const [lightboxUrl, setLightboxUrl] = useState<string>('');
  const [lightboxScale, setLightboxScale] = useState<number>(1);
  const blobCacheRef = useRef<Record<string, Blob>>({});
  const [pageUrls, setPageUrls] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    const pagesToLoad = pages.filter(p => p.docId === activeDocument.id);
    pagesToLoad.forEach(p => {
      const hash = generatePageHash(p);
      const cachedUrl = globalImageCache.getUrl(hash);
      if (cachedUrl) {
         initial[p.id] = cachedUrl;
      }
    });
    return initial;
  });

  useEffect(() => {
    setExportOptions(prev => ({
      ...prev,
      documentTitle: activeDocument.title || 'Scanned_Document'
    }));
  }, [activeDocument.title]);

  const pendingHashesRef = useRef<Set<string>>(new Set());

  const loadAllFilteredPageBlobs = useCallback(async () => {
    const pagesToLoad = pages.filter(p => p.docId === activeDocument.id);
    
    // Determine which pages need processing/fetching based on their current state hash
    const hashesToFilter = pagesToLoad.map(p => ({ p, hash: generatePageHash(p) }));
    
    // Sync any already cached hashes to the current page IDs without depending on pageUrls state directly
    setPageUrls(prev => {
      let needed = false;
      const next = { ...prev };
      hashesToFilter.forEach(({ p, hash }) => {
        const cachedUrl = globalImageCache.getUrl(hash);
        if (cachedUrl && next[p.id] !== cachedUrl) {
          next[p.id] = cachedUrl;
          needed = true;
        }
      });
      return needed ? next : prev;
    });

    const pagesToFetch = hashesToFilter.filter(({ hash }) => {
      return !globalImageCache.getUrl(hash) && !pendingHashesRef.current.has(hash);
    });

    if (pagesToFetch.length === 0) {
      return;
    }

    try {
      const { processFinalImageOffThread } = await import('../utils/imageWorkerClient');
      
      await Promise.all(pagesToFetch.map(async ({ p, hash }) => {
        pendingHashesRef.current.add(hash);
        try {
          // Try display cache first (required by strict policy)
          const cachedBlob = await getDisplayCacheBlob(hash);
          if (cachedBlob) {
             const url = globalImageCache.put(hash, cachedBlob);
             setPageUrls(prev => ({ ...prev, [p.id]: url }));
             return; // Success
          }

          // Fallback to processing
          const blob = await getImageBlob(p.originalImageId);
          if (blob) {
             const bitmap = await createImageBitmap(blob);
             const procBlob = await processFinalImageOffThread(bitmap, p.corners, p.rotation, p.filter, p.adjustments, 'preview');
             
             // Save to display cache
             await saveDisplayCacheBlob(hash, procBlob);
             
             const url = globalImageCache.put(hash, procBlob);
             setPageUrls(prev => ({ ...prev, [p.id]: url }));
          }
        } finally {
          pendingHashesRef.current.delete(hash);
        }
      }));
    } catch (err) {
      console.error('Parallel blob load failed:', err);
    }
  }, [pages, activeDocument.id]);

  useEffect(() => {
    loadAllFilteredPageBlobs();
  }, [loadAllFilteredPageBlobs]);

  // Managed by globalImageCache to support zero-latency reopening and low-memory state reuse

  const handleLightboxNext = () => {
    if (lightboxIndex !== null && lightboxIndex < docPages.length - 1) {
      setLightboxIndex(lightboxIndex + 1);
    }
  };

  const handleLightboxPrev = () => {
    if (lightboxIndex !== null && lightboxIndex > 0) {
      setLightboxIndex(lightboxIndex - 1);
    }
  };

  const [lightboxPan, setLightboxPan] = useState({ x: 0, y: 0 });
  const [isPanDragging, setIsPanDragging] = useState(false);
  const [panDragStart, setPanDragStart] = useState({ x: 0, y: 0 });
  const [touchStartX, setTouchStartX] = useState<number | null>(null);

  const [editingPage, setEditingPage] = useState<ScanPage | null>(null);
  const [editingBlob, setEditingBlob] = useState<Blob | null>(null);
  const [loadingEditingBlob, setLoadingEditingBlob] = useState<boolean>(false);
  const [detectedCorners, setDetectedCorners] = useState<PageCorners | null>(null);

  const handleStartEditing = async (page: ScanPage) => {
    setLoadingEditingBlob(true);
    setEditingPage(page);
    setDetectedCorners(null);
    try {
      let blob = await getImageBlob(page.originalImageId);
      if (blob) {
        // Downscale large captured image to max 1080px proxy for Crop/Adjust UI speed
        const bitmap = await createImageBitmap(blob);
        let proxyWidth = bitmap.width;
        let proxyHeight = bitmap.height;
        if (proxyWidth > 1080 || proxyHeight > 1080) {
          if (proxyWidth > proxyHeight) {
            proxyHeight = Math.round(proxyHeight * 1080 / proxyWidth);
            proxyWidth = 1080;
          } else {
            proxyWidth = Math.round(proxyWidth * 1080 / proxyHeight);
            proxyHeight = 1080;
          }
          const canvas = document.createElement('canvas');
          canvas.width = proxyWidth;
          canvas.height = proxyHeight;
          const ctx = canvas.getContext('2d');
          if (ctx) {
            ctx.drawImage(bitmap, 0, 0, proxyWidth, proxyHeight);
            const proxyBlob = await new Promise<Blob | null>(res => {
              canvas.toBlob(blob => {
                canvas.width = 0;
                canvas.height = 0;
                res(blob);
              }, 'image/png');
            });
            if (proxyBlob) blob = proxyBlob;
          }
        }
        bitmap.close();
        setEditingBlob(blob);
      }
    } catch (err) {
      console.error('Failed to load image for cropping:', err);
    } finally {
      setLoadingEditingBlob(false);
    }
  };

  const handleSaveEditedPage = async (
    _finalBlob: Blob,
    corners: any,
    rotation: number,
    filter: any,
    adjustments: any
  ) => {
    if (!editingPage) return;
    try {
      // NON-DESTRUCTIVE ARCHITECTURE:
      
      const revisedPage: ScanPage = {
        ...editingPage,
        corners,
        rotation,
        filter,
        adjustments,
      };

      onUpdatePage(revisedPage);
    } catch (err) {
      console.error('Could not save updated page matrix:', err);
      alert('Error updating page scans: ' + String(err));
    } finally {
      setEditingPage(null);
      setEditingBlob(null);
      setDetectedCorners(null);
    }
  };

  const handleSaveEditedPageAndNext = async (
    _finalBlob: Blob,
    corners: any,
    rotation: number,
    filter: any,
    adjustments: any
  ) => {
    if (!editingPage) return;
    
    const currentIndex = docPages.findIndex(p => p.id === editingPage.id);
    if (currentIndex === -1 || currentIndex >= docPages.length - 1) {
      // No next page, just save normally and close editor
      await handleSaveEditedPage(_finalBlob, corners, rotation, filter, adjustments);
      return;
    }

    const nextPage = docPages[currentIndex + 1];

    // Save current page changes first, but DO NOT close or nullify editingPage/editingBlob
    const revisedPage: ScanPage = {
      ...editingPage,
      corners,
      rotation,
      filter,
      adjustments,
    };
    onUpdatePage(revisedPage);

    // Fetch next page's original image blob in background to ensure zero loading state or full-screen overlay for the previous page,
    // maintaining smooth seamless transition state for multi-page scan.
    try {
      let blob = await getImageBlob(nextPage.originalImageId);
      if (blob) {
        // Downscale large captured image exactly the same way to ensure performance remains pristine
        const bitmap = await createImageBitmap(blob);
        let proxyWidth = bitmap.width;
        let proxyHeight = bitmap.height;
        if (proxyWidth > 1080 || proxyHeight > 1080) {
          if (proxyWidth > proxyHeight) {
            proxyHeight = Math.round(proxyHeight * 1080 / proxyWidth);
            proxyWidth = 1080;
          } else {
            proxyWidth = Math.round(proxyWidth * 1080 / proxyHeight);
            proxyHeight = 1080;
          }
          const canvas = document.createElement('canvas');
          canvas.width = proxyWidth;
          canvas.height = proxyHeight;
          const ctx = canvas.getContext('2d');
          if (ctx) {
            ctx.drawImage(bitmap, 0, 0, proxyWidth, proxyHeight);
            const proxyBlob = await new Promise<Blob | null>(res => {
              canvas.toBlob(blob => {
                canvas.width = 0;
                canvas.height = 0;
                res(blob);
              }, 'image/png');
            });
            if (proxyBlob) blob = proxyBlob;
          }
        }
        bitmap.close();

        // Update BOTH page and blob together in the same tick so Crop transitions instantly
        setEditingPage(nextPage);
        setEditingBlob(blob);
        setDetectedCorners(null);
      }
    } catch (e) {
      console.error('Error loading next page during multi-page workflow:', e);
    }
  };

  useEffect(() => {
    if (initialCroppingPageId) {
      const targetPage = docPages.find((p) => p.id === initialCroppingPageId);
      if (targetPage) {
        handleStartEditing(targetPage);
        if (onClearInitialCropping) {
          setTimeout(() => {
            onClearInitialCropping();
          }, 0);
        }
      }
    }
  }, [initialCroppingPageId, pages]);

  useEffect(() => {
    setShowDeleteConfirmInLightbox(false);
    if (lightboxIndex === null) {
      setLightboxUrl('');
    } else {
      const page = docPages[lightboxIndex];
      if (page) {
        setLightboxUrl(pageUrls[page.id] || '');
      }
    }
  }, [lightboxIndex, pageUrls, docPages]);

  const handleMovePage = useCallback((index: number, direction: 'left' | 'right') => {
    const nextIndex = direction === 'left' ? index - 1 : index + 1;
    if (nextIndex < 0 || nextIndex >= docPages.length) return;

    const reorderedIds = [...docPages.map(p => p.id)];
    const temp = reorderedIds[index];
    reorderedIds[index] = reorderedIds[nextIndex];
    reorderedIds[nextIndex] = temp;

    onReorderPages(reorderedIds);
  }, [docPages, onReorderPages]);

  const handleCompilePDF = async (fileName: string, action?: 'download' | 'share', customOptions?: PDFExportOptions) => {
    setPdfProgress({ current: 0, total: docPages.length, building: true });
    try {
      const blobCache = blobCacheRef.current;
      await Promise.all(
        docPages.map(async (p) => {
          if (blobCache[p.originalImageId]) return blobCache[p.originalImageId];
          const blob = await getImageBlob(p.originalImageId);
          if (blob) blobCache[p.originalImageId] = blob;
          return blob;
        })
      );
      
      // Update export progress via requestAnimationFrame
      const progressCallback = (curr: number, tot: number) => {
        requestAnimationFrame(() => {
          setPdfProgress({ current: curr, total: tot, building: true });
        });
      };

      const finalOptions = customOptions || exportOptions;

      const pdfBlob = await exportDocumentToPDF(
        docPages,
        finalOptions,
        progressCallback
      );

      const normalizedName = `${fileName}.pdf`;
      await shareOrDownloadFile(pdfBlob, normalizedName, activeDocument.title, action === 'download');
      setShowExportModal(false);
    } catch (e) {
      alert('Could not compile PDF: ' + String(e));
    } finally {
      setPdfProgress({ current: 0, total: 0, building: false });
    }
  };

  return {
    docPages,
    showExportModal,
    setShowExportModal,
    exportOptions,
    setExportOptions,
    pdfProgress,
    lightboxIndex,
    setLightboxIndex,
    showDeleteConfirmInLightbox,
    setShowDeleteConfirmInLightbox,
    lightboxUrl,
    lightboxScale,
    setLightboxScale,
    lightboxPan,
    setLightboxPan,
    isPanDragging,
    setIsPanDragging,
    panDragStart,
    setPanDragStart,
    touchStartX,
    setTouchStartX,
    editingPage,
    editingBlob,
    loadingEditingBlob,
    detectedCorners,
    handleStartEditing,
    handleSaveEditedPage,
    handleSaveEditedPageAndNext,
    handleMovePage,
    handleCompilePDF,
    pageUrls,
    handleLightboxNext,
    handleLightboxPrev,
  };
}
