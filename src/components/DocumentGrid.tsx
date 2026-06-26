import React, { useState, useEffect, useRef } from 'react';
import { ScanDocument, ScanPage } from '../types';
import { getImageBlob, getImageCacheBlob, saveImageCacheBlob, getDisplayCacheBlob, saveDisplayCacheBlob } from '../utils/db';
import { generatePageHash } from '../utils/imageWorkerClient';
import { globalImageCache } from '../utils/globalImageCache';
import {
  FileText, Search,
  X, Check, Edit3,
  Share2, Trash2,
  Scan
} from 'lucide-react';
import { useDocumentGridHook } from './DocumentGridHook';
import { globalRenderCountRef } from '../utils/renderStats';
import { useSharedSettings } from '../lib/useSharedSettings';
import { useTranslation, Language } from '../lib/i18n';

interface DocumentGridProps {
  documents: ScanDocument[];
  pages: ScanPage[];
  onSelectDocument: (docId: string) => void;
  onCreateDocument: (title?: string) => void;
  onDeleteDocument: (docId: string) => void;
  onDeleteDocuments: (docIds: string[]) => void;
  onRenameDocument: (docId: string, newTitle: string) => void;
  onExportPDF: (doc: ScanDocument) => void;
  onAddScanToDocument?: (docId: string) => void;
  onUpdateDocumentTags?: (docId: string, tags: string[]) => void;
  onTriggerImport?: () => void;
  onTriggerScan?: () => void;
}

// Memory-friendly asynchronous page thumbnail renderer from IndexedDB with dynamic perspective and filter processing off-thread
const PageThumbnail = React.memo(function PageThumbnail({ page, className }: { page: ScanPage; className?: string }) {
  const [loading, setLoading] = useState(() => {
    const hash = generatePageHash(page);
    return !globalImageCache.getUrl(hash);
  });
  const [imgUrl, setImgUrl] = useState(() => {
    const hash = generatePageHash(page);
    return globalImageCache.getUrl(hash) || '';
  });
  const [isVisible, setIsVisible] = useState(false);
  const elementRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // Synchronously check if cached to avoid delays entirely
    const hash = generatePageHash(page);
    const cached = globalImageCache.getUrl(hash);
    if (cached) {
      setImgUrl(cached);
      setLoading(false);
      setIsVisible(true);
      return;
    }

    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) {
        setIsVisible(true);
        observer.disconnect();
      }
    }, { rootMargin: '200px' });

    if (elementRef.current) observer.observe(elementRef.current);
    return () => observer.disconnect();
  }, [page]);

  useEffect(() => {
    if (!isVisible) return;

    const hash = generatePageHash(page);
    const cached = globalImageCache.getUrl(hash);
    if (cached) {
      setImgUrl(cached);
      setLoading(false);
      return;
    }

    let isMounted = true;

    const renderThumbnail = async () => {
      try {
        // Step 1: Try display-cache store (required by strict policy)
        let processedBlob: Blob | null = await getDisplayCacheBlob(hash);
        
        // Step 2: Fallback to old imageCache store for historical compatibility
        if (!processedBlob) {
          processedBlob = await getImageCacheBlob(hash);
        }

        if (!processedBlob) {
          const rawBlob = await getImageBlob(page.originalImageId);
          if (!rawBlob || !isMounted) {
            if (isMounted) setLoading(false);
            return;
          }

          const { processFinalImageOffThread } = await import('../utils/imageWorkerClient');
          const bitmap = await createImageBitmap(rawBlob);
          if (!isMounted) {
            bitmap.close();
            return;
          }

          processedBlob = await processFinalImageOffThread(
            bitmap,
            page.corners,
            page.rotation,
            page.filter,
            page.adjustments,
            'preview'
          );

          if (isMounted && processedBlob) {
            // Persist to display-cache as required by the secure policy
            await saveDisplayCacheBlob(hash, processedBlob);
          }
        }

        if (!isMounted || !processedBlob) return;

        // Register to our global in-memory cache to keep it fast
        const finalUrl = globalImageCache.put(hash, processedBlob);
        
        if (isMounted) {
          setImgUrl(finalUrl);
          setLoading(false);
        }
      } catch (err) {
        console.error('Dynamic thumbnail render failed:', err);
        if (isMounted) setLoading(false);
      }
    };

    renderThumbnail();

    return () => {
      isMounted = false;
    };
  }, [page, isVisible]);

  return (
    <div 
      ref={elementRef}
      className={`relative ${className} overflow-hidden bg-gray-100`} 
      style={{ aspectRatio: '1/1' }}
    >
      {!isVisible && !imgUrl ? (
        <div className="absolute inset-0 bg-gray-100 flex items-center justify-center">
          <FileText className="w-5 h-5 text-gray-300" />
        </div>
      ) : loading && (
        <div className="absolute inset-0 bg-gray-100 animate-pulse flex items-center justify-center">
          <FileText className="w-5 h-5 text-gray-300" />
        </div>
      )}
      {imgUrl && (
        <img
          src={imgUrl}
          alt={`Page ${page.id}`}
          loading="lazy"
          className={`w-full h-full object-cover rounded-lg transition-opacity duration-200 ${loading ? 'opacity-0' : 'opacity-100'}`}
        />
      )}
    </div>
  );
});

