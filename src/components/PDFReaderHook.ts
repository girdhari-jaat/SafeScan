import { useRef, useState, useEffect, useImperativeHandle, useCallback } from 'react';
import { GlobalWorkerOptions, getDocument, version as pdfjsVersion } from 'pdfjs-dist';

// Use standard Vite asset URL resolution for the local worker via ?url suffix
import pdfWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

GlobalWorkerOptions.workerSrc = pdfWorker;

interface UsePDFReaderHookProps {
  onImportPage: (imageBlobs: Blob[]) => void;
  preloadedFile?: File | null;
  onClearPreloadedFile?: () => void;
  ref: any;
}

export function usePDFReaderHook({
  onImportPage,
  preloadedFile,
  onClearPreloadedFile,
  ref,
}: UsePDFReaderHookProps) {
  const [pdfDoc, setPdfDoc] = useState<any>(null);
  const [numPages, setNumPages] = useState<number>(0);
  const [scale, setScale] = useState<number>(1.2);
  const [viewMode, setViewMode] = useState<'scroll' | 'read' | 'grid'>('scroll');
  const [pageNum, setPageNum] = useState<number>(1);
  const [pdfName, setPdfName] = useState<string>('Document.pdf');
  const [isPasswordModalOpen, setIsPasswordModalOpen] = useState(false);
  const [isSelectionMode, setIsSelectionMode] = useState(false);
  const [currentFile, setCurrentFile] = useState<File | null>(null);
  const [selectedPages, setSelectedPages] = useState<Set<number>>(new Set());
  const [isPrinting, setIsPrinting] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const pdfUrlRef = useRef<string | null>(null);
  const pageRefs = useRef<Record<number, any>>({});
  const canvasPoolRef = useRef<HTMLCanvasElement[]>([]);

  useImperativeHandle(ref, () => ({
    triggerBrowse: () => {
      fileInputRef.current?.click();
    },
    triggerReset: () => {
      if (pdfDoc) {
        try {
          pdfDoc.destroy();
        } catch (e) {
          console.warn('Error destroying pdfDoc', e);
        }
      }
      setPdfDoc(null);
      setNumPages(0);
      setPageNum(1);
      setCurrentFile(null);
      if (pdfUrlRef.current) {
        URL.revokeObjectURL(pdfUrlRef.current);
        pdfUrlRef.current = null;
      }
    }
  }));

  useEffect(() => {
    return () => {
      if (pdfUrlRef.current) {
        URL.revokeObjectURL(pdfUrlRef.current);
      }
      if (pdfDoc) {
        try {
          pdfDoc.destroy();
        } catch (e) {
          // ignore
        }
      }
      // Clean up canvas pool
      canvasPoolRef.current.forEach(c => {
        c.width = 0;
        c.height = 0;
      });
      canvasPoolRef.current = [];
    };
  }, [pdfDoc]);

  const loadPDFFile = useCallback(async (file: File, password?: string) => {
    setPdfName(file.name);
    
    if (pdfUrlRef.current) {
      URL.revokeObjectURL(pdfUrlRef.current);
      pdfUrlRef.current = null;
    }

    try {
        const url = URL.createObjectURL(file);
        pdfUrlRef.current = url;
        const doc = await getDocument({ 
          url, 
          password,
          cMapUrl: `https://unpkg.com/pdfjs-dist@${pdfjsVersion || '4.3.136'}/cmaps/`,
          cMapPacked: true,
          standardFontDataUrl: `https://unpkg.com/pdfjs-dist@${pdfjsVersion || '4.3.136'}/standard_fonts/`
        }).promise;
        setPdfDoc(doc);
        setNumPages(doc.numPages);
        setPageNum(1);
        setViewMode('scroll');
        setSelectedPages(new Set());
        setIsPasswordModalOpen(false);
    } catch (err: any) {
        if (err.name === 'PasswordException') {
            setCurrentFile(file);
            setIsPasswordModalOpen(true);
        } else {
             console.error(err);
        }
    }
  }, []);

  useEffect(() => {
    if (preloadedFile) {
      loadPDFFile(preloadedFile);
      onClearPreloadedFile?.();
    }
  }, [preloadedFile, loadPDFFile, onClearPreloadedFile]);

  const togglePageSelection = useCallback((n: number) => {
    setSelectedPages(prev => {
      const next = new Set(prev);
      if (next.has(n)) next.delete(n);
      else {
          next.add(n);
      }
      return next;
    });
  }, []);

  const importPageAsBlob = useCallback(async (n: number): Promise<Blob | null> => {
    if (!pdfDoc) return null;
    // console.log(`Attempting to import page ${n}`);
    try {
      if (pageRefs.current[n]) {
        try {
          const blob = await pageRefs.current[n].exportBlob();
          if (blob) {
            // console.log(`Page ${n} imported via ref`);
            return blob;
          }
        } catch (e) {
          console.warn('Ref-based blob export failed, falling back to offscreen renderer', e);
        }
      }

      // console.log(`Falling back to offscreen renderer for page ${n}`);
      const page = await pdfDoc.getPage(n);
      const viewport = page.getViewport({ scale: 1.5 });
      
      let canvas = canvasPoolRef.current.pop();
      if (!canvas) {
        canvas = document.createElement('canvas');
      }
      
      canvas.width = viewport.width;
      canvas.height = viewport.height;
      const context = canvas.getContext('2d');
      if (!context) {
        canvasPoolRef.current.push(canvas);
        console.error('Could not get canvas context for page', n);
        return null;
      }
      
      context.clearRect(0, 0, canvas.width, canvas.height);

      await page.render({ canvasContext: context, viewport }).promise;

      return new Promise<Blob | null>((resolve) => {
        canvas!.toBlob((blob) => {
          canvas!.width = 0;
          canvas!.height = 0;
          canvasPoolRef.current.push(canvas!);
          if (!blob) console.error(`Failed to convert page ${n} to blob`);
          // else console.log(`Page ${n} imported via offscreen, size: ${blob.size}`);
          resolve(blob);
        }, 'image/png');
      });
    } catch (err) {
      console.error(`Severe failure while rendering page ${n} offscreen:`, err);
      return null;
    }
  }, [pdfDoc]);

  const importSelected = useCallback(async () => {
    const promises = Array.from(selectedPages).map(n => importPageAsBlob(n));
    const blobs = await Promise.all(promises);
    const validBlobs = blobs.filter((b): b is Blob => b !== null);
    
    if (validBlobs.length > 0) {
      onImportPage(validBlobs);
    }
    setSelectedPages(new Set());
  }, [selectedPages, importPageAsBlob, onImportPage]);

  const importPageToScan = useCallback(async (n: number) => {
    const blob = await importPageAsBlob(n);
    if (blob) onImportPage([blob]);
  }, [importPageAsBlob, onImportPage]);

  const handlePrint = useCallback(() => {
    setIsPrinting(true);
    setTimeout(() => {
      window.print();
      setTimeout(() => {
        setIsPrinting(false);
      }, 1000);
    }, 850);
  }, []);

  return {
    pdfDoc,
    numPages,
    setNumPages,
    scale,
    setScale,
    viewMode,
    setViewMode,
    pageNum,
    setPageNum,
    pdfName,
    setPdfName,
    isPasswordModalOpen,
    setIsPasswordModalOpen,
    isSelectionMode,
    setIsSelectionMode,
    currentFile,
    setCurrentFile,
    selectedPages,
    setSelectedPages,
    isPrinting,
    setIsPrinting,
    fileInputRef,
    pdfUrlRef,
    pageRefs,
    togglePageSelection,
    loadPDFFile,
    importSelected,
    importPageToScan,
    handlePrint,
  };
}
