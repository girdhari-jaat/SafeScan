import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { unstable_batchedUpdates } from 'react-dom';
import { 
  initDB, 
  getOfflineDocuments, 
  saveOfflineDocuments, 
  getOfflinePages, 
  saveOfflinePages, 
  saveImageBlob,
  savePageMeta,
  batchSaveBlobs,
  deleteImageBlob,
  deleteImageCacheBlob,
  deleteDisplayCacheBlob
} from './utils/db';
import { 
  ScanDocument, 
  ScanPage, 
  PageCorners
} from './types';
import { exportDocumentToPDF, shareOrDownloadFile } from './utils/pdfExport';
import { useSharedSettings } from './lib/useSharedSettings';
import { generatePageHash } from './utils/imageWorkerClient';

export function useAppHook() {
  const [currentView, setCurrentView] = useState<'home' | 'camera' | 'card' | 'editor' | 'pdf' | 'library' | 'settings'>('library');
  const [activeDocId, setActiveDocId] = useState<string | null>(null);
  const [newlyCapturedPageId, setNewlyCapturedPageId] = useState<string | null>(null);
  const [capturedBatchPageIds, setCapturedBatchPageIds] = useState<string[]>([]);
  
  const [documents, setDocuments] = useState<ScanDocument[]>([]);
  const [pages, setPages] = useState<ScanPage[]>([]);
  const [dbReady, setDbReady] = useState(false);
  const [errorNotice, setErrorNotice] = useState('');

  const { settings, updateSetting } = useSharedSettings();

  const scannerSubTab = settings.scannerSubTab;
  const setScannerSubTab = (tab: 'paper' | 'idcard' | 'grid') => {
    updateSetting('scannerSubTab', tab);
    if (tab === 'idcard' || tab === 'grid') {
      updateSetting('batchScan', true);
    }
  };

  useEffect(() => {
    if ((scannerSubTab === 'idcard' || scannerSubTab === 'grid') && !settings.batchScan) {
      updateSetting('batchScan', true);
    }
  }, [scannerSubTab, settings.batchScan, updateSetting]);

  const [deferredPrompt, setDeferredPrompt] = useState<any>(null);
  const [isInstallModalOpen, setIsInstallModalOpen] = useState(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  
  const [exportModal, setExportModal] = useState<{ isOpen: boolean; doc: ScanDocument | null }>({ isOpen: false, doc: null });
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const pdfReaderRef = useRef<{ triggerBrowse: () => void; triggerReset?: () => void } | null>(null);

  
  useEffect(() => {
    setHasUnsavedChanges(pages.length > 0);
  }, [pages]);

  useEffect(() => {
    if (currentView === 'home' || currentView === 'camera' || currentView === 'card') {
      setCapturedBatchPageIds([]);
    }
  }, [currentView]);

  const cleanupEmptyDocuments = () => {
    setDocuments(prevDocs => {
      const filtered = prevDocs.filter(d => d.pageIds && d.pageIds.length > 0);
      saveOfflineDocuments(filtered);
      return filtered;
    });
  };

  useEffect(() => {
    initDB()
      .then(() => {
        setDbReady(true);
        let fetchedDocs = getOfflineDocuments();
        let fetchedPages = getOfflinePages();

        fetchedDocs = fetchedDocs.map(d => {
          const loadedPageIds = (d.pageIds || []).filter(id => fetchedPages.some(p => p.id === id));
          return { ...d, pageIds: loadedPageIds };
        }).filter(d => d.pageIds.length > 0);
        saveOfflineDocuments(fetchedDocs);

        if (!settings.hasSeededTutorial) {
          updateSetting('hasSeededTutorial', true);
          updateSetting('defaultScanFilter', 'original');
        }

        setDocuments(fetchedDocs);
        setPages(fetchedPages);
      })
      .catch(() => {
        setErrorNotice('Offline storage initialization failed. Please enable browser cookie storage.');
      });
  }, [settings.hasSeededTutorial, updateSetting]);

  useEffect(() => {
    const handleBeforeInstall = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e);
    };
    window.addEventListener('beforeinstallprompt', handleBeforeInstall);
    return () => {
      window.removeEventListener('beforeinstallprompt', handleBeforeInstall);
    };
  }, []);

  // Handle PWA Homescreen Shortcuts deep linking
  useEffect(() => {
    if (!dbReady) return; // Wait until offline state/database is fully ready
    try {
      const params = new URLSearchParams(window.location.search);
      const tabParam = params.get('tab');
      if (tabParam === 'paper' || tabParam === 'idcard' || tabParam === 'grid') {
        setCurrentView('home');
        setScannerSubTab(tabParam);
        // Clean URL query params elegant tracking cleanup so it doesn't trigger on manual reloads
        const newUrl = window.location.pathname + (window.location.search.includes('source=pwa') ? '?source=pwa' : '');
        window.history.replaceState({}, document.title, newUrl);
        triggerToast(`Launched via shortcut: ${tabParam === 'idcard' ? 'ID Card' : 'Document Scanner'}`);
      }
    } catch (err) {
      console.error("PWA shortcut navigation routing failed:", err);
    }
  }, [dbReady]);


  useEffect(() => {
    // Prevent browser native pinch-to-zoom on the viewport/document,
    // while keeping pointer-events pinch zooming working on programmatic nodes (the image itself).
    const handleTouchMove = (e: TouchEvent) => {
      if (e.touches.length > 1) {
        e.preventDefault();
      }
    };

    const handleGestureStart = (e: Event) => {
      e.preventDefault();
    };

    const handleGestureChange = (e: Event) => {
      e.preventDefault();
    };

    document.addEventListener('touchmove', handleTouchMove, { passive: false });
    document.addEventListener('gesturestart', handleGestureStart, { passive: false });
    document.addEventListener('gesturechange', handleGestureChange, { passive: false });

    return () => {
      document.removeEventListener('touchmove', handleTouchMove);
      document.removeEventListener('gesturestart', handleGestureStart);
      document.removeEventListener('gesturechange', handleGestureChange);
    };
  }, []);

  const triggerToast = useCallback((msg: string) => {
    setToastMessage(msg);
    setTimeout(() => {
      setToastMessage(null);
    }, 3200);
  }, []);

  const handleSetDocuments = useCallback((val: ScanDocument[] | ((prev: ScanDocument[]) => ScanDocument[])) => {
    setDocuments((prev) => {
      const next = typeof val === 'function' ? val(prev) : val;
      saveOfflineDocuments(next);
      return next;
    });
  }, []);

  const handleSetPages = useCallback((updatedPages: ScanPage[]) => {
    setPages(updatedPages);
    saveOfflinePages(updatedPages);
  }, []);

  const handleCreateDocument = useCallback((title?: string) => {
    const defaultTitle = title || `Document ${new Date().toLocaleDateString()} ${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })}`;
    const newDoc: ScanDocument = {
      id: `doc_${crypto.randomUUID()}`,
      title: defaultTitle,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      pageIds: [],
      tags: [],
    };

    setDocuments((currentDocs) => {
      const updated = [newDoc, ...currentDocs];
      saveOfflineDocuments(updated);
      return updated;
    });
    setActiveDocId(newDoc.id);
    setCurrentView('editor');
    triggerToast('New document folder created');
  }, [triggerToast]);

  const handleDeleteDocuments = useCallback(async (docIds: string[]) => {
    // Perform cleanup for all documents to be deleted
    const pagesToDelete = pages.filter((p) => docIds.includes(p.docId));

    const filteredPages = pages.filter((p) => !docIds.includes(p.docId));
    const filteredDocs = documents.filter((d) => !docIds.includes(d.id));

    handleSetPages(filteredPages);
    handleSetDocuments(filteredDocs);

    if (docIds.includes(activeDocId || '')) {
      setActiveDocId(null);
      setCurrentView('home');
    }
    triggerToast('Documents deleted permanently');

    Promise.resolve().then(async () => {
      for (const page of pagesToDelete) {
        try {
          if (page.originalImageId) {
            await deleteImageBlob(page.originalImageId);
          }
        } catch (e) {
          // ignore
        }
        try {
          if (page.processedImageId) {
            await deleteImageBlob(page.processedImageId);
          }
        } catch (e) {
          // ignore
        }
        try {
          const hash = generatePageHash(page);
          await deleteImageCacheBlob(hash);
          await deleteDisplayCacheBlob(hash);
        } catch (e) {
           // ignore
        }
      }
    });
  }, [documents, pages, activeDocId, triggerToast, handleSetPages, handleSetDocuments]);

  const handleDeleteDocument = useCallback(async (docId: string) => {
    return handleDeleteDocuments([docId]);
  }, [handleDeleteDocuments]);

  const handleRenameDocument = useCallback((docId: string, newTitle: string) => {
    handleSetDocuments((prev) => 
      prev.map((doc) => {
        if (doc.id === docId) {
          return { ...doc, title: newTitle };
        }
        return doc;
      })
    );
    triggerToast('Document title updated');
    // Actual DB update happens if needed, but local state update is prioritized for speed
  }, [handleSetDocuments, triggerToast]);

  const handleUpdateDocumentTags = useCallback((docId: string, tags: string[]) => {
    const updated = documents.map((doc) => {
      if (doc.id === docId) {
        return { ...doc, tags, updatedAt: Date.now() };
      }
      return doc;
    });
    handleSetDocuments(updated);
  }, [documents, handleSetDocuments]);

  const handleDeletePage = useCallback(async (pageId: string) => {
    const pageToDelete = pages.find((p) => p.id === pageId);
    if (!pageToDelete) return;

    const filteredPages = pages.filter((p) => p.id !== pageId);
    handleSetPages(filteredPages);

    const updatedDocs = documents.map((doc) => {
      if (doc.id === pageToDelete.docId) {
        return {
          ...doc,
          pageIds: (doc.pageIds || []).filter((id) => id !== pageId),
          updatedAt: Date.now(),
        };
      }
      return doc;
    });
    handleSetDocuments(updatedDocs);
    
    const targetDoc = updatedDocs.find(d => d.id === pageToDelete.docId);
    if (targetDoc && (!targetDoc.pageIds || targetDoc.pageIds.filter(id => id !== pageId).length === 0)) {
        const filteredDocs = updatedDocs.filter((d) => d.id !== targetDoc.id);
        handleSetDocuments(filteredDocs);
        if (activeDocId === targetDoc.id) {
          setActiveDocId(null);
          setCurrentView('home');
        }
        triggerToast('Document deleted permanently');
    } else {
        triggerToast('Page deleted');
    }

    Promise.resolve().then(async () => {
      try {
        if (pageToDelete.originalImageId) {
          await deleteImageBlob(pageToDelete.originalImageId);
        }
      } catch (e) {
        // ignore
      }
      try {
        if (pageToDelete.processedImageId) {
          await deleteImageBlob(pageToDelete.processedImageId);
        }
      } catch (e) {
        // ignore
      }
      try {
        const hash = generatePageHash(pageToDelete);
        await deleteImageCacheBlob(hash);
        await deleteDisplayCacheBlob(hash);
      } catch (e) {
        // ignore
      }
    });
  }, [pages, documents, activeDocId, triggerToast, handleSetPages, handleSetDocuments]);

  const handleUpdatePage = useCallback((updatedPage: ScanPage) => {
    const revised = pages.map((p) => (p.id === updatedPage.id ? updatedPage : p));
    handleSetPages(revised);

    const updatedDocs = documents.map((doc) => {
      if (doc.id === updatedPage.docId) {
        return { ...doc, updatedAt: Date.now() };
      }
      return doc;
    });
    handleSetDocuments(updatedDocs);
  }, [pages, documents, handleSetPages, handleSetDocuments]);

  const handleReorderPages = useCallback((reorderedPageIds: string[]) => {
    if (!activeDocId) return;

    const nonDocPages = pages.filter((p) => p.docId !== activeDocId);
    const sortedDocPages: ScanPage[] = [];

    reorderedPageIds.forEach((id) => {
      const match = pages.find((p) => p.id === id);
      if (match) sortedDocPages.push(match);
    });

    handleSetPages([...nonDocPages, ...sortedDocPages]);

    const updatedDocs = documents.map((doc) => {
      if (doc.id === activeDocId) {
        return { ...doc, pageIds: reorderedPageIds, updatedAt: Date.now() };
      }
      return doc;
    });
    handleSetDocuments(updatedDocs);
    triggerToast('Page sequence reordered');
  }, [activeDocId, pages, documents, handleSetPages, handleSetDocuments, triggerToast]);

  const handleAddRawImagePage = useCallback(async (blob: Blob, isBatch?: boolean, _prependedCorners?: PageCorners, targetDocId?: string, forceCrop?: boolean, customTag?: string, needsDetection?: boolean) => {
    const docIdToUse = targetDocId || activeDocId;
    if (!docIdToUse) return;

    try {
      const pageId = `page_${crypto.randomUUID()}`;
      const originalImageId = `raw_${pageId}`;
      const processedImageId = `proc_${pageId}`;

      // Await saving to IndexedDB to prevent race condition & ensure accuracy for low-memory 2GB platforms
      await saveImageBlob(originalImageId, blob);
      
      /* Urdu: original safe, meta alag */
      const initialCorners: PageCorners = _prependedCorners || {
        tl: { x: 0, y: 0 },
        tr: { x: 100, y: 0 },
        br: { x: 100, y: 100 },
        bl: { x: 0, y: 100 }
      };

      await savePageMeta(pageId, {
        cropPoints: initialCorners,
        rotate: 0,
        filter: 'original',
        adjustments: { b: 0, c: 0, s: 0 }
      });
      // Removed saveImageBlob for processedImageId to implement non-destructive save

      const newPage: ScanPage = {
        id: pageId,
        docId: docIdToUse,
        originalImageId,
        processedImageId,
        corners: initialCorners,
        rotation: 0,
        filter: 'original',
        adjustments: { brightness: 0, contrast: 0, saturation: 0 },
        addedAt: Date.now(),
      };

      const docPagesCount = pages.filter(p => p.docId === docIdToUse).length + 1;
      
      unstable_batchedUpdates(() => {
        setPages((currentPages) => {
          const updated = [...currentPages, newPage];
          saveOfflinePages(updated);
          return updated;
        });

        setDocuments((currentDocs) => {
          const updated = currentDocs.map((doc) => {
            if (doc.id === docIdToUse) {
              const currentTags = doc.tags || [];
              const tagToApply = customTag || 'Scan';
              const nextTags = currentTags.includes(tagToApply) ? currentTags : [...currentTags.filter(t => t !== 'Scanned' && t !== 'Scan'), tagToApply];
              const updatedPageIds = [...(doc.pageIds || []), pageId];
              return {
                ...doc,
                pageIds: updatedPageIds,
                tags: nextTags,
                updatedAt: Date.now(),
              };
            }
            return doc;
          });
          saveOfflineDocuments(updated);
          return updated;
        });

        if (isBatch) {
          triggerToast(`Page #${docPagesCount} captured!`);
          setCapturedBatchPageIds(prev => [...prev, pageId]);
        } else {
          // If not batch scanning, we immediately prepare the editor to open the crop/adjust modal for this specific page
          // However, if auto-crop already optimized the page (forceCrop === false), we don't necessarily need to force the UI modal
          if (forceCrop !== false) {
            setNewlyCapturedPageId(pageId);
          } else {
            setNewlyCapturedPageId(null);
          }
          setCurrentView('editor');
          triggerToast('Page captured!');
        }
      });
      
      // Background corner detection off-thread
      if (needsDetection) {
        (async () => {
          try {
            const { detectCornersOffThread } = await import('./utils/imageWorkerClient');
            const tempBmp = await createImageBitmap(blob);
            const detectOutput = await detectCornersOffThread(tempBmp, 'paper', true); // Force auto detect without API
            if (detectOutput && detectOutput.corners) {
              const w = detectOutput.originalWidth;
              const h = detectOutput.originalHeight;
              const newCropPoints = {
                tl: { x: (detectOutput.corners[0].x / w) * 100, y: (detectOutput.corners[0].y / h) * 100 },
                tr: { x: (detectOutput.corners[1].x / w) * 100, y: (detectOutput.corners[1].y / h) * 100 },
                br: { x: (detectOutput.corners[2].x / w) * 100, y: (detectOutput.corners[2].y / h) * 100 },
                bl: { x: (detectOutput.corners[3].x / w) * 100, y: (detectOutput.corners[3].y / h) * 100 },
              };
              
              // 1. Update IndexedDB metadata silently
              await savePageMeta(pageId, {
                cropPoints: newCropPoints,
                rotate: 0,
                filter: 'original',
                adjustments: { b: 0, c: 0, s: 0 }
              });

              // 2. Update global state seamlessly
              setPages(currentPages => {
                const updated = currentPages.map(p => 
                  p.id === pageId ? { ...p, corners: newCropPoints } : p
                );
                saveOfflinePages(updated);
                return updated;
              });
            }
          } catch (e) {
            console.warn("Background corner detection failed:", e);
          }
        })();
      }

      return pageId;
    } catch (e) {
      alert('Fail to save captured page: ' + String(e));
      if (!isBatch) {
        setCurrentView('editor');
      }
    }
  }, [activeDocId, pages, triggerToast]);

  const handleRetakePageInApp = useCallback(async (pageId: string, blob: Blob) => {
    try {
      const pageToUpdate = pages.find(p => p.id === pageId);
      if (!pageToUpdate) return;

      // Await saving to IndexedDB to prevent race condition & ensure accuracy for low-memory 2GB platforms
      await saveImageBlob(pageToUpdate.originalImageId, blob);
      // Non-destructive save: removed processedImageId saving

      const updatedPage: ScanPage = {
        ...pageToUpdate,
        corners: {
          tl: { x: 0, y: 0 },
          tr: { x: 100, y: 0 },
          br: { x: 100, y: 100 },
          bl: { x: 0, y: 100 }
        },
        rotation: 0,
        filter: 'original',
        adjustments: { brightness: 0, contrast: 0, saturation: 0 }
      };

      handleUpdatePage(updatedPage);
      triggerToast('Page re-taken successfully!');
    } catch (err) {
      alert('Failed to re-take page: ' + String(err));
    }
  }, [pages, handleUpdatePage, triggerToast]);

  const handleAndroidBackButton = useCallback(() => {
    if (currentView === 'camera') {
      setCurrentView('editor');
    } else if (currentView === 'editor') {
      setActiveDocId(null);
      setCurrentView('library');
      cleanupEmptyDocuments();
    } else if (currentView === 'pdf') {
      setCurrentView('library');
      cleanupEmptyDocuments();
    } else if (currentView === 'settings') {
      setCurrentView('library');
    } else if (currentView === 'library') {
      // Handled at App level to trigger exit confirmation
    } else {
      // Handled at App level to trigger exit confirmation
    }
  }, [currentView, cleanupEmptyDocuments]);

  const handlePDFPageImport = useCallback(async (blobs: Blob[]) => {
    let docIdToUse = activeDocId;
    if (!docIdToUse) {
      const defaultTitle = `PDF Scan ${new Date().toLocaleDateString()} ${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })}`;
      const newDocId = `doc_${crypto.randomUUID()}`;
      const newDoc: ScanDocument = {
        id: newDocId,
        title: defaultTitle,
        createdAt: Date.now(),
        updatedAt: Date.now(),
        pageIds: [],
        tags: ['Pdf'],
      };
      docIdToUse = newDocId;
      setDocuments(prev => [newDoc, ...prev]);
      saveOfflineDocuments([newDoc, ...documents]);
      setActiveDocId(newDocId);
    }

    setCurrentView('editor');
    triggerToast(`Importing ${blobs.length} PDF page(s)...`);
    
    setTimeout(async () => {
        const newPages: ScanPage[] = [];
        const batchItems: { id: string; blob: Blob }[] = [];
        const newPageIds: string[] = [];

        for (let i = 0; i < blobs.length; i++) {
            const pageId = `page_${crypto.randomUUID()}`;
            const originalImageId = `raw_${pageId}`;
            const processedImageId = `proc_${pageId}`;
            
            batchItems.push({ id: originalImageId, blob: blobs[i] });
            // Non-destructive save: no longer saving duplicate blob

            const newPage: ScanPage = {
                id: pageId,
                docId: docIdToUse!,
                originalImageId,
                processedImageId,
                corners: { tl: {x:0,y:0}, tr: {x:100,y:0}, br: {x:100,y:100}, bl: {x:0,y:100} },
                rotation: 0,
                filter: 'original',
                adjustments: { brightness: 0, contrast: 0, saturation: 0 },
                addedAt: Date.now() + i,
            };
            newPages.push(newPage);
            newPageIds.push(pageId);
        }

        await batchSaveBlobs(batchItems);

        unstable_batchedUpdates(() => {
          handleSetPages([...pages, ...newPages]);
          setDocuments(prev => {
            const updated = prev.map(d => {
              if (d.id === docIdToUse) {
                return {
                  ...d,
                  pageIds: [...(d.pageIds || []), ...newPageIds],
                  updatedAt: Date.now()
                };
              }
              return d;
            });
            saveOfflineDocuments(updated);
            return updated;
          });
          setNewlyCapturedPageId(newPages[0]?.id || null);
        });
    }, 250);
  }, [activeDocId, documents, pages, handleSetPages, triggerToast]);

  const handleTriggerFileInput = useCallback(() => {
    if (fileInputRef.current) {
      fileInputRef.current.click();
    }
  }, []);

  const handleFileChange = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;

    const imageFiles: File[] = [];
    for (let i = 0; i < files.length; i++) {
      const f = files.item(i);
      if (f && f.type.startsWith('image/')) {
        imageFiles.push(f);
      }
    }

    if (imageFiles.length === 0) {
      triggerToast('No valid image files selected');
      return;
    }

    let docIdToUse = activeDocId;
    let documentsList = [...documents];

    if (!docIdToUse) {
      const defaultTitle = `Import ${new Date().toLocaleDateString()} ${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })}`;
      const newDocId = `doc_${crypto.randomUUID()}`;
      const newDoc: ScanDocument = {
        id: newDocId,
        title: defaultTitle,
        createdAt: Date.now(),
        updatedAt: Date.now(),
        pageIds: [],
        tags: ['Image'],
      };
      docIdToUse = newDocId;
      documentsList = [newDoc, ...documentsList];
      handleSetDocuments(documentsList);
      setActiveDocId(newDocId);
    }

    setCurrentView('editor');
    triggerToast(`Importing ${imageFiles.length} page(s)...`);

    const newPages: ScanPage[] = [];
    const batchItems: { id: string; blob: Blob }[] = [];
    const newPageIds: string[] = [];

    for (let i = 0; i < imageFiles.length; i++) {
      const file = imageFiles[i];
      const pageId = `page_${crypto.randomUUID()}`;
      const originalImageId = `raw_${pageId}`;
      const processedImageId = `proc_${pageId}`;

      batchItems.push({ id: originalImageId, blob: file });
      // Non-destructive save

      const newPage: ScanPage = {
        id: pageId,
        docId: docIdToUse!,
        originalImageId,
        processedImageId,
        corners: {
          tl: { x: 0, y: 0 },
          tr: { x: 100, y: 0 },
          br: { x: 100, y: 100 },
          bl: { x: 0, y: 100 }
        },
        rotation: 0,
        filter: 'original',
        adjustments: { brightness: 0, contrast: 0, saturation: 0 },
        addedAt: Date.now() + i,
      };

      newPages.push(newPage);
      newPageIds.push(pageId);
    }

    await batchSaveBlobs(batchItems);

    unstable_batchedUpdates(() => {
      handleSetPages([...pages, ...newPages]);
      const finalDocs = documentsList.map((doc) => {
        if (doc.id === docIdToUse) {
          const currentTags = doc.tags || [];
          const nextTags = currentTags.includes('Image') ? currentTags : [...currentTags.filter(t => t !== 'imported' && t !== 'Scan'), 'Image'];
          return {
            ...doc,
            pageIds: [...(doc.pageIds || []), ...newPageIds],
            tags: nextTags,
            updatedAt: Date.now(),
          };
        }
        return doc;
      });

      handleSetDocuments(finalDocs);
    });

    triggerToast(`Successfully imported ${newPageIds.length} page(s)`);

    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  }, [activeDocId, documents, pages, handleSetPages, handleSetDocuments, triggerToast]);

  const handlePDFExportRequest = useCallback((doc: ScanDocument) => {
    setExportModal({ isOpen: true, doc });
  }, []);

  const handleExportConfirmed = useCallback(async (options: { 
    pageSize: 'a4' | 'letter' | 'fit'; 
    orientation: 'portrait' | 'landscape' | 'auto'; 
    quality: number; 
    password?: string; 
    action?: 'download' | 'share';
    title?: string;
  }) => {
    const doc = exportModal.doc;
    if (!doc) return;
    
    setExportModal({ isOpen: false, doc: null });

    const docPages = pages.filter((p) => p.docId === doc.id);
    if (docPages.length === 0) {
      triggerToast('Cannot compile: Add pages first');
      return;
    }
    
    triggerToast('Processing high-res document output PDF...');
    try {
      const pdfBlob = await exportDocumentToPDF(
        docPages,
        { ...options, documentTitle: options.title || doc.title }
      );

      const fileName = `${(options.title || doc.title).trim() || 'Scanned_Doc'}.pdf`;
      await shareOrDownloadFile(pdfBlob, fileName, options.title || doc.title, options.action === 'download');
      triggerToast('PDF saved or shared successfully');
    } catch (e) {
      alert('Failed to construct PDF files: ' + String(e));
    }
  }, [exportModal, pages, triggerToast]);

  const activeDoc = useMemo(() => {
    return documents.find((doc) => doc.id === activeDocId);
  }, [documents, activeDocId]);

  return {
    currentView,
    setCurrentView,
    scannerSubTab,
    setScannerSubTab,
    activeDocId,
    setActiveDocId,
    newlyCapturedPageId,
    setNewlyCapturedPageId,
    capturedBatchPageIds,
    setCapturedBatchPageIds,
    documents,
    setDocuments,
    pages,
    setPages,
    handleSetPages,
    dbReady,
    errorNotice,
    deferredPrompt,
    setDeferredPrompt,
    isInstallModalOpen,
    setIsInstallModalOpen,
    toastMessage,
    exportModal,
    setExportModal,
    fileInputRef,
    pdfReaderRef,
    cleanupEmptyDocuments,
    handleCreateDocument,
    handleDeleteDocument,
    handleDeleteDocuments,
    handleRenameDocument,
    handleUpdateDocumentTags,
    handleDeletePage,
    handleUpdatePage,
    handleReorderPages,
    handleAddRawImagePage,
    handleRetakePageInApp,
    handleAndroidBackButton,
    handlePDFPageImport,
    handleTriggerFileInput,
    handleFileChange,
    handlePDFExportRequest,
    handleExportConfirmed,
    activeDoc,
    triggerToast,
    hasUnsavedChanges
  };
}