// Helper component to highlight search queries in text
const HighlightText = React.memo(({ text, search }: { text: string; search: string }) => {
  if (!search) return <>{text}</>;
  const parts = text.split(new RegExp(`(${search.replace(/[-\/\\^$*+?.()|[\]{}]/g, '\\$&')})`, 'gi'));
  return (
    <>
      {parts.map((part, i) => 
        part.toLowerCase() === search.toLowerCase() ? (
          <mark key={i} className="bg-amber-500/20 text-amber-500 rounded px-0.5 font-bold">{part}</mark>
        ) : (
          part
        )
      )}
    </>
  );
});

// Memoized Document item card that prevents unnecessary complete list updates
const DocumentCard = React.memo(({
  doc,
  docPages,
  firstPage,
  onSelectDocument,
  isEditing,
  renameValue,
  onRenameValueChange,
  onCommitRename,
  isSelected,
  onSelect,
  searchQuery,
}: {
  doc: ScanDocument;
  docPages: ScanPage[];
  firstPage: ScanPage | undefined;
  onSelectDocument: (docId: string) => void;
  isEditing: boolean;
  renameValue: string;
  onRenameValueChange: (val: string) => void;
  onCommitRename: (docId: string) => void;
  isSelected?: boolean;
  onSelect?: (docId: string, e: React.MouseEvent) => void;
  viewMode?: 'grid' | 'list';
  searchQuery: string;
  onUpdateDocumentTags?: (docId: string, tags: string[]) => void;
  onExportPDF?: (doc: ScanDocument) => void;
}) => {
  const handleCommit = () => onCommitRename(doc.id);
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleCommit();
    if (e.key === 'Escape') onRenameValueChange(doc.title);
  };

  return (
    <div
      onClick={() => onSelectDocument(doc.id)}
      className={`group relative flex items-center p-3 transition-colors cursor-pointer select-none border-b border-[var(--border-color)] ${
        isSelected ? 'bg-[var(--primary)]/10' : 'bg-[var(--bg-primary)] hover:bg-[var(--bg-card)]'
      }`}
    >
      {/* Thumbnail */}
      <div className="relative w-16 h-16 rounded-xl overflow-hidden shrink-0 shadow-sm border border-[var(--border-color)]">
        {firstPage ? (
          <PageThumbnail page={firstPage} className="w-full h-full object-cover" />
        ) : (
          <div className="w-full h-full flex items-center justify-center bg-[var(--primary)]/20 text-[var(--primary)] rounded-xl">
            <div className="w-8 h-8 flex items-center justify-center font-bold">
              {doc.title.charAt(0).toUpperCase()}
            </div>
          </div>
        )}
      </div>

      {/* Info */}
      <div className="flex-1 ml-4 overflow-hidden">
        {isEditing ? (
          <input
            type="text"
            value={renameValue}
            onChange={(e) => onRenameValueChange(e.target.value)}
            onBlur={handleCommit}
            onKeyDown={handleKeyDown}
            className="bg-[var(--bg-primary)] border border-[var(--primary)] rounded-lg px-2 py-1 text-[var(--text-primary)] text-sm font-medium outline-none w-full"
            autoFocus
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <div className="flex items-center gap-1.5 cursor-pointer">
            <h4 className="font-semibold text-[var(--text-primary)] tracking-tight text-[15px] truncate">
              <HighlightText text={doc.title} search={searchQuery} />
            </h4>
          </div>
        )}
        <div className="flex items-center gap-2 mt-0.5">
          <span className="text-[12px] text-[var(--text-secondary)] font-medium">
            {new Date(doc.updatedAt).toLocaleDateString(undefined, { 
              month: '2-digit', 
              day: '2-digit', 
              year: 'numeric' 
            })} {new Date(doc.updatedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })}
          </span>
          {docPages.length > 0 && (
            <div className="flex items-center gap-1 text-[12px] text-[var(--text-secondary)] font-medium ml-1">
              <FileText size={12} className="stroke-[1.5]" />
              <span>{docPages.length}</span>
            </div>
          )}
        </div>
      </div>

      {/* Checkbox */}
      <div className="ml-2" onClick={(e) => e.stopPropagation()}>
        <button 
          onClick={(e) => onSelect?.(doc.id, e)} 
          className={`w-6 h-6 rounded flex items-center justify-center border transition-all ${
            isSelected 
              ? 'bg-[var(--primary)] border-[var(--primary)] text-white' 
              : 'bg-[var(--bg-card)] border-[var(--border-color)] shadow-sm'
          }`}
        >
          {isSelected && <Check size={16} strokeWidth={4} />}
        </button>
      </div>
    </div>
  );
}, (prev, next) => {
  // Always re-render if essential props change
  if (prev.isSelected !== next.isSelected) return false;
  if (prev.isEditing !== next.isEditing) return false;
  if (prev.doc.id !== next.doc.id) return false;
  if (prev.doc.title !== next.doc.title) return false;
  if (prev.docPages.length !== next.docPages.length) return false;
  if (prev.searchQuery !== next.searchQuery) return false;
  if (prev.firstPage?.id !== next.firstPage?.id) return false;
  
  // ONLY re-render due to renameValue if THIS card is currently being edited
  if (next.isEditing && prev.renameValue !== next.renameValue) return false;
  
  // Compare functions if needed, but we assumed stable callbacks from DocumentGrid
  return true;
});

