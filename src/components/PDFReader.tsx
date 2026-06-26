// AUDITED: Fixed canvas leaks and removed unused exports
import React, { useRef } from 'react';
import { FileText, X, Printer, LayoutGrid, ScrollText, BookOpen, Import } from 'lucide-react';
import { PDFPage } from './PDFReader/PDFPage';
import { PasswordModal } from './PDFReader/PasswordModal';
import { usePDFReaderHook } from './PDFReaderHook';
import { globalRenderCountRef } from '../utils/renderStats';
import { useSharedSettings } from '../lib/useSharedSettings';
import { useTranslation, Language } from '../lib/i18n';

interface PDFHeaderProps {
  pdfDoc: any;
  onClose: () => void;
  viewMode: 'scroll' | 'read' | 'grid';
  setViewMode: (mode: 'scroll' | 'read' | 'grid') => void;
  selectedPages: Set<number>;
  importSelected: () => void;
  isSelectionMode: boolean;
  setIsSelectionMode: (mode: boolean) => void;
  handlePrint: () => void;
  isPrinting: boolean;
  t: any;
}

const PDFHeader = React.memo(({
  pdfDoc,
  onClose,
  viewMode,
  setViewMode,
  selectedPages,
  importSelected,
  isSelectionMode,
  setIsSelectionMode,
  handlePrint,
  isPrinting,
  t
}: PDFHeaderProps) => {
  return (
    <div className="flex flex-row items-center justify-between p-2.5 sm:p-4 pt-[calc(0.4rem+env(safe-area-inset-top))] sm:pt-[calc(0.8rem+env(safe-area-inset-top))] bg-[var(--bg-card)]/80 backdrop-blur-md border-b border-[var(--border-color)] no-print select-none gap-2">
      {!pdfDoc ? (
        <>
          <div className="flex items-center gap-1.5">
            <FileText className="w-5 h-5 text-[var(--primary)] shrink-0" />
            <span className="font-bold text-sm tracking-tight text-[var(--text-primary)]">{(t as any).pdfReader || "PDF Reader"}</span>
          </div>
          
          <button 
            type="button"
            onClick={onClose} 
            className="p-2.5 min-w-[44px] min-h-[44px] sm:p-3 sm:min-w-[48px] sm:min-h-[48px] hover:bg-[var(--bg-primary)] rounded-xl flex items-center justify-center active:scale-95 transition-all outline-none" 
            title={(t as any).exitReader || "Exit Reader"}
          >
            <X className="w-5 h-5 text-[var(--text-secondary)]" />
          </button>
        </>
      ) : (
        <div className="flex items-center justify-between w-full font-sans gap-2 flex-nowrap">
          <div className="flex bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-xl p-0.5 gap-0.5 shrink-0">
            <button 
              type="button"
              onClick={() => setViewMode('scroll')} 
              className={`p-1.5 min-h-[40px] min-w-[40px] sm:p-2 sm:min-h-[48px] sm:min-w-[48px] rounded-lg transition-all flex items-center justify-center outline-none ${viewMode === 'scroll' ? 'bg-[var(--primary)] text-white font-bold shadow-sm' : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'}`}
              title={(t as any).scrollContinuousView || "Scroll continuous view"}
            >
              <ScrollText className="w-4 h-4 sm:w-5 sm:h-5"/>
            </button>
            <button 
              type="button"
              onClick={() => setViewMode('read')} 
              className={`p-1.5 min-h-[40px] min-w-[40px] sm:p-2 sm:min-h-[48px] sm:min-w-[48px] rounded-lg transition-all flex items-center justify-center outline-none ${viewMode === 'read' ? 'bg-[var(--primary)] text-white font-bold shadow-sm' : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'}`}
              title={(t as any).singlePageSwipeView || "Single page Swipe view"}
            >
              <BookOpen className="w-4 h-4 sm:w-5 sm:h-5"/>
            </button>
            <button 
              type="button"
              onClick={() => setViewMode('grid')} 
              className={`p-1.5 min-h-[40px] min-w-[40px] sm:p-2 sm:min-h-[48px] sm:min-w-[48px] rounded-lg transition-all flex items-center justify-center outline-none ${viewMode === 'grid' ? 'bg-[var(--primary)] text-white font-bold shadow-sm' : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'}`}
              title={(t as any).gridOverview || "Grid overview"}
            >
              <LayoutGrid className="w-4 h-4 sm:w-5 sm:h-5"/>
            </button>
          </div>
          
          <div className="flex items-center gap-1.5 sm:gap-2 ml-auto shrink-0">
            {selectedPages.size > 0 && (
              <button 
                type="button"
                onClick={importSelected} 
                className="bg-[var(--primary)] hover:opacity-90 text-white font-black px-3 sm:px-5 min-h-[40px] sm:min-h-[48px] rounded-xl text-[10px] uppercase tracking-widest flex items-center justify-center gap-1 active:scale-95 transition-all outline-none shadow-lg shadow-[var(--primary)]/20 animate-pulse"
              >
                {(t as any).importSelected || "Import"} {selectedPages.size}
              </button>
            )}

            <button 
              type="button"
              onClick={() => setIsSelectionMode(!isSelectionMode)} 
              className={`p-2 sm:p-3 min-h-[40px] min-w-[40px] sm:min-h-[48px] sm:min-w-[48px] hover:bg-[var(--bg-primary)] rounded-xl transition-all flex items-center justify-center outline-none ${isSelectionMode ? 'text-[var(--primary)] bg-[var(--primary-faint)] border border-[var(--primary)]/20' : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)]'}`}
              title={(t as any).selectMultiplePages || "Select Multiple Pages to Import"}
            >
              <Import className="w-4.5 h-4.5 sm:w-5 sm:h-5"/>
            </button>

            <button 
              type="button"
              onClick={handlePrint} 
              className="p-2 sm:p-3 min-h-[40px] min-w-[40px] sm:min-h-[48px] sm:min-w-[48px] hover:bg-[var(--bg-primary)] rounded-xl flex items-center justify-center active:scale-95 transition-all outline-none" 
              title={(t as any).printPdfFile || "Print PDF File"}
              disabled={isPrinting}
            >
              <Printer className="w-4.5 h-4.5 sm:w-5 sm:h-5 text-[var(--text-secondary)]" />
            </button>

            <button 
              type="button"
              onClick={onClose} 
              className="p-2 sm:p-3 min-h-[40px] min-w-[40px] sm:min-h-[48px] sm:min-w-[48px] hover:bg-rose-500/10 text-rose-500 rounded-xl flex items-center justify-center active:scale-95 transition-all outline-none border border-rose-500/20 bg-rose-500/5 shadow-xs" 
              title={(t as any).exitReader || "Exit Reader"}
            >
              <X className="w-4.5 h-4.5 sm:w-5 sm:h-5" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}, (prev, next) => {
  return (
    prev.pdfDoc === next.pdfDoc &&
    prev.viewMode === next.viewMode &&
    prev.isSelectionMode === next.isSelectionMode &&
    prev.isPrinting === next.isPrinting &&
    prev.selectedPages.size === next.selectedPages.size
  );
});

interface PDFReaderProps {
  onImportPage: (imageBlobs: Blob[]) => void;
  onClose: () => void;
  preloadedFile?: File | null;
  onClearPreloadedFile?: () => void;
  onScroll?: (e: React.UIEvent<HTMLDivElement>) => void;
}

export const PDFReader = React.forwardRef<
  { triggerBrowse: () => void },
  PDFReaderProps
>(function PDFReader({ onImportPage, onClose, preloadedFile, onClearPreloadedFile, onScroll }: PDFReaderProps, ref) {
  const { settings } = useSharedSettings();
  const { t } = useTranslation(settings.uiLanguage as Language);
  
  const renderCountRef = React.useRef(globalRenderCountRef);
  renderCountRef.current.current['PDFReader'] = (renderCountRef.current.current['PDFReader'] || 0) + 1;
  // console.log(`render PDFReader: ${renderCountRef.current.current['PDFReader']}x`);
  const {
    pdfDoc,
    numPages,
    scale,
    viewMode,
    setViewMode,
    pageNum,
    setPageNum,
    isPasswordModalOpen,
    setIsPasswordModalOpen,
    isSelectionMode,
    setIsSelectionMode,
    currentFile,
    selectedPages,
    isPrinting,
    fileInputRef,
    pageRefs,
    togglePageSelection,
    loadPDFFile,
    importSelected,
    importPageToScan,
    handlePrint,
  } = usePDFReaderHook({
    onImportPage,
    preloadedFile,
    onClearPreloadedFile,
    ref,
  });

  const touchStartX = useRef<number | null>(null);

  // High-performance hardware accelerated pinch-to-zoom
  const [zoomScale, setZoomScale] = React.useState<number>(1);
  const [isPinching, setIsPinching] = React.useState<boolean>(false);

  const zoomScaleRef = React.useRef(zoomScale);
  zoomScaleRef.current = zoomScale;

  const outerContainerRef = useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    // Reset zoom when changing view modes
    setZoomScale(1);
  }, [viewMode]);

  React.useEffect(() => {
    const container = outerContainerRef.current;
    if (!container) return;

    let initialDist = 0;
    let initialZoom = 1;

    const handleTouchStart = (e: TouchEvent) => {
      if (e.touches.length === 2) {
        e.preventDefault();
        setIsPinching(true);
        initialDist = Math.hypot(
          e.touches[0].clientX - e.touches[1].clientX,
          e.touches[0].clientY - e.touches[1].clientY
        );
        initialZoom = zoomScaleRef.current;
      }
    };

    const handleTouchMove = (e: TouchEvent) => {
      if (e.touches.length === 2 && initialDist > 0) {
        e.preventDefault();
        const dist = Math.hypot(
          e.touches[0].clientX - e.touches[1].clientX,
          e.touches[0].clientY - e.touches[1].clientY
        );
        const factor = dist / initialDist;
        const newZoom = Math.max(1, Math.min(4, initialZoom * factor));
        setZoomScale(newZoom);
      }
    };

    const handleTouchEnd = () => {
      setIsPinching(false);
    };

    container.addEventListener('touchstart', handleTouchStart, { passive: false });
    container.addEventListener('touchmove', handleTouchMove, { passive: false });
    container.addEventListener('touchend', handleTouchEnd);
    container.addEventListener('touchcancel', handleTouchEnd);

    return () => {
      container.removeEventListener('touchstart', handleTouchStart);
      container.removeEventListener('touchmove', handleTouchMove);
      container.removeEventListener('touchend', handleTouchEnd);
      container.removeEventListener('touchcancel', handleTouchEnd);
    };
  }, []);

  const handleDoubleTap = React.useCallback(() => {
    if (zoomScale > 1) {
      setZoomScale(1);
    } else {
      setZoomScale(2);
    }
  }, [zoomScale]);

  return (
    <div className="flex flex-col h-full bg-[var(--bg-primary)] text-[var(--text-primary)] overflow-hidden" id="pdf-view-main">
      <style>{`
        @media print { 
          .no-print { 
            display: none !important; 
          } 
          #pdf-view-main { 
            display: block !important; 
            background: var(--bg-card) !important; 
            color: var(--text-primary) !important;
            border: none !important; 
            box-shadow: none !important; 
          } 
          body { 
            background: var(--bg-card) !important; 
            color: var(--text-primary) !important;
          }
          #pdf-printing-container {
            display: block !important;
          }
          .printable-page {
            page-break-after: always !important;
            page-break-inside: avoid !important;
            break-after: page !important;
            break-inside: avoid !important;
            margin: 0 !important;
            padding: 0 !important;
            border: none !important;
            box-shadow: none !important;
            background: var(--bg-card) !important;
          }
          body * {
            visibility: hidden;
          }
          #pdf-printing-container, #pdf-printing-container * {
            visibility: visible;
          }
          #pdf-printing-container {
            position: absolute;
            left: 0;
            top: 0;
            width: 100%;
          }
        }
      `}</style>
      
      <input 
        ref={fileInputRef} 
        type="file" 
        accept="application/pdf" 
        className="hidden" 
        onChange={(e) => {
          if (e.target.files?.[0]) {
            loadPDFFile(e.target.files[0]);
          }
          e.target.value = '';
        }} 
      />

      {/* Header */}
      <PDFHeader
        pdfDoc={pdfDoc}
        onClose={onClose}
        viewMode={viewMode}
        setViewMode={setViewMode}
        selectedPages={selectedPages}
        importSelected={importSelected}
        isSelectionMode={isSelectionMode}
        setIsSelectionMode={setIsSelectionMode}
        handlePrint={handlePrint}
        isPrinting={isPrinting}
        t={t}
      />

      {/* Pages Container */}
      <div 
        ref={outerContainerRef}
        onScroll={onScroll}
        className="flex-1 overflow-auto p-0 flex flex-col items-center justify-start gap-4 w-full print:hidden transform-gpu will-change-[scroll-position] relative pb-[calc(100px+env(safe-area-inset-bottom))]"
        style={{ WebkitOverflowScrolling: 'touch' }}
      >
        {pdfDoc ? (
          <div
            className="w-full flex-grow flex flex-col items-center justify-start transform-gpu will-change-transform"
            onDoubleClick={handleDoubleTap}
            style={{
              transform: `scale(${zoomScale})`,
              transformOrigin: 'center 150px',
              transition: isPinching ? 'none' : 'transform 0.15s cubic-bezier(0.16, 1, 0.3, 1)',
            }}
          >
            {viewMode === 'scroll' ? (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4 w-full transform-gpu" style={{ transform: 'translate3d(0,0,0)' }}>
                {Array.from({ length: numPages }).map((_, i) => (
                  <PDFPage ref={(el) => pageRefs.current[i + 1] = el} key={i} pdfDoc={pdfDoc} pageNum={i + 1} scale={scale} onImportPage={importPageToScan} importingPageNum={null} isSelected={selectedPages.has(i + 1)} onToggleSelection={togglePageSelection} isSelectionMode={isSelectionMode} />
                ))}
              </div>
            ) : viewMode === 'read' ? (
              <div
                className="flex flex-col items-center gap-6 w-full select-none cursor-grab active:cursor-grabbing font-sans"
                style={{ touchAction: 'pan-y' }}
                onTouchStart={(e) => {
                  touchStartX.current = e.touches[0].clientX;
                }}
                onTouchEnd={(e) => {
                  if (touchStartX.current === null) return;
                  const diffX = e.changedTouches[0].clientX - touchStartX.current;
                  touchStartX.current = null;
                  if (Math.abs(diffX) > 60) {
                    if (diffX < 0) { // Swipe left, page next
                      setPageNum(p => Math.min(numPages, p + 1));
                    } else { // Swipe right, page previous
                      setPageNum(p => Math.max(1, p - 1));
                    }
                  }
                }}
                onMouseDown={(e) => {
                  touchStartX.current = e.clientX;
                }}
                onMouseUp={(e) => {
                  if (touchStartX.current === null) return;
                  const diffX = e.clientX - touchStartX.current;
                  touchStartX.current = null;
                  if (Math.abs(diffX) > 60) {
                    if (diffX < 0) { // Swipe left, page next
                      setPageNum(p => Math.min(numPages, p + 1));
                    } else { // Swipe right, page previous
                      setPageNum(p => Math.max(1, p - 1));
                    }
                  }
                }}
              >
                <PDFPage ref={(el) => pageRefs.current[pageNum] = el} pdfDoc={pdfDoc} pageNum={pageNum} scale={scale} onImportPage={importPageToScan} importingPageNum={null} isSelected={selectedPages.has(pageNum)} onToggleSelection={togglePageSelection} isSelectionMode={isSelectionMode} />
                
                <div className="flex items-center gap-4 bg-[var(--bg-card)] border border-[var(--border-color)] p-2 rounded-2xl no-print shadow-xl">
                  <button 
                    onClick={() => setPageNum(p => Math.max(1, p - 1))}
                    className="px-4 py-3 min-h-12 min-w-24 bg-[var(--bg-primary)] hover:bg-[var(--bg-card)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] rounded-xl font-bold transition-all active:scale-95 text-[10px] uppercase tracking-widest shadow-md border border-[var(--border-color)]"
                  >
                    {(t as any).previous || "Previous"}
                  </button>
                  <span className="font-mono text-xs px-2 text-[var(--text-secondary)]">{pageNum} / {numPages}</span>
                  <button 
                    onClick={() => setPageNum(p => Math.min(numPages, p + 1))}
                    className="px-4 py-3 min-h-12 min-w-24 bg-[var(--bg-primary)] hover:bg-[var(--bg-card)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] rounded-xl font-bold transition-all active:scale-95 text-[10px] uppercase tracking-widest shadow-md border border-[var(--border-color)]"
                  >
                    {(t as any).next || "Next"}
                  </button>
                </div>
              </div>
            ) : (
              <div className="grid grid-cols-2 md:grid-cols-3 gap-4 w-full">
                {Array.from({ length: numPages }).map((_, i) => (
                  <div key={i} className="cursor-pointer border border-zinc-800 p-2 rounded-2xl hover:bg-zinc-900 transition-all">
                    <div onClick={(_e) => { 
                      if (isSelectionMode) return;
                      setPageNum(i + 1); 
                      setViewMode('read'); 
                    }}>
                      <PDFPage ref={(el) => pageRefs.current[i + 1] = el} pdfDoc={pdfDoc} pageNum={i + 1} scale={0.5} onImportPage={importPageToScan} importingPageNum={null} isSelected={selectedPages.has(i + 1)} onToggleSelection={togglePageSelection} isSelectionMode={isSelectionMode} />
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center flex-1 min-h-[350px] w-full text-center gap-6 p-6">
            <div className="w-16 h-16 rounded-full bg-[var(--primary-faint)] border border-[var(--primary)]/20 flex items-center justify-center shadow-lg text-[var(--primary)]">
              <FileText className="w-8 h-8" />
            </div>
            <div className="space-y-1.5">
              <h3 className="text-base font-black uppercase tracking-tight text-[var(--text-primary)]">{(t as any).noDocument || "No Document"}</h3>
              <p className="text-[11px] text-[var(--text-secondary)] leading-relaxed font-sans font-medium">
                {(t as any).importPdfDesc || "Import a PDF document from your system to read, print chapters, or scan and extract pages directly."}
              </p>
            </div>
            <button
              onClick={() => fileInputRef.current?.click()}
              className="px-8 py-4 w-full flex items-center justify-center gap-2 bg-[var(--primary)] hover:opacity-90 text-white font-black rounded-2xl transition-all cursor-pointer active:scale-95 text-[10px] uppercase tracking-widest shadow-xl shadow-[var(--primary)]/25"
            >
              <Import className="w-5 h-5" />
              <span>{(t as any).importPdfFile || "Import PDF File"}</span>
            </button>
          </div>
        )}
      </div>

      {pdfDoc && isPrinting && (
        <div className="hidden print:block bg-[var(--bg-card)] text-[var(--text-primary)] w-full" id="pdf-printing-container">
          {Array.from({ length: numPages }).map((_, i) => (
            <div key={i} className="printable-page flex flex-col items-center justify-center p-0 m-0 bg-[var(--bg-card)]">
              <PDFPage 
                pdfDoc={pdfDoc} 
                pageNum={i + 1} 
                scale={1.5} 
                onImportPage={importPageToScan} 
                importingPageNum={null} 
                isSelected={false} 
                onToggleSelection={() => {}} 
                isSelectionMode={false} 
                forceRender={true} 
              />
            </div>
          ))}
        </div>
      )}

      <PasswordModal 
        isOpen={isPasswordModalOpen}
        onClose={() => setIsPasswordModalOpen(false)}
        onPasswordSubmit={(pwd) => currentFile && loadPDFFile(currentFile, pwd)}
      />
    </div>
  );
});

export default React.memo(PDFReader);

