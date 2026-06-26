import { Settings, X, Share2, Download } from 'lucide-react';
import { useExportModal } from './ExportModalHook';

interface ExportModalProps {
  isOpen: boolean;
  onClose: () => void;
  onExport: (options: { 
    title: string; 
    pageSize: 'a4' | 'letter' | 'fit'; 
    orientation: 'portrait' | 'landscape' | 'auto'; 
    quality: number; 
    password?: string;
    action?: 'download' | 'share';
  }) => void;
  defaultTitle: string;
  disabledOrientations?: ('auto' | 'portrait' | 'landscape')[];
}

export function ExportModal({ isOpen, onClose, onExport, defaultTitle, disabledOrientations = [] }: ExportModalProps) {
  const {
    title,
    setTitle,
    password,
    setPassword,
    pageSize,
    setPageSize,
    orientation,
    setOrientation,
    quality,
    setQuality
  } = useExportModal({ defaultTitle });

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center p-4 backdrop-blur-md bg-[var(--bg-overlay)]">
      <div className="bg-[var(--bg-card)] border border-[var(--border-color)] p-6 rounded-2xl w-full max-w-sm flex flex-col gap-4 shadow-2xl text-[var(--text-primary)]">
        <div className="flex justify-between items-center">
            <label className="text-[var(--text-primary)] font-bold text-sm flex items-center gap-2"><Settings size={16} className="text-[var(--primary)]" /> Build PDF Parameters</label>
            <button onClick={onClose} className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] cursor-pointer"><X size={16} /></button>
        </div>

        <div className="flex flex-col gap-1">
            <label className="text-[var(--text-secondary)] text-[10px] uppercase font-bold">FILE EXPORT NAME</label>
            <input value={title} onChange={(e) => setTitle(e.target.value)} className="w-full bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-lg p-2 text-[var(--text-primary)] text-xs outline-none focus:border-[var(--primary)]" />
        </div>

        <div className="flex flex-col gap-1">
            <label className="text-[var(--text-secondary)] text-[10px] uppercase font-bold">PASSWORD (OPTIONAL)</label>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Set password..." className="w-full bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-lg p-2 text-[var(--text-primary)] text-xs outline-none focus:border-[var(--primary)]" />
        </div>

        <div className="flex flex-col gap-1.5">
            <label className="text-[var(--text-secondary)] text-[10px] uppercase font-extrabold tracking-widest pl-1">Page Layout Format</label>
            <div className="grid grid-cols-3 gap-2 bg-[var(--bg-primary)] p-1 rounded-2xl border border-[var(--border-color)]">
                {(['a4', 'letter', 'fit'] as const).map((size) => (
                    <button 
                      key={size} 
                      onClick={() => setPageSize(size)} 
                      className={`px-2 py-2 rounded-xl text-[11px] font-black transition-all duration-200 cursor-pointer ${
                        pageSize === size 
                          ? 'bg-[var(--primary)] text-white shadow-lg shadow-[var(--primary)]/20 scale-[1.02]' 
                          : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-card)]'
                      }`}
                    >
                      {size === 'fit' ? 'Original' : size.toUpperCase()}
                    </button>
                ))}
            </div>
        </div>

        <div className="flex flex-col gap-1.5">
            <label className="text-[var(--text-secondary)] text-[10px] uppercase font-extrabold tracking-widest pl-1">Format Orientation</label>
            <div className="grid grid-cols-3 gap-2 bg-[var(--bg-primary)] p-1 rounded-2xl border border-[var(--border-color)]">
                {(['auto', 'portrait', 'landscape'] as const)
                  .filter(o => !disabledOrientations.includes(o))
                  .map((orient) => (
                    <button 
                      key={orient} 
                      onClick={() => setOrientation(orient)} 
                      className={`px-2 py-2 rounded-xl text-[11px] font-black transition-all duration-200 cursor-pointer ${
                        orientation === orient 
                          ? 'bg-[var(--primary)] text-white shadow-lg shadow-[var(--primary)]/20 scale-[1.02]' 
                          : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-card)]'
                      }`}
                    >
                      {orient.toUpperCase()}
                    </button>
                ))}
            </div>
        </div>

        <div className="flex flex-col gap-1">
            <div className='flex justify-between'>
                <label className="text-[var(--text-secondary)] text-[10px] uppercase font-bold">Compression Efficiency Quality</label>
                <span className='text-[var(--primary)] text-xs font-bold'>{Math.round(quality * 100)}%</span>
            </div>
            <input type="range" min="0.1" max="1" step="0.1" value={quality} onChange={(e) => setQuality(parseFloat(e.target.value))} className="w-full h-1.5 rounded-lg appearance-none cursor-pointer outline-none bg-[var(--border-color)] [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:h-[16px] [&::-webkit-slider-thumb]:w-[16px] [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-[var(--primary)]" />
        </div>

        <div className="flex gap-2.5 mt-2">
          <button 
            type="button"
            onClick={() => onExport({ title, password, pageSize, orientation, quality, action: 'share' })} 
            className="flex-1 bg-[var(--bg-primary)] hover:bg-[var(--bg-card)] border border-[var(--border-color)] px-3 py-3 rounded-lg text-[var(--text-primary)] font-bold text-xs cursor-pointer flex items-center justify-center gap-1 w-1/2 transition-all active:scale-95 duration-100"
            id="export-share-btn"
          >
            <Share2 size={13} className="text-[var(--primary)] stroke-[2.5]" />
            <span>SHARE</span>
          </button>
          <button 
            type="button"
            onClick={() => onExport({ title, password, pageSize, orientation, quality, action: 'download' })} 
            className="flex-1 bg-[var(--primary)] hover:bg-[var(--primary-hover)] text-white font-bold px-3 py-3 rounded-lg text-xs cursor-pointer flex items-center justify-center gap-1 w-1/2 transition-all active:scale-95 duration-100"
            id="export-download-btn"
          >
            <Download size={13} className="stroke-[2.5]" />
            <span>DOWNLOAD</span>
          </button>
        </div>
      </div>
    </div>
  );
}