function DocumentGrid({
  documents,
  pages,
  onSelectDocument,
  onCreateDocument: _unused_onCreate,
  onDeleteDocument: _unused_onDelete,
  onDeleteDocuments,
  onRenameDocument,
  onExportPDF,
  onAddScanToDocument: _unused_onAddScan,
  onUpdateDocumentTags: _unused_onUpdateTags,
  onTriggerImport,
  onTriggerScan,
}: DocumentGridProps) {
  const renderCountRef = React.useRef(globalRenderCountRef);
  renderCountRef.current.current['Library'] = (renderCountRef.current.current['Library'] || 0) + 1;

  const {
    searchQuery,
    setSearchQuery,
    docToDelete,
    setDocToDelete,
    filteredDocs,
    editingDocId,
    renameValue,
    setRenameValue,
    handleStartRename,
    handleCommitRename: __handleCommitRename,
    selectedDocIds,
    toggleSelect,
    clearSelection,
    selectAll,
  } = useDocumentGridHook({ documents, onRenameDocument });

  const [focusedDocId, setFocusedDocId] = useState<string | null>(null);

  const isSelectionMode = selectedDocIds.size > 0;
  const showBars = isSelectionMode || !!focusedDocId;

  // New commit rename to ensure we close editor
  const handleCommitRenameFull = React.useCallback((id: string) => {
    __handleCommitRename(id);
  }, [__handleCommitRename]);

  const { settings } = useSharedSettings();
  const { t } = useTranslation(settings.uiLanguage as Language);

  return (
    <div className="w-full h-full flex flex-col bg-[var(--bg-primary)] overflow-hidden relative" id="document-grid-main">
      {/* Top Header */}
      {showBars ? (
        <div className="sticky top-0 z-50 bg-[var(--bg-primary)] border-b border-[var(--border-color)] flex items-center justify-between px-4 py-1 pt-[calc(0.25rem+env(safe-area-inset-top))] shrink-0 animate-in fade-in slide-in-from-top-2 duration-300">
          <div className="flex items-center gap-6">
            <button onClick={() => { clearSelection(); setFocusedDocId(null); }} className="text-[var(--text-primary)] hover:bg-[var(--bg-card)] p-1 rounded-full transition-colors">
              <X size={20} />
            </button>
            <span className="text-lg font-medium text-[var(--text-primary)]">
              {isSelectionMode ? `${selectedDocIds.size} selected` : '1 selected'}
            </span>
          </div>
          {isSelectionMode && (
            <button 
              onClick={selectAll}
              className="text-[var(--primary)] font-semibold text-[15px] hover:opacity-80 active:scale-95 transition-all"
            >
              {selectedDocIds.size === filteredDocs.length && filteredDocs.length > 0 ? 'Deselect All' : 'Select All'}
            </button>
          )}
        </div>
      ) : (
        <div className="sticky top-0 z-50 bg-[var(--bg-primary)] border-b border-[var(--border-color)] px-4 pt-[calc(1rem+env(safe-area-inset-top))] pb-3 shrink-0 flex flex-col gap-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <h2 className="text-2xl font-black text-[var(--text-primary)] tracking-tight">
                {settings.customAppName || "SafeScan"}
              </h2>
              <span className="bg-[var(--primary)]/10 text-[var(--primary)] text-[10px] uppercase font-black px-2 py-0.5 rounded-full tracking-wider mt-1">
                {t.library}
              </span>
            </div>
          </div>
          <div className="relative mb-1">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-secondary)]" />
            <input
              type="text"
              placeholder={t.search}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-[var(--bg-card)] border border-[var(--border-color)] rounded-xl py-2 pl-9 pr-4 text-[15px] focus:bg-[var(--bg-primary)] focus:ring-2 focus:ring-[var(--primary)]/10 focus:border-[var(--primary)]/30 outline-none transition-all placeholder:text-[var(--text-secondary)]"
            />
          </div>
        </div>
      )}

      {/* Scrollable Content */}
      <div className="flex-1 overflow-y-auto pb-[calc(100px+env(safe-area-inset-bottom))]">
        {/* List items */}
        {filteredDocs.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-24 text-center px-10 animate-fade-in select-none">
            <button 
              onClick={() => onTriggerScan?.()}
              className="relative mb-6 cursor-pointer group active:scale-95 transition-all duration-300 pointer-events-auto"
              title="Tap to scan a new document"
              id="empty-state-scan-btn"
            >
              {/* Outer soft glowing ring */}
              <div className="absolute inset-0 rounded-full bg-[var(--primary)]/5 group-hover:bg-[var(--primary)]/10 blur-xl scale-125 transition-colors" />
              {/* Minimal nested circular frames */}
              <div className="w-16 h-16 bg-gradient-to-tr from-[var(--bg-card)] to-[var(--bg-primary)] border border-[var(--border-color)]/65 rounded-full text-[var(--text-secondary)] flex items-center justify-center shadow-sm relative z-10 group-hover:scale-105 duration-300 group-hover:border-[var(--primary)]/40">
                <FileText className="w-6 h-6 stroke-[1.5] text-[var(--primary)] group-hover:scale-110 transition-transform" />
                {/* Secondary overlapping minimal indicator */}
                <div className="absolute -bottom-0.5 -right-0.5 w-5 h-5 rounded-full bg-[var(--bg-card)] border border-[var(--border-color)] flex items-center justify-center shadow-sm">
                  <span className="w-1.5 h-1.5 rounded-full bg-[var(--primary)] animate-pulse" />
                </div>
              </div>
            </button>
            <h3 className="text-[var(--text-primary)] font-bold text-[15px] mb-1 tracking-tight font-sans">{t.noDocs}</h3>
            <p className="text-[var(--text-secondary)] text-xs max-w-[240px] leading-relaxed mb-6">{t.emptyStateDesc}</p>
            
            <div className="flex items-center gap-3">
              <button 
                onClick={() => onTriggerScan?.()}
                className="flex items-center gap-1.5 px-4.5 py-2.5 bg-[var(--primary)] hover:opacity-90 text-white text-xs font-bold rounded-full transition-all active:scale-95 shadow-md shadow-[var(--primary)]/25 cursor-pointer pointer-events-auto"
              >
                <Scan size={14} className="stroke-[2.5]" />
                <span>{t.newScan}</span>
              </button>
              <button 
                onClick={() => onTriggerImport?.()}
                className="flex items-center gap-1.5 px-4.5 py-2.5 bg-[var(--bg-card)] hover:bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-primary)] text-xs font-bold rounded-full transition-all active:scale-95 shadow-sm cursor-pointer pointer-events-auto"
              >
                <FileText size={14} className="stroke-[2]" />
                <span>{t.importFiles}</span>
              </button>
            </div>
          </div>
        ) : (
          <div className="flex flex-col divide-y divide-[var(--border-color)]" id="documents-list-container">
            {filteredDocs.map((doc) => {
              const docPages = pages.filter((p) => p.docId === doc.id);
              const firstPage = docPages[0];

              return (
                <DocumentCard
                  key={doc.id}
                  doc={doc}
                  docPages={docPages}
                  firstPage={firstPage}
                  onSelectDocument={(id) => { onSelectDocument(id); setFocusedDocId(id); }}
                  isEditing={editingDocId === doc.id}
                  renameValue={editingDocId === doc.id ? renameValue : ''}
                  onRenameValueChange={setRenameValue}
                  onCommitRename={handleCommitRenameFull}
                  isSelected={selectedDocIds.has(doc.id)}
                  onSelect={toggleSelect}
                  searchQuery={searchQuery}
                />
              );
            })}
          </div>
        )}
      </div>

      {/* Bottom Actions Footer - Only show in Selection Mode */}
      {showBars && (
        <div className="fixed bottom-0 left-0 right-0 z-[100] bg-[var(--bg-card)] border-t border-[var(--border-color)] flex items-center justify-around px-4 pt-1 pb-4 md:pb-2 max-w-[100vw] overflow-x-hidden animate-in slide-in-from-bottom duration-300 shadow-2xl">
          <ActionButton 
            icon={<Share2 size={20} />}
            label="Share" 
            onClick={() => {
              const docId = isSelectionMode ? Array.from(selectedDocIds)[0] : focusedDocId;
              const doc = documents.find(d => d.id === docId);
              if (doc && onExportPDF) onExportPDF(doc);
            }} 
            disabled={!isSelectionMode && !focusedDocId}
          />
          {isSelectionMode && (
            <ActionButton 
              icon={<Edit3 size={20} />}
              label="Rename" 
              disabled={selectedDocIds.size !== 1}
              onClick={() => {
                const firstId = Array.from(selectedDocIds)[0];
                const doc = documents.find(d => d.id === firstId);
                if (doc) handleStartRename(doc);
              }} 
            />
          )}
          <ActionButton 
            icon={<Trash2 size={20} />} 
            label="Delete" 
            onClick={() => {
              const docId = isSelectionMode ? Array.from(selectedDocIds)[0] : focusedDocId;
              const doc = documents.find(d => d.id === docId);
              if (doc) setDocToDelete(doc);
            }} 
            disabled={!isSelectionMode && !focusedDocId}
          />
        </div>
      )}

      {/* Delete Confirmation */}
      {docToDelete && (
        <div 
          className="fixed inset-0 z-[110] flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm animate-in fade-in duration-200"
          onClick={() => setDocToDelete(null)}
        >
          <div 
            className="w-full max-w-sm bg-[var(--bg-card)] rounded-3xl p-6 shadow-2xl flex flex-col gap-4 text-[var(--text-primary)] animate-in zoom-in-95 duration-150 border border-[var(--border-color)]"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="font-bold text-lg">{t.deleteTitle} {selectedDocIds.size > 1 ? `(${selectedDocIds.size})` : ''}?</h3>
             <p className="text-sm text-[var(--text-secondary)] leading-relaxed">
               {t.deleteDesc}
             </p>
             <div className="flex gap-3 mt-2">
                <button
                  type="button"
                  onClick={() => setDocToDelete(null)}
                  className="flex-1 py-3 rounded-2xl text-sm font-semibold text-[var(--text-primary)] bg-[var(--bg-primary)] hover:bg-[var(--border-color)] transition-all"
                >
                  {t.cancel}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    if (selectedDocIds.size > 0) {
                      onDeleteDocuments(Array.from(selectedDocIds));
                    }
                    setDocToDelete(null);
                    clearSelection();
                  }}
                  className="flex-1 py-3 rounded-2xl text-sm font-semibold text-white bg-rose-500 hover:bg-rose-600 shadow-lg shadow-rose-500/20 transition-all cursor-pointer"
                >
                  {t.confirmDelete}
                </button>
             </div>
          </div>
        </div>
      )}
    </div>
  );
}

const ActionButton = React.memo(({ icon, label, onClick, disabled = false }: { icon: React.ReactNode, label: string, onClick?: () => void, disabled?: boolean }) => (
  <button 
    onClick={onClick}
    disabled={disabled}
    className={`flex flex-col items-center gap-1 min-w-[64px] transition-all active:scale-95 ${
      disabled ? 'opacity-20 pointer-events-none' : 'text-[var(--text-primary)] hover:text-[var(--primary)]'
    }`}
  >
    <div className="w-6 h-6 flex items-center justify-center">
      {icon}
    </div>
    <span className="text-[11px] font-medium leading-none">{label}</span>
  </button>
));

export default React.memo(DocumentGrid);

