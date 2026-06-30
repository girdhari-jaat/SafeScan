import React, { useState, useEffect, useCallback } from 'react';
import { ScanDocument, ScanPage, ScanFilterType } from '../types';
import { getImageBlob, savePageMeta, getDisplayCacheBlob, saveDisplayCacheBlob } from '../utils/db';
import { generatePageHash, processFinalImageOffThread } from '../utils/imageWorkerClient';
import { saveOrShareBlob } from '../utils/pdfExport';
import { ArrowLeft, FileDown, Plus, Trash2, RefreshCw,
  Camera, Upload, ChevronLeft, ChevronRight, X, SlidersHorizontal,
  Sparkles, Languages, Cpu, Layers, AlertCircle, Check, Copy, ZapOff,
  ArrowUpDown
} from 'lucide-react';
import Crop from './Crop';
import { ExportModal } from './ExportModal';
import { getCSSFilterString } from '../utils/imageProcess';
import { useEditorHook } from './EditorHook';
import { ZoomableImage } from './ZoomableImage';
import { globalRenderCountRef, addLog } from '../utils/renderStats';
import { useSharedSettings } from '../lib/useSharedSettings';
import { useTranslation, Language } from '../lib/i18n';

interface EditorProps {
  document: ScanDocument;
  pages: ScanPage[];
  onBack: () => void;
  onAddPage: () => void; // Launches capture flow
  onImportPage?: () => void; // Launches local image import flow
  onUpdateDocumentTags: (docId: string, tags: string[]) => void;
  onDeletePage: (pageId: string) => void;
  onUpdatePage: (updatedPage: ScanPage) => void;
  onReorderPages: (pageIds: string[]) => void;
  initialCroppingPageId?: string;
  onClearInitialCropping?: () => void;
  onRenameDocument?: (docId: string, newTitle: string) => void;
}

const DeleteConfirmationModal = React.memo(({
  isOpen,
  onClose,
  onDelete,
  title,
  message,
}: {
  isOpen: boolean;
  onClose: () => void;
  onDelete: () => void;
  title: string;
  message: string;
}) => {
  if (!isOpen) return null;
  return (
    <div 
      className="fixed inset-0 bg-[var(--bg-overlay)] backdrop-blur-md z-[200] flex flex-col items-center justify-center p-6 text-center text-[var(--text-primary)] animate-in fade-in duration-200"
      onClick={(e) => {
        e.stopPropagation();
        onClose();
      }}
    >
      <div 
        className="w-full max-w-sm bg-[var(--bg-card)] border border-[var(--border-color)] p-8 rounded-[38px] flex flex-col gap-5 text-center text-[var(--text-primary)] shadow-2xl animate-in zoom-in-95 duration-150"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="w-16 h-16 bg-rose-500/10 border border-rose-500/20 text-rose-500 p-3 rounded-full mx-auto flex items-center justify-center mb-1">
          <Trash2 className="w-7 h-7 text-rose-500" />
        </div>
        <div className="space-y-1.5">
          <h4 className="text-[var(--text-primary)] font-black text-base uppercase tracking-tight font-sans">{title}</h4>
          <p className="text-[var(--text-secondary)] text-[11px] font-medium font-sans leading-relaxed">
            {message}
          </p>
        </div>
        <div className="flex flex-col gap-2 mt-2">
          <button
            type="button"
            onClick={() => {
              onDelete();
              onClose();
            }}
            className="w-full bg-rose-600 hover:bg-rose-500 text-white font-black py-3.5 rounded-2xl text-xs uppercase tracking-widest cursor-pointer active:scale-95 transition-all text-center shadow-lg shadow-rose-600/20"
          >
            Confirm Delete
          </button>
          <button
            type="button"
            onClick={onClose}
            className="w-full bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] font-black py-3.5 rounded-2xl text-xs uppercase tracking-widest cursor-pointer active:scale-95 transition-all text-center"
          >
            Dismiss
          </button>
        </div>
      </div>
    </div>
  );
});


const QUICK_FILTERS = [
  { id: 'original', name: 'Original' },
  { id: 'pro-scan', name: 'Pro Scan' },
  { id: 'auto-enhance', name: 'Enhance' },
  { id: 'magic', name: 'Magic' },
  { id: 'bw', name: 'B&W' },
  { id: 'grayscale', name: 'Grayscale' },
  { id: 'document', name: 'Doc' },
  { id: 'cnic', name: 'CNIC' },
] as const;

