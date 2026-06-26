import React, { useRef, useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';

interface PDFPageProps {
  pdfDoc: any;
  pageNum: number;
  scale: number;
  onImportPage: (num: number) => any;
  importingPageNum: number | null;
  isSelected: boolean;
  onToggleSelection: (pageNum: number) => void;
  isSelectionMode: boolean;
  forceRender?: boolean;
}

const PDFPageComponent = React.forwardRef(function PDFPage(
    { pdfDoc, pageNum, scale, isSelected, onToggleSelection, isSelectionMode, forceRender }: PDFPageProps, 
    ref: React.Ref<any>
) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [isVisible, setIsVisible] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const renderTaskRef = useRef<any>(null);

  const renderedScaleRef = useRef<number | null>(null);
  const renderedPageNumRef = useRef<number | null>(null);
  const renderedPdfDocRef = useRef<any>(null);

  React.useImperativeHandle(ref, () => ({
    exportBlob: async (): Promise<Blob | null> => {
        if (!canvasRef.current) return null;
        return new Promise(resolve => canvasRef.current!.toBlob(resolve, 'image/png'));
    }
  }));

  useEffect(() => {
    if (forceRender) {
      setIsVisible(true);
      return;
    }
    const observer = new IntersectionObserver(
      ([entry]) => setIsVisible(entry.isIntersecting),
      { rootMargin: '600px' }
    );
    if (containerRef.current) observer.observe(containerRef.current);
    
    return () => observer.disconnect();
  }, [forceRender]);

  useEffect(() => {
    if (isVisible) {
      const isAlreadyRendered = 
        renderedScaleRef.current === scale &&
        renderedPageNumRef.current === pageNum &&
        renderedPdfDocRef.current === pdfDoc;
      
      if (!isAlreadyRendered) {
        renderPage();
      }
    } else {
      if (renderTaskRef.current) {
        renderTaskRef.current.cancel();
        renderTaskRef.current = null;
      }
      setLoading(false);
    }
  }, [isVisible, pdfDoc, pageNum, scale]);

  const renderPage = async () => {
    if (!pdfDoc || !canvasRef.current) return;
    setLoading(true);
    setError(false);

    if (renderTaskRef.current) {
      try { renderTaskRef.current.cancel(); } catch(e) {}
      renderTaskRef.current = null;
    }

    try {
      const page = await pdfDoc.getPage(pageNum);
      const viewport = page.getViewport({ scale });
      const canvas = canvasRef.current;
      const context = canvas.getContext('2d');
      if (!context) return;
      canvas.width = viewport.width;
      canvas.height = viewport.height;
      const renderTask = page.render({ canvasContext: context, viewport });
      renderTaskRef.current = renderTask;
      await renderTask.promise;
      renderedScaleRef.current = scale;
      renderedPageNumRef.current = pageNum;
      renderedPdfDocRef.current = pdfDoc;
    } catch (e: any) {
        if (e.name !== 'RenderingCancelledException') {
            console.error('Rendering error', e);
            setError(true);
        }
    } finally {
      setLoading(false);
    }
  };

  const handleCardClick = (e: React.MouseEvent) => {
    // Ignore if clicking sub-elements like buttons/checkboxes directly
    const target = e.target as HTMLElement;
    if (target.closest('button') || target.closest('input[type="checkbox"]')) {
      return;
    }
    if (isSelectionMode) {
      onToggleSelection(pageNum);
    }
  };

  return (
    <div 
      ref={containerRef} 
      onClick={handleCardClick}
      className={`flex flex-col items-center bg-[var(--bg-card)]/40 border cursor-pointer ${isSelected ? 'border-[var(--primary)] bg-[var(--primary-faint)]' : 'border-[var(--border-color)] hover:border-[var(--primary)]/30'} rounded-2xl p-3 shadow-lg relative min-h-[200px] w-full select-none transition-all duration-200 transform-gpu will-change-transform backface-hidden`}
      style={{ transform: 'translate3d(0,0,0)', backfaceVisibility: 'hidden' }}
    >
      <div className="flex justify-between items-center w-full mb-2">
        <div className="flex items-center gap-1.5 ">
            <span className="bg-[var(--primary)] font-black text-white px-2 py-0.5 rounded text-[10px] uppercase tracking-tighter">P{pageNum}</span>
            {isSelectionMode && (
                <input 
                  type="checkbox" 
                  checked={isSelected} 
                  onChange={() => onToggleSelection(pageNum)} 
                  className="accent-[var(--primary)] w-3.5 h-3.5 cursor-pointer leading-none" 
                />
            )}
        </div>
      </div>
      <div className="relative w-full flex justify-center bg-[var(--bg-primary)] rounded-lg overflow-hidden transform-gpu" style={{ transform: 'translate3d(0,0,0)' }}>
        {loading && <div className="absolute inset-0 flex items-center justify-center"><RefreshCw className="w-5 h-5 animate-spin text-[var(--primary)]" /></div>}
        {error && <div className="absolute inset-0 flex items-center justify-center font-bold text-[10px] uppercase text-rose-500">failed</div>}
        <canvas ref={canvasRef} className="max-w-full shadow-inner transform-gpu" style={{ transform: 'translate3d(0,0,0)', willChange: 'transform' }} />
      </div>
    </div>
  );
});

export const PDFPage = React.memo(PDFPageComponent);