const SingleVerticalPageCard = React.memo(function SingleVerticalPageCard({
  page,
  index,
  total,
  onDelete,
  onImageClick,
  onMoveLeft,
  onMoveRight,
  isFirst,
  isLast,
  onEdit,
  imgUrl,
  onUpdateFilter,
  onTriggerDocAI,
  onReorderClick,
}: {
  page: ScanPage;
  index: number;
  total: number;
  onDelete: () => void;
  onImageClick: () => void;
  onMoveLeft: () => void;
  onMoveRight: () => void;
  isFirst: boolean;
  isLast: boolean;
  onEdit: () => void | Promise<void>;
  imgUrl: string;
  onUpdateFilter: (pageId: string, newFilter: any, applyToAll?: boolean) => void | Promise<void>;
  onTriggerDocAI: (page: ScanPage, index: number, imgUrl: string) => void;
  onReorderClick?: () => void;
}) {
  const [isSaving, setIsSaving] = useState(false);
  const [applyToAll, setApplyToAll] = useState(false);

  const handleSaveToGallery = useCallback(async () => {
    if (isSaving) return;
    setIsSaving(true);
    try {
      const hash = generatePageHash(page);
      const fileName = `PageScanned_${index + 1}.jpg`;
      
      // Step 1: Look up display-cache (Policy compliant)
      const cachedBlob = await getDisplayCacheBlob(hash);
      if (cachedBlob) {
        await saveOrShareBlob(cachedBlob, fileName, `Page Scanned ${index + 1}`);
        return;
      }

      // Step 2: Fallback to processing off-thread securely with active workers
      const rawBlob = await getImageBlob(page.originalImageId);
      if (rawBlob) {
        const bitmap = await createImageBitmap(rawBlob);
        const processedBlob = await processFinalImageOffThread(
          bitmap,
          page.corners,
          page.rotation,
          page.filter,
          page.adjustments,
          'export'
        );
        
        if (processedBlob) {
          // Persist to display-cache for future instant action
          await saveDisplayCacheBlob(hash, processedBlob);
          await saveOrShareBlob(processedBlob, fileName, `Page Scanned ${index + 1}`);
        }
      } else if (imgUrl) {
        // Safe ultimate fallback using memory Object URL if original is somehow missing
        const response = await fetch(imgUrl);
        const fallbackBlob = await response.blob();
        await saveOrShareBlob(fallbackBlob, fileName, `Page Scanned ${index + 1}`);
      }
    } catch (error) {
      console.error('Failed to save page image:', error);
    } finally {
      setIsSaving(false);
    }
  }, [page, index, imgUrl, isSaving]);

  return (
    <div className="relative w-full rounded-[2rem] overflow-hidden border border-[var(--border-color)] bg-[var(--bg-card)] shadow-lg animate-in fade-in duration-300 flex items-center justify-center min-h-[180px]" id={`vertical-page-card-${page.id}`}>
      {!imgUrl ? (
        <div className="flex flex-col items-center gap-2 py-12 select-none w-full">
          <RefreshCw className="w-6 h-6 animate-spin text-[var(--primary)]" />
          <span className="text-[var(--text-secondary)] text-xs font-mono">
            Loading original image...
          </span>
        </div>
      ) : (
        <div className="flex flex-col w-full h-full">
          {/* Top Row Header Bar */}
          <div className="flex justify-between items-center bg-[var(--bg-primary)]/80 border-b border-[var(--border-color)] py-2.5 px-4 select-none">
            {/* Top Left: Clean borderless Page index indicator */}
            <div className="text-[var(--text-primary)] text-[11px] font-black tracking-widest uppercase font-mono flex items-center gap-1.5">
              <span>Page {index + 1} / {total}</span>
              {total > 1 && onReorderClick && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onReorderClick();
                  }}
                  className="p-1 hover:bg-[var(--bg-card)] rounded text-[var(--primary)] hover:text-[var(--primary-hover)] transition-all ml-0.5"
                  title="Reorder Pages"
                >
                  <ArrowUpDown className="w-3.5 h-3.5" />
                </button>
              )}
            </div>

            {/* Top Right: Page Card Actions */}
            <div className="flex gap-2 items-center">
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onTriggerDocAI(page, index, imgUrl);
                }}
                className="p-1.5 rounded-xl border border-[var(--border-color)] bg-[var(--bg-card)] hover:bg-[var(--bg-primary)] text-amber-500 hover:text-amber-400 transition-all aspect-square flex items-center justify-center active:scale-90"
                title="SafeScan AI Smart Extract"
              >
                <Sparkles className="w-4 h-4 fill-amber-500/15" />
              </button>
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onEdit();
                }}
                className="p-1.5 rounded-xl border border-[var(--border-color)] bg-[var(--bg-card)] hover:bg-[var(--bg-primary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-all aspect-square flex items-center justify-center active:scale-95"
                title="Crop, rotate or apply filters"
              >
                <SlidersHorizontal className="w-4 h-4" />
              </button>
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  handleSaveToGallery();
                }}
                disabled={isSaving}
                className="p-1.5 rounded-xl border border-[var(--border-color)] bg-[var(--bg-card)] hover:bg-[var(--bg-primary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-all aspect-square flex items-center justify-center active:scale-95"
                title="Save Image copy"
              >
                {isSaving ? (
                  <RefreshCw className="w-4 h-4 animate-spin" />
                ) : (
                  <FileDown className="w-4 h-4" />
                )}
              </button>
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onDelete();
                }}
                className="p-1.5 rounded-xl border border-rose-500/20 bg-rose-500/5 hover:bg-rose-500/10 text-rose-500 transition-all aspect-square flex items-center justify-center active:scale-95"
                title="Delete page"
              >
                <Trash2 className="w-4 h-4" />
              </button>
            </div>
          </div>

          {/* Actual image displayed full bleed but styled without obstructing layout */}
          <div className="w-full bg-[var(--bg-card)] flex items-center justify-center p-3 select-none">
            <img
              src={imgUrl}
              onClick={onImageClick}
              className="max-h-[500px] w-auto h-auto cursor-zoom-in pointer-events-auto block rounded-2xl border border-[var(--border-color)]/60 shadow-inner"
              title="Click to view full screen"
              id={`page-preview-img-${page.id}`}
            />
          </div>

          {/* Bottom Filters Bar */}
          <div className="bg-[var(--bg-primary)]/80 border-t border-[var(--border-color)] px-4 py-2.5 flex items-center gap-2 select-none w-full">
            <label className="flex items-center gap-1.5 cursor-pointer flex-shrink-0 mr-1.5 select-none pointer-events-auto group">
              <input
                type="checkbox"
                checked={applyToAll}
                onChange={(e) => {
                  const checked = e.target.checked;
                  setApplyToAll(checked);
                  if (checked) {
                    onUpdateFilter(page.id, page.filter || 'original', true);
                  }
                }}
                className="sr-only"
              />
              <div className={`w-4 h-4 rounded-[6px] border flex items-center justify-center transition-all duration-200 ${
                applyToAll
                  ? 'bg-[var(--primary)] border-[var(--primary)] shadow-[0_0_6px_rgba(var(--primary-rgb),0.75)]'
                  : 'border-[var(--border-color)] bg-[var(--bg-card)] hover:border-[var(--text-secondary)]'
              }`}>
                {applyToAll && <Check className="w-2.5 h-2.5 text-white stroke-[4]" />}
              </div>
              <span className="text-[9px] font-extrabold text-[var(--text-secondary)] uppercase tracking-widest font-mono group-hover:text-[var(--text-primary)] transition-colors">
                Apply To All
              </span>
            </label>
            <div className="flex gap-1.5 items-center overflow-x-auto pb-0.5 scrollbar-none flex-nowrap w-full" id={`quick-filters-row-${page.id}`}>
              {QUICK_FILTERS.map((preset) => {
                const isActive = (page.filter || 'original') === preset.id;
                return (
                  <button
                    type="button"
                    key={preset.id}
                    onClick={() => onUpdateFilter(page.id, preset.id, applyToAll)}
                    className={`px-3 py-1.5 rounded-xl border text-[10px] font-black tracking-wider uppercase whitespace-nowrap cursor-pointer font-sans transition-all relative ${
                      isActive
                        ? 'bg-[var(--primary)] text-white border-[var(--primary)] shadow-sm'
                        : 'bg-[var(--bg-card)] text-[var(--text-secondary)] border-[var(--border-color)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-primary)]'
                    }`}
                    title={`Apply ${preset.name} filter`}
                  >
                    {preset.name}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}, (prev, next) => {
  return (
    prev.page.id === next.page.id &&
    prev.page.filter === next.page.filter &&
    prev.page.rotation === next.page.rotation &&
    prev.index === next.index &&
    prev.total === next.total &&
    prev.isFirst === next.isFirst &&
    prev.isLast === next.isLast &&
    prev.imgUrl === next.imgUrl &&
    JSON.stringify(prev.page.corners) === JSON.stringify(next.page.corners) &&
    JSON.stringify(prev.page.adjustments) === JSON.stringify(next.page.adjustments)
  );
});

function Editor({
  document: activeDocument,
  pages,
  onBack,
  onAddPage,
  onImportPage,
  onDeletePage,
  onUpdatePage,
  onReorderPages,
  initialCroppingPageId,
  onClearInitialCropping,
  onRenameDocument,
}: EditorProps) {
  const { settings } = useSharedSettings();
  const { t } = useTranslation(settings.uiLanguage as Language);
  const [networkOffline, setNetworkOffline] = useState(typeof navigator !== 'undefined' ? !navigator.onLine : false);

  useEffect(() => {
    const handleOnline = () => setNetworkOffline(false);
    const handleOffline = () => setNetworkOffline(true);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  const isOffline = networkOffline || settings.offlineMode;

  const [isEditingTitle, setIsEditingTitle] = useState(false);
  const [editTitleValue, setEditTitleValue] = useState(activeDocument.title);

  // States for custom mouse/touch drag and drop reordering in Editor
  const [isReorderOpen, setIsReorderOpen] = useState(false);
  const [draggedIndex, setDraggedIndex] = useState<number | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);

  // Gemini DocAI integration details
  const [aiPage, setAiPage] = useState<{ page: ScanPage; index: number; imgUrl: string } | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiResult, setAiResult] = useState<any>(null);
  const [aiError, setAiError] = useState<string | null>(null);
  const [targetLanguage, setTargetLanguage] = useState<string>('English');
  const [aiCopied, setAiCopied] = useState(false);

  const handleCopy = useCallback((text: string) => {
    navigator.clipboard?.writeText(text);
    setAiCopied(true);
    setTimeout(() => setAiCopied(false), 2000);
  }, []);

  const handleRunDocumentAI = useCallback(async () => {
    if (!aiPage) return;
    setAiLoading(true);
    setAiError(null);
    setAiResult(null);

    try {
      // 1. Fetch original image binary blob
      const response = await fetch(aiPage.imgUrl);
      if (!response.ok) throw new Error("Could not retrieve original image content from the URL cache.");
      const blob = await response.blob();

      // 2. Read as array buffer to extract base64 cleanly
      const base64Data = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onloadend = () => {
          if (typeof reader.result === 'string') {
            resolve(reader.result.split(',')[1]);
          } else {
            reject(new Error("Failed to read image as Base-64 encoded string format."));
          }
        };
        reader.onerror = reject;
        reader.readAsDataURL(blob);
      });

      // 3. Synchronize with our secure offline-ready Express server API
      const apiResponse = await fetch('/api/gemini/analyze', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          base64Data,
          mimeType: blob.type || 'image/jpeg',
          documentTitle: activeDocument.title,
          targetLanguage: targetLanguage,
          appName: settings.customAppName || "SafeScan"
        }),
      });

      const parsed = await apiResponse.json();
      if (!apiResponse.ok || !parsed.success) {
        throw new Error(parsed.error || "Server-side analysis failed. Check server logs or configure GEMINI_API_KEY.");
      }

      setAiResult(parsed.data);
    } catch (err: any) {
      console.error("DocAI execution failed:", err);
      setAiError(err.message || "An unexpected error occurred during analysis.");
    } finally {
      setAiLoading(false);
    }
  }, [aiPage, activeDocument.title, targetLanguage, settings.customAppName]);

  const handleFinishRename = useCallback(() => {
    const trimmed = editTitleValue.trim();
    if (trimmed && trimmed !== activeDocument.title) {
      if (onRenameDocument) {
        onRenameDocument(activeDocument.id, trimmed);
      }
    }
    setIsEditingTitle(false);
  }, [editTitleValue, activeDocument.title, onRenameDocument, activeDocument.id]);

  const [deleteConfirmation, setDeleteConfirmation] = useState<{
    isOpen: boolean;
    pageId: string | null;
    title: string;
    message: string;
    onConfirm: () => void;
  }>({ isOpen: false, pageId: null, title: '', message: '', onConfirm: () => {} });

  const renderCountRef = React.useRef(globalRenderCountRef);
  renderCountRef.current.current['Editor'] = (renderCountRef.current.current['Editor'] || 0) + 1;
  // console.log(`render Editor: ${renderCountRef.current.current['Editor']}x`);
  const {
    docPages,
    showExportModal,
    setShowExportModal,
    exportOptions,
    lightboxIndex,
    setLightboxIndex,
    lightboxUrl,
    setLightboxScale,
    setLightboxPan,
    editingPage,
    editingBlob,
    loadingEditingBlob,
    handleStartEditing,
    handleSaveEditedPage,
    handleSaveEditedPageAndNext,
    handleMovePage,
    handleCompilePDF,
    pageUrls,
    handleLightboxNext,
    handleLightboxPrev,
  } = useEditorHook({
    activeDocument,
    pages,
    onBack,
    onUpdatePage,
    onReorderPages,
    onDeletePage,
    initialCroppingPageId,
    onClearInitialCropping,
  });

  const performReorder = useCallback((fromIdx: number, toIdx: number) => {
    if (!onReorderPages) return;
    const newPages = [...docPages];
    const [moved] = newPages.splice(fromIdx, 1);
    newPages.splice(toIdx, 0, moved);
    onReorderPages(newPages.map(p => p.id));
  }, [docPages, onReorderPages]);

  const handleDragStart = useCallback((e: React.DragEvent, index: number) => {
    setDraggedIndex(index);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', index.toString());
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (draggedIndex === null || draggedIndex === index) return;
    setDragOverIndex(index);
  }, [draggedIndex]);

  const handleDrop = useCallback((e: React.DragEvent, index: number) => {
    e.preventDefault();
    const sourceIndex = parseInt(e.dataTransfer.getData('text/plain'), 10);
    if (isNaN(sourceIndex) || sourceIndex === index) return;
    performReorder(sourceIndex, index);
  }, [performReorder]);

  const handleDragEnd = useCallback(() => {
    setDraggedIndex(null);
    setDragOverIndex(null);
  }, []);

  // Touch handlers for responsive drag-and-drop on mobile devices
  const handleTouchStart = useCallback((e: React.TouchEvent, index: number) => {
    setDraggedIndex(index);
  }, []);

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (draggedIndex === null) return;
    const touch = e.touches[0];
    const element = document.elementFromPoint(touch.clientX, touch.clientY);
    if (!element) return;
    const card = element.closest('[data-reorder-page-index]');
    if (card) {
      const indexStr = card.getAttribute('data-reorder-page-index');
      if (indexStr) {
        const id = parseInt(indexStr, 10);
        if (!isNaN(id) && id !== draggedIndex) {
          setDragOverIndex(id);
        }
      }
    }
  }, [draggedIndex]);

  const handleTouchEnd = useCallback(() => {
    if (draggedIndex !== null && dragOverIndex !== null && draggedIndex !== dragOverIndex) {
      performReorder(draggedIndex, dragOverIndex);
    }
    setDraggedIndex(null);
    setDragOverIndex(null);
  }, [draggedIndex, dragOverIndex, performReorder]);

  const handleUpdateFilter = useCallback(async (pageId: string, newFilter: ScanFilterType, applyToAll?: boolean) => {
    addLog(`Filter changed to ${newFilter} for page ${pageId} (applyToAll: ${applyToAll})`);
    
    const pagesToUpdate = applyToAll ? pages : pages.filter(p => p.id === pageId);
    
    for (const page of pagesToUpdate) {
      const pageMeta = (page as any).meta || {
        cropPoints: page.corners,
        rotate: page.rotation,
        filter: page.filter,
        adjustments: page.adjustments
      };
      
      let normAdj = page.adjustments;
      if (pageMeta.adjustments) {
        normAdj = {
          brightness: typeof pageMeta.adjustments.b === 'number' ? pageMeta.adjustments.b : (typeof pageMeta.adjustments.brightness === 'number' ? pageMeta.adjustments.brightness : page.adjustments.brightness),
          contrast: typeof pageMeta.adjustments.c === 'number' ? pageMeta.adjustments.c : (typeof pageMeta.adjustments.contrast === 'number' ? pageMeta.adjustments.contrast : page.adjustments.contrast),
          saturation: typeof pageMeta.adjustments.s === 'number' ? pageMeta.adjustments.s : (typeof pageMeta.adjustments.saturation === 'number' ? pageMeta.adjustments.saturation : page.adjustments.saturation)
        };
      }

      const newMeta = {
        cropPoints: pageMeta.cropPoints || pageMeta.corners || page.corners,
        rotate: typeof pageMeta.rotate === 'number' ? pageMeta.rotate : page.rotation,
        filter: newFilter,
        adjustments: {
          b: normAdj.brightness,
          c: normAdj.contrast,
          s: normAdj.saturation
        }
      };

      await savePageMeta(page.id, newMeta);
      onUpdatePage({
        ...page,
        filter: newFilter,
        meta: newMeta
      });
    }
  }, [pages, onUpdatePage]);


  return (
    <div className="w-full flex flex-col gap-4" id="document-editor-root">
      <DeleteConfirmationModal
        isOpen={deleteConfirmation.isOpen}
        onClose={() => setDeleteConfirmation({ ...deleteConfirmation, isOpen: false })}
        onDelete={deleteConfirmation.onConfirm}
        title={deleteConfirmation.title}
        message={deleteConfirmation.message}
      />
      
      {/* --- Fullscreen Swipable Lightbox --- */}
      {lightboxIndex !== null && (
        <div 
          className="fixed inset-0 bg-black z-[100] flex flex-col animate-in fade-in duration-200"
          id="fullscreen-lightbox-overlay"
        >
          {/* Header Controls */}
          <div className="absolute top-0 left-0 right-0 flex items-center justify-between px-5 bg-gradient-to-b from-black/80 to-transparent z-10 pt-[calc(env(safe-area-inset-top)+0.5rem)] pb-4 h-auto">
            <button
              onClick={() => setLightboxIndex(null)}
              className="w-10 h-10 flex items-center justify-center bg-[var(--bg-card)]/40 hover:bg-[var(--bg-primary)] rounded-full text-[var(--text-primary)] backdrop-blur-md transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
            <span className="text-sm font-bold text-[var(--text-primary)] tracking-wide">
              Page {lightboxIndex + 1} of {docPages.length}
            </span>
<span className="w-10 h-10" />
          </div>

          {/* Swipeable Viewport */}
          <div className="flex-1 relative overflow-hidden">
            <ZoomableImage
              src={lightboxUrl}
              className="w-full h-full"
              onSwipeLeft={handleLightboxNext}
              onSwipeRight={handleLightboxPrev}
              resetTrigger={lightboxIndex}
            />
            
            {/* Quick Navigation Arrows (Desktop visible) */}
            <div className="hidden md:flex absolute inset-y-0 left-0 items-center px-4">
              <button
                disabled={lightboxIndex === 0}
                onClick={handleLightboxPrev}
                className="w-12 h-12 flex items-center justify-center bg-[var(--bg-card)]/60 hover:bg-[var(--bg-primary)] text-[var(--text-primary)] rounded-full transition-all disabled:opacity-0"
              >
                <ChevronLeft className="w-6 h-6" />
              </button>
            </div>
            <div className="hidden md:flex absolute inset-y-0 right-0 items-center px-4">
              <button
                disabled={lightboxIndex === docPages.length - 1}
                onClick={handleLightboxNext}
                className="w-12 h-12 flex items-center justify-center bg-[var(--bg-card)]/60 hover:bg-[var(--bg-primary)] text-[var(--text-primary)] rounded-full transition-all disabled:opacity-0"
              >
                <ChevronRight className="w-6 h-6" />
              </button>
            </div>
          </div>

          {/* Bottom Thumbnails Strip (Optional) */}
          <div className="h-20 bg-[var(--bg-card)]/40 backdrop-blur-xl border-t border-[var(--border-color)]/50 flex items-center justify-center gap-1.5 px-4 overflow-x-auto scrollbar-none">
            {docPages.map((p, idx) => {
              const url = pageUrls[p.id];
              return (
                <button
                  key={p.id}
                  onClick={() => setLightboxIndex(idx)}
                  className={`w-10 h-14 rounded border-2 transition-all shrink-0 overflow-hidden ${
                    lightboxIndex === idx ? 'border-[var(--primary)] scale-110 shadow-[0_0_15px_rgba(var(--primary-rgb),0.3)]' : 'border-transparent opacity-50 grayscale hover:grayscale-0 hover:opacity-80'
                  }`}
                >
                  <img src={url} className="w-full h-full object-cover" />
                </button>
              );
            })}
          </div>
        </div>
      )}
      {/* Navigation and Actions Row - Sticky & Blurry backdrop */}
      <div className="sticky top-0 z-45 flex flex-col bg-[var(--bg-card)]/95 backdrop-blur-md border-b border-[var(--border-color)] shadow-md pt-[env(safe-area-inset-top)]">
        {/* Main Title Row */}
        <div className="px-4 h-[var(--header-height)] flex flex-row items-center gap-3">
          <button
            onClick={onBack}
            className="min-h-11 min-w-11 sm:min-h-12 sm:min-w-12 border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-card)] rounded-full flex items-center justify-center transition-all cursor-pointer active:scale-95 shadow-sm shrink-0"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div className="min-w-0 flex-1">
            {isEditingTitle ? (
              <input
                type="text"
                value={editTitleValue}
                onChange={(e) => setEditTitleValue(e.target.value)}
                onBlur={handleFinishRename}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleFinishRename();
                  if (e.key === 'Escape') {
                    setEditTitleValue(activeDocument.title);
                    setIsEditingTitle(false);
                  }
                }}
                className="bg-[var(--bg-primary)] text-[var(--text-primary)] border border-[var(--border-color)] rounded-full px-4 py-1.5 text-sm sm:text-base font-sans font-black w-full max-w-[320px] outline-none focus:border-[var(--primary)] shadow-inner"
                autoFocus
              />
            ) : (
              <div 
                onClick={() => {
                  setEditTitleValue(activeDocument.title);
                  setIsEditingTitle(true);
                }} 
                className="cursor-pointer group flex flex-col min-w-0 select-none py-1"
              >
                <h2 className="text-base sm:text-lg font-black tracking-tight text-[var(--text-primary)] font-sans line-clamp-1 group-hover:text-[var(--primary)] transition-colors" title="Click to rename document">
                  {activeDocument.title}
                </h2>
                <div className="flex items-center gap-1.5 text-[var(--text-secondary)] font-mono text-[10px] sm:text-[11px] tracking-wide mt-0.5">
                  <span>{docPages.length} {docPages.length === 1 ? 'Page' : 'Pages'}</span>
                  <span>•</span>
                  <span>{new Date(activeDocument.updatedAt || activeDocument.createdAt).toLocaleDateString()}</span>
                  <span className="text-[8px] text-[var(--text-secondary)] opacity-0 group-hover:opacity-100 transition-opacity font-sans ml-1">(Click to rename)</span>
                </div>
              </div>
            )}
          </div>
          {docPages.length > 1 && (
            <button
              onClick={() => setIsReorderOpen(true)}
              className="min-h-11 min-w-11 sm:min-h-12 sm:min-w-12 border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--primary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-card)] rounded-full flex items-center justify-center transition-all cursor-pointer active:scale-95 shadow-sm shrink-0"
              title="Reorder Pages"
            >
              <ArrowUpDown className="w-5 h-5" />
            </button>
          )}
        </div>

        {/* Dynamic Toolbar for the 3 main actions */}
        <div className="flex flex-row items-center justify-center gap-2 px-4 pb-3" id="editor-header-buttons">
          <button
            onClick={onAddPage}
            className="flex-1 max-w-[140px] flex items-center justify-center gap-1.5 bg-[var(--primary)] hover:opacity-90 text-white px-3 py-2 sm:py-2.5 rounded-full transition-all cursor-pointer active:scale-95 text-xs font-black shadow-md whitespace-nowrap min-h-[40px]"
            id="editor-add-page-btn"
            title="Scan with Camera"
          >
            <Camera className="w-3.5 h-3.5" />
            <span className="font-bold">Scan Page</span>
          </button>

          {onImportPage && (
            <button
              onClick={onImportPage}
              className="flex-1 max-w-[140px] flex items-center justify-center gap-1.5 bg-[var(--bg-card)] border border-[var(--border-color)] hover:bg-[var(--bg-primary)] hover:text-[var(--text-primary)] text-[var(--text-secondary)] px-3 py-2 sm:py-2.5 rounded-full transition-all cursor-pointer active:scale-95 text-xs font-black border-dashed whitespace-nowrap min-h-[40px]"
              id="editor-import-page-btn"
              title="Import image from file system"
            >
              <Upload className="w-3.5 h-3.5 text-[var(--primary)]" />
              <span className="font-bold">Import Image</span>
            </button>
          )}

          {docPages.length > 0 && (
            <button
              onClick={() => setShowExportModal(true)}
              className="flex-1 max-w-[140px] flex items-center justify-center gap-1.5 bg-[var(--bg-card)] border border-[var(--border-color)] hover:bg-[var(--bg-primary)] text-[var(--text-primary)] px-3 py-2 sm:py-2.5 rounded-full transition-all cursor-pointer active:scale-95 text-xs font-black whitespace-nowrap min-h-[40px]"
              id="editor-export-doc-btn"
            >
              <FileDown className="w-3.5 h-3.5 text-[var(--primary)] font-bold" />
              <span className="font-bold">Export PDF</span>
            </button>
          )}
        </div>
      </div>

      {docPages.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-24 bg-[var(--bg-primary)] border border-[var(--border-color)] border-dashed rounded-3xl p-8 text-center mx-4 my-4" id="empty-pages-state">
          <div className="w-12 h-12 bg-[var(--bg-card)] p-3 rounded-full border border-[var(--border-color)] text-[var(--text-secondary)] mb-3 flex items-center justify-center">
            <Plus className="w-6 h-6" />
          </div>
          <h4 className="text-[var(--text-primary)] font-bold mb-1">No pages scanned inside this document</h4>
          <p className="text-[var(--text-secondary)] text-xs max-w-xs mb-5 font-sans">
            Add high-resolution image snapshots straight from your device camera or local storage.
          </p>
          <div className="flex flex-wrap gap-2 justify-center">
            <button
              onClick={onAddPage}
              className="bg-[var(--primary)] hover:opacity-90 text-white font-bold text-xs uppercase tracking-wider px-6 py-2.5 rounded-full cursor-pointer duration-150 active:scale-95 shadow-md flex items-center gap-1.5 font-sans"
            >
              <Camera className="w-3.5 h-3.5" /> Launch Camera
            </button>

            {onImportPage && (
              <button
                onClick={onImportPage}
                className="bg-[var(--bg-primary)] hover:bg-[var(--bg-card)] text-[var(--text-primary)] font-bold text-xs uppercase tracking-wider px-6 py-2.5 rounded-full border border-[var(--border-color)] cursor-pointer duration-150 active:scale-95 shadow-md flex items-center gap-1.5 font-sans"
              >
                <Upload className="w-3.5 h-3.5 text-[var(--primary)]" /> Import Image
              </button>
            )}
          </div>
        </div>
      ) : (
        <div className="flex flex-col w-full gap-3.5 max-w-md mx-auto px-4 pt-4 pb-12 mb-[calc(100px+env(safe-area-inset-bottom))]" id="vertical-pages-stack">
          {docPages.map((page, index) => (
            <SingleVerticalPageCard
              key={page.id}
              page={page}
              index={index}
              total={docPages.length}
              imgUrl={pageUrls[page.id] || ''}
              onDelete={() => {
                setDeleteConfirmation({
                  isOpen: true,
                  pageId: page.id,
                  title: `Permanently remove Page #${index + 1}?`,
                  message: "Are you sure you want to permanently remove this page scan?",
                  onConfirm: () => onDeletePage(page.id)
                });
              }}
              onImageClick={() => {
                setLightboxScale(1);
                setLightboxPan({ x: 0, y: 0 });
                setLightboxIndex(index);
              }}
              onMoveLeft={() => handleMovePage(index, 'left')}
              onMoveRight={() => handleMovePage(index, 'right')}
              isFirst={index === 0}
              isLast={index === docPages.length - 1}
              onEdit={() => { handleStartEditing(page); }}
              onUpdateFilter={handleUpdateFilter}
              onTriggerDocAI={(p, idx, url) => {
                setAiPage({ page: p, index: idx, imgUrl: url });
                setAiResult(null);
                setAiError(null);
              }}
              onReorderClick={() => setIsReorderOpen(true)}
            />
          ))}
        </div>
      )}

      {/* --- MODAL DIALOG OVERLAYS --- */}

      {/* 1. PDF Compilation configuration modal options */}
      <ExportModal 
        isOpen={showExportModal}
        onClose={() => setShowExportModal(false)}
        defaultTitle={exportOptions.documentTitle}
        onExport={(opts) => {
          if (opts.action) {
            handleCompilePDF(opts.title.trim() || activeDocument.title || 'Scanned_Doc', opts.action, {
              pageSize: opts.pageSize,
              orientation: opts.orientation,
              quality: opts.quality,
              documentTitle: opts.title.trim() || activeDocument.title || 'Scanned_Doc',
              password: opts.password
            });
          }
        }}
      />

      {/* 2. Swipable & Zoomable Fullscreen Lightbox Gallery view completely removed as per request */}
      
      {/* 4. Beautiful custom high-contrast Image editing Crop component screen */}
      {editingPage && editingBlob && (() => {
        const editingPageIndex = docPages.findIndex(p => p.id === editingPage.id);
        const hasNextPage = editingPageIndex !== -1 && editingPageIndex < docPages.length - 1;
        return (
          <Crop
            imageSrc={editingBlob}
            initialCorners={editingPage.corners}
            initialRotation={editingPage.rotation}
            initialFilter={editingPage.filter}
            initialAdjustments={editingPage.adjustments}
            sourceType="document"
            onSave={handleSaveEditedPage}
            onSaveAndNext={hasNextPage ? handleSaveEditedPageAndNext : undefined}
            onCancel={() => {
              handleSaveEditedPage(editingBlob, editingPage.corners, editingPage.rotation, editingPage.filter, editingPage.adjustments);
            }}
            onCropChange={async (newCorners) => {
              const pageMeta = editingPage.meta || {
                cropPoints: editingPage.corners,
                rotate: editingPage.rotation,
                filter: editingPage.filter,
                adjustments: editingPage.adjustments
              };
              const newMeta = { ...pageMeta, cropPoints: newCorners };
              await savePageMeta(editingPage.id, newMeta);
              onUpdatePage({
                ...editingPage,
                corners: newCorners,
                meta: newMeta
              });
            }}
          />
        );
      })()}

      {/* Loader while retrieving raw image blob from storage */}
      {loadingEditingBlob && (
        <div className="fixed inset-0 bg-[var(--bg-overlay)] backdrop-blur-md z-[55] flex flex-col items-center justify-center p-6 text-center select-none animate-in fade-in duration-100">
          <RefreshCw className="w-8 h-8 animate-spin text-[var(--primary)] mb-3" />
          <p className="text-[var(--text-secondary)] text-xs font-mono font-bold uppercase tracking-wider">
            Accessing Original Raw Image Matrix...
          </p>
        </div>
      )}

      {/* 5. SafeScan Intelligent Document AI OCR & Translation Modal Sheet */}
      {aiPage && (
        <div className="fixed inset-0 bg-black/75 backdrop-blur-md z-[50] flex items-center justify-center p-4 overflow-y-auto animate-in fade-in duration-200">
          <div className="bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-[2.5rem] w-full max-w-lg shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
            {/* Header */}
            <div className="p-5 border-b border-[var(--border-color)] bg-[var(--bg-card)] flex justify-between items-center">
              <div className="flex items-center gap-2 text-amber-500">
                <Sparkles className="w-5 h-5 fill-amber-500/20" />
                <h3 className="font-black text-sm uppercase tracking-widest text-[var(--text-primary)]">
                  {settings.customAppName || "SafeScan"} {t.extractText}
                </h3>
              </div>
              <button
                onClick={() => setAiPage(null)}
                className="p-1 px-2 border border-[var(--border-color)] rounded-full text-xs font-mono font-extrabold text-[var(--text-secondary)] hover:text-rose-500 hover:border-rose-500/50 transition-all cursor-pointer active:scale-95"
              >
                Close • ✕
              </button>
            </div>

            {/* Scrollable Container */}
            <div className="flex-1 overflow-y-auto p-5 space-y-5">
              {/* Image Thumbnail and Option bar */}
              <div className="flex items-center gap-4 bg-[var(--bg-card)] p-4 rounded-3xl border border-[var(--border-color)]">
                <div className="w-14 h-14 rounded-xl overflow-hidden shrink-0 border border-[var(--border-color)] bg-gray-100">
                  <img src={aiPage.imgUrl} className="w-full h-full object-cover" alt="Active Scan" />
                </div>
                <div className="flex-1 space-y-1">
                  <span className="text-[10px] font-black uppercase text-[var(--text-secondary)] font-mono tracking-wider">
                    Target Document Page {aiPage.index + 1}
                  </span>
                  <div className="flex items-center gap-1">
                    <Languages className="w-3.5 h-3.5 text-[var(--primary)]" />
                    <span className="text-xs text-[var(--text-primary)] font-bold">
                      Translation Input:
                    </span>
                  </div>
                </div>
                {/* Language Picker */}
                <select
                  value={targetLanguage}
                  onChange={(e) => setTargetLanguage(e.target.value)}
                  disabled={aiLoading}
                  className="bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-xl py-1 px-2.5 text-xs font-bold text-[var(--text-primary)] outline-none cursor-pointer focus:border-[var(--primary)] text-right"
                >
                  <option value="English">None (English)</option>
                  <option value="Urdu">Urdu (اردو)</option>
                  <option value="Spanish">Spanish (Español)</option>
                  <option value="Arabic">Arabic (العربية)</option>
                  <option value="French">French (Français)</option>
                  <option value="German">German (Deutsch)</option>
                  <option value="Hindi">Hindi (हिन्दी)</option>
                </select>
              </div>

              {/* Action Button trigger */}
              {!aiResult && !aiLoading && (
                <div className="space-y-3">
                  <button
                    type="button"
                    onClick={handleRunDocumentAI}
                    disabled={isOffline}
                    className={`w-full text-white font-extrabold text-xs uppercase tracking-wider py-4 rounded-2xl cursor-pointer hover:opacity-90 active:scale-[0.98] transition-all flex items-center justify-center gap-2 shadow-md min-h-[48px] ${
                      isOffline ? 'bg-zinc-700 pointer-events-none opacity-50' : 'bg-[var(--primary)]'
                    }`}
                  >
                    <Cpu className="w-4 h-4" />
                    {aiLoading ? t.analyzing : t.runAnalysis}
                  </button>
                  {isOffline && (
                    <div className="flex items-center gap-2 justify-center py-2 px-4 bg-amber-500/10 border border-amber-500/20 rounded-xl">
                      <ZapOff className="w-3.5 h-3.5 text-amber-600" />
                      <span className="text-[10px] font-bold text-amber-700 uppercase tracking-tight">
                        {t.onlineRequired}
                      </span>
                    </div>
                  )}
                </div>
              )}

              {/* Loading State with pulsating elements */}
              {aiLoading && (
                <div className="flex flex-col items-center justify-center py-10 space-y-3.5 bg-[var(--bg-card)] border border-[var(--border-color)] border-dashed rounded-3xl">
                  <div className="relative">
                    <Sparkles className="w-8 h-8 text-amber-500 fill-amber-500/10 animate-[pulse_1.5s_infinite]" />
                    <div className="absolute inset-0 border border-amber-500/30 rounded-full scale-150 animate-[ping_1.5s_infinite] opacity-35" />
                  </div>
                  <div className="text-center font-mono space-y-1">
                    <p className="text-xs font-bold text-[var(--text-primary)] uppercase tracking-wider">
                      Analyzing with Gemini-3.5-flash...
                    </p>
                    <p className="text-[10px] text-[var(--text-secondary)]">
                      Scanning OCR boundaries, extracting layout & values
                    </p>
                  </div>
                </div>
              )}

              {/* Error state */}
              {aiError && (
                <div className="bg-rose-500/10 border border-rose-500/35 rounded-3xl p-4 flex gap-3">
                  <AlertCircle className="w-5 h-5 text-rose-500 shrink-0 mt-0.5" />
                  <div className="space-y-1">
                    <h5 className="text-xs font-bold text-rose-500 font-mono uppercase tracking-wider">
                      AI Analysis Module Stopped
                    </h5>
                    <p className="text-xs text-[var(--text-secondary)]">
                      {aiError}
                    </p>
                  </div>
                </div>
              )}

              {/* Successful result parameters displaying structured data */}
              {aiResult && (
                <div className="space-y-4 animate-in fade-in zoom-in-95 duration-200">
                  {/* Row showing classification classification badges */}
                  <div className="flex flex-wrap gap-2">
                    <div className="bg-purple-500/10 border border-purple-500/20 px-3 py-1.5 rounded-full flex items-center gap-1.5 shrink-0 select-none">
                      <Layers className="w-3.5 h-3.5 text-purple-500" />
                      <span className="text-[10px] font-black uppercase text-purple-500 font-mono tracking-wider">
                        Type: {aiResult.documentType || "Unclassified"}
                      </span>
                    </div>
                    <div className="bg-[var(--primary)]/10 border border-[var(--primary)]/20 px-3 py-1.5 rounded-full flex items-center gap-1.5 shrink-0 select-none">
                      <Languages className="w-3.5 h-3.5 text-[var(--primary)]" />
                      <span className="text-[10px] font-black uppercase text-[var(--primary)] font-mono tracking-wider font-extrabold">
                        {t.detected}: {aiResult.detectedLanguage || "Unknown"}
                      </span>
                    </div>
                  </div>

                  {/* Summary translated Section */}
                  <div className="p-4 bg-amber-500/5 border border-amber-500/12 rounded-2xl md:p-5 select-text space-y-1.5">
                    <h4 className="text-[11px] font-black uppercase text-amber-500 font-mono tracking-widest flex items-center gap-1">
                      <Sparkles className="w-3.5 h-3.5 fill-amber-500/20" /> {t.smartSummary} ({targetLanguage})
                    </h4>
                    <p className="text-xs text-[var(--text-primary)] leading-relaxed font-sans">
                      {aiResult.summaryText}
                    </p>
                  </div>

                  {/* Extracted Key Fields list Table */}
                  {aiResult.extractedFields && aiResult.extractedFields.length > 0 && (
                    <div className="rounded-2xl border border-[var(--border-color)] overflow-hidden">
                      <div className="p-3 bg-[var(--bg-card)] border-b border-[var(--border-color)]">
                        <span className="text-[10px] font-black uppercase text-[var(--text-secondary)] font-mono tracking-wider block">
                          {t.fields}
                        </span>
                      </div>
                      <table className="w-full text-xs text-left border-collapse select-text">
                        <thead>
                          <tr className="bg-[var(--bg-primary)] border-b border-[var(--border-color)] text-[var(--text-secondary)] font-bold text-[10px] uppercase font-mono">
                            <th className="p-2.5">Field / Key</th>
                            <th className="p-2.5 text-right font-black">Value</th>
                          </tr>
                        </thead>
                        <tbody>
                          {aiResult.extractedFields.map((field: any, i: number) => (
                            <tr key={i} className="border-b border-[var(--border-color)]/60 last:border-b-0 hover:bg-[var(--bg-card)]">
                              <td className="p-2.5 font-bold text-[var(--text-secondary)]">{field.label}</td>
                              <td className="p-2.5 text-right font-mono font-bold text-[var(--text-primary)]">{field.value}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}

                  {/* Complete OCR transcription component block */}
                  <div className="bg-[var(--bg-card)] rounded-2xl border border-[var(--border-color)] overflow-hidden flex flex-col">
                    <div className="p-3 border-b border-[var(--border-color)] flex justify-between items-center select-none bg-[var(--bg-primary)]">
                      <span className="text-[10px] font-black uppercase text-[var(--text-secondary)] font-mono tracking-wider">
                        {t.ocrTranscript}
                      </span>
                      <button
                        onClick={() => handleCopy(aiResult.fullTranscript)}
                        className="flex items-center gap-1 px-2 py-1 bg-[var(--bg-card)] border border-[var(--border-color)] rounded-lg text-[10px] font-bold text-[var(--text-secondary)] hover:text-[var(--text-primary)] cursor-pointer transition-all shrink-0 active:scale-95"
                      >
                        {aiCopied ? (
                          <>
                            <Check className="w-3 h-3 text-[var(--primary)]" />
                            Copied!
                          </>
                        ) : (
                          <>
                            <Copy className="w-3 h-3" />
                            {t.copyText}
                          </>
                        )}
                      </button>
                    </div>
                    <div className="p-4 overflow-y-auto max-h-[220px] select-text">
                      <p className="text-xs font-mono text-[var(--text-primary)] leading-relaxed whitespace-pre-wrap select-text">
                        {aiResult.fullTranscript}
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* Footer sync stats */}
            <div className="p-4 border-t border-[var(--border-color)] bg-[var(--bg-card)] text-center font-mono flex items-center justify-between">
              <span className="text-[9px] text-[var(--text-secondary)] font-bold">
                SECURE ENDPOINT • LOCAL BLOB
              </span>
              <button
                onClick={() => setAiPage(null)}
                className="bg-zinc-800 text-white font-extrabold text-[10px] uppercase py-1.5 px-3.5 rounded-lg border border-zinc-700 hover:bg-zinc-700 transition-all cursor-pointer"
              >
                Done
              </button>
            </div>
          </div>
        </div>
      )}

      {/* --- Fullscreen Reorder Pages Overlay --- */}
      {isReorderOpen && (
        <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-[100] flex flex-col animate-in fade-in duration-200">
          {/* Header */}
          <div className="flex items-center justify-between px-5 border-b border-zinc-800 bg-zinc-900/50 shrink-0 pt-[calc(env(safe-area-inset-top)+0.5rem)] pb-4 h-auto">
            <div className="flex items-center gap-2">
              <ArrowUpDown className="w-5 h-5 text-[var(--primary)] animate-pulse" />
              <span className="text-sm font-black uppercase tracking-widest text-zinc-100">
                Reorder Pages
              </span>
            </div>
            <button
              onClick={() => setIsReorderOpen(false)}
              className="px-4 py-2 bg-[var(--primary)] hover:bg-[var(--primary-hover)] text-white text-xs font-black rounded-full shadow-lg shadow-[var(--primary)]/20 active:scale-95 transition-all cursor-pointer uppercase tracking-wider"
            >
              Done
            </button>
          </div>

          {/* Grid View */}
          <div className="flex-1 overflow-y-auto p-6 grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4 no-scrollbar">
            {docPages.map((page, idx) => {
              const isDragging = draggedIndex === idx;
              const isDragOver = dragOverIndex === idx;
              const imgUrl = pageUrls[page.id] || '';
              return (
                <div 
                  key={page.id}
                  data-reorder-page-index={idx}
                  draggable="true"
                  onDragStart={(e) => handleDragStart(e, idx)}
                  onDragOver={(e) => handleDragOver(e, idx)}
                  onDragEnd={handleDragEnd}
                  onDrop={(e) => handleDrop(e, idx)}
                  onTouchStart={(e) => handleTouchStart(e, idx)}
                  onTouchMove={handleTouchMove}
                  onTouchEnd={handleTouchEnd}
                  className={`flex flex-col gap-2 group transition-all duration-200 select-none cursor-grab active:cursor-grabbing touch-none ${
                    isDragging ? 'opacity-30 scale-95 border-2 border-dashed border-[var(--primary)] rounded-xl' : ''
                  } ${
                    isDragOver ? 'border-2 border-solid border-[var(--primary)] scale-105 rounded-xl shadow-[0_0_15px_rgba(20,184,166,0.6)] font-black text-zinc-100' : ''
                  }`}
                >
                  <div className="relative aspect-[3/4] bg-zinc-900 rounded-xl border border-zinc-800 overflow-hidden shadow-2xl group-hover:border-[var(--primary)]/50 transition-all">
                    {imgUrl ? (
                      <img
                        src={imgUrl}
                        alt={`Page ${idx + 1}`}
                        className="w-full h-full object-cover rounded-xl pointer-events-none"
                      />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center">
                        <RefreshCw className="w-5 h-5 animate-spin text-zinc-500" />
                      </div>
                    )}
                    
                    {/* Badge count */}
                    <div className="absolute bottom-2 left-2 w-6 h-6 rounded-full bg-[var(--primary)] text-white text-[10px] font-black flex items-center justify-center shadow-xl border-2 border-zinc-900">
                      {idx + 1}
                    </div>

                    {/* Left / Right quick arrows */}
                    <div className="absolute top-2 right-2 flex gap-1 z-30 pointer-events-auto">
                      <button
                        disabled={idx === 0}
                        onClick={(e) => {
                          e.stopPropagation();
                          performReorder(idx, idx - 1);
                        }}
                        className="w-7 h-7 rounded-full bg-black/60 hover:bg-black/80 text-white flex items-center justify-center disabled:opacity-20 active:scale-90 transition-all cursor-pointer"
                        title="Move Page Back"
                      >
                        <ChevronLeft size={14} strokeWidth={2.5} />
                      </button>
                      <button
                        disabled={idx === docPages.length - 1}
                        onClick={(e) => {
                          e.stopPropagation();
                          performReorder(idx, idx + 1);
                        }}
                        className="w-7 h-7 rounded-full bg-black/60 hover:bg-black/80 text-white flex items-center justify-center disabled:opacity-20 active:scale-90 transition-all cursor-pointer"
                        title="Move Page Forward"
                      >
                        <ChevronRight size={14} strokeWidth={2.5} />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

export default React.memo(Editor);
