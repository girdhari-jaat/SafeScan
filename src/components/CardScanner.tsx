import React, { useEffect, useRef, useState } from "react";
import {
  Download,
  X,
  ScanLine,
  ImagePlus,
  ChevronDown,
} from "lucide-react";
import {
  useCardScannerHook,
  CardData,
  CardScannerMode,
} from "./CardScannerHook";
import { ExportModal } from "./ExportModal";
import { useCamera } from "../contexts/CameraContext";
import { UnifiedViewfinder } from "./UnifiedViewfinder";
import Crop from "./Crop";
import { getImageBlob } from "../utils/db";

interface CardSlotItemProps {
  index: number;
  label: string;
  isSelected: boolean;
  status: "empty" | "capturing" | "filled";
  onSelect: (index: number) => void;
  gridSlotsRef: React.MutableRefObject<(HTMLDivElement | null)[]>;
  onDelete: (index: number) => void;
  card?: CardData | null;
  onUpload?: (index: number, file: File) => void;
  previewUrl: string | null;
}

const CardSlotItem = React.memo(
  ({
    index,
    label,
    isSelected,
    status,
    onSelect,
    gridSlotsRef,
    onDelete,
    card,
    onUpload,
    previewUrl,
  }: CardSlotItemProps) => {
    const fileInputRef = useRef<HTMLInputElement>(null);

    const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (file && onUpload) {
        onUpload(index, file);
      }
    };

    return (
      <div
        ref={(el) => {
          if (el) gridSlotsRef.current[index] = el;
        }}
        onClick={() => onSelect(index)}
        style={{ aspectRatio: "85.6/53.98" }}
        className={`relative w-full rounded-xl border flex flex-col justify-center items-center overflow-hidden transition-all duration-150 select-none cursor-pointer group ${
          isSelected
            ? "border-[var(--primary)]/50 ring-2 ring-[var(--primary)]/30"
            : "border-dashed border-[var(--border-color)]"
        }`}
      >
        <input
          type="file"
          ref={fileInputRef}
          onChange={handleFileChange}
          className="hidden"
          accept="image/*"
        />

        {status === "filled" && previewUrl ? (
          <img
            src={previewUrl}
            alt={`${label} preview`}
            referrerPolicy="no-referrer"
            className="absolute inset-0 w-full h-full object-cover rounded-xl opacity-90 group-hover:scale-105 transition-transform duration-300"
          />
        ) : status === "filled" && card?.imageId ? (
          <div className="absolute inset-0 bg-[var(--bg-primary)]/40 flex items-center justify-center">
            <div className="w-4 h-4 border-2 border-[var(--primary)] border-t-transparent rounded-full animate-spin"></div>
          </div>
        ) : null}

        {status === "filled" && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onDelete(index);
            }}
            className="absolute top-1.5 right-1.5 z-20 w-6 h-6 rounded-full bg-[var(--bg-overlay)]/80 hover:bg-rose-600 border border-[var(--border-color)] hover:border-transparent flex items-center justify-center text-[var(--text-primary)] hover:text-white transition-all shadow-md cursor-pointer hover:scale-105"
            title="Delete Slot Image"
          >
            <X size={12} className="stroke-[2.5px]" />
          </button>
        )}

        {status === "capturing" && (
          <div className="absolute inset-0 z-10 bg-[var(--bg-overlay)]/70 flex flex-col items-center justify-center gap-1.5 backdrop-blur-[2px]">
            <div className="w-4 h-4 border-2 border-[var(--primary)] border-t-transparent rounded-full animate-spin"></div>
            <span className="text-[10px] text-[var(--primary)] font-extrabold tracking-wider uppercase animate-pulse">
              Capturing
            </span>
          </div>
        )}

        {status === "empty" && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              fileInputRef.current?.click();
            }}
            className="z-10 bg-[var(--bg-card)] border border-[var(--border-color)] hover:bg-[var(--primary)] hover:border-transparent p-3 rounded-full text-[var(--text-secondary)] hover:text-white transition-all scale-95 hover:scale-110 flex items-center justify-center cursor-pointer shadow-lg mb-1.5 active:scale-90"
            title="Import Image"
          >
            <ImagePlus size={18} />
          </button>
        )}

        {status !== "empty" && (
          <div className="text-[var(--text-primary)] font-bold text-xs uppercase flex items-center gap-1 z-15 pointer-events-none mix-blend-difference filter drop-shadow-md">
            <ScanLine size={14} className="text-[var(--primary)] animate-pulse" />{" "}
            <span className="truncate max-w-[80px]">{label}</span>
          </div>
        )}
      </div>
    );
  },
);
CardSlotItem.displayName = "CardSlotItem";

interface CardScannerProps {
  mode: CardScannerMode;
  onClose: () => void;
  documentTitle: string;
  initialPages?: any[];
  onChangeTab?: (tab: "paper" | "idcard" | "grid") => void;
  onAutosave?: (cards: (CardData | null)[]) => Promise<void>;
  onSaveSession?: (cards: (CardData | null)[]) => Promise<void>;
}

export function CardScanner({
  mode,
  onClose,
  documentTitle,
  initialPages,
  onChangeTab,
  onAutosave,
  onSaveSession,
}: CardScannerProps) {
  const [isSlotsVisible, setIsSlotsVisible] = useState(false);

  const {
    slotIndex,
    filledSlots,
    pdfReady,
    cardsRef,
    gridSlotsRef,
    fileInputRef,
    uploadImage,
    executeExport,
    handleSlotClick,
    settings,
    updateSetting,
    updateResolution,
    flashMode,
    hdMode,
    cropCardIndex,
    setCropCardIndex,
    persistSession,
    restoreSession,
    deleteSlot,
    clearAllSlots,
    SLOTS,
    toggleFlash,
    captureFrame,
    isCapturing,
  } = useCardScannerHook({
    mode,
    initialPages,
    onAutosave,
    onSaveSession,
    isSlotsVisible,
  });

  const viewfinderRef = useRef<any>(null);
  const [showExportModal, setShowExportModal] = useState(false);
  const [showRestorePrompt, setShowRestorePrompt] = useState(false);
  const [previewUrls, setPreviewUrls] = useState<(string | null)[]>([]);
  const { startCamera, stopCamera, detectedCorners } = useCamera();

  useEffect(() => {
    const usePhoneCamera = !!settings?.usePhoneCamera;
    const shouldStop = usePhoneCamera || isSlotsVisible || cropCardIndex !== null;
    if (shouldStop) {
      stopCamera();
    } else {
      startCamera();
    }
  }, [startCamera, stopCamera, settings?.usePhoneCamera, isSlotsVisible, cropCardIndex]);

  useEffect(() => {
    const checkSession = async () => {
      const sessionKey = `card_session_${mode}`;
      const saved = localStorage.getItem(sessionKey);
      if (saved) {
        try {
          const parsed = JSON.parse(saved);
          if (Array.isArray(parsed) && parsed.length > 0) {
            setShowRestorePrompt(true);
          }
        } catch (e) {}
      }
    };
    checkSession();
  }, [mode]);

  useEffect(() => {
    let active = true;
    const urls: (string | null)[] = new Array(SLOTS.length).fill(null);
    const revokes: string[] = [];

    const loadPreviews = async () => {
      try {
        const TEMPORARILY_DISABLE_WARP = false;
        if (TEMPORARILY_DISABLE_WARP) {
          for (let i = 0; i < SLOTS.length; i++) {
            const card = cardsRef.current[i];
            if (card && active) {
              const rawBlob = await getImageBlob(card.imageId);
              if (rawBlob && active) {
                const u = URL.createObjectURL(rawBlob);
                urls[i] = u;
                revokes.push(u);
                setPreviewUrls([...urls]);
              }
            }
          }
          return;
        }

        const { processFinalImageOffThread } =
          await import("../utils/imageWorkerClient");

        for (let i = 0; i < SLOTS.length; i++) {
          const card = cardsRef.current[i];
          if (card && active) {
            try {
              const rawBlob = await getImageBlob(card.imageId);
              if (rawBlob && active) {
                const bitmap = await createImageBitmap(rawBlob);
                if (active) {
                  const processedBlob = await processFinalImageOffThread(
                    bitmap,
                    card.corners,
                    card.rotation,
                    card.filter,
                    card.adjustments,
                    "idcard_preview",
                  );
                  if (active) {
                    const u = URL.createObjectURL(processedBlob);
                    urls[i] = u;
                    revokes.push(u);
                    setPreviewUrls([...urls]);
                  } else {
                    bitmap.close();
                  }
                }
              }
            } catch (err) {
              console.error("Failed to generate background warp preview:", err);
            }
          }
        }
      } catch (err) {
        console.error("Failed to load worker API inside CardScanner:", err);
      }
    };

    setPreviewUrls(new Array(SLOTS.length).fill(null));
    loadPreviews();

    return () => {
      active = false;
      revokes.forEach((u) => {
        try {
          URL.revokeObjectURL(u);
        } catch (e) {}
      });
    };
  }, [filledSlots, cropCardIndex]);

  // Do not open slots window automatically after capture as requested
  // useEffect(() => {
  //   // Disabled to prevent slots queue appearing after every capture
  // }, [filledSlots]);

  // Automatically show slots when capture limit is reached
  useEffect(() => {
    const capturedCount = filledSlots.filter(s => s === 'filled').length;
    if ((mode === 'idcard' && capturedCount === 2) || (mode === 'grid' && capturedCount === 8)) {
      setIsSlotsVisible(true);
    }
  }, [filledSlots, mode]);

  const activeCropCard =
    cropCardIndex !== null ? cardsRef.current[cropCardIndex] : null;

  const overlayCanvasRef = useRef<HTMLCanvasElement>(null);
  
  // Synchronize detected boundaries to overlay canvas
  useEffect(() => {
    const canvas = overlayCanvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animFrameId: number;

    const draw = () => {
      const rect = canvas.getBoundingClientRect();
      if (canvas.width !== Math.round(rect.width) || canvas.height !== Math.round(rect.height)) {
        canvas.width = Math.round(rect.width);
        canvas.height = Math.round(rect.height);
      }
      const dtw = canvas.width;
      const dth = canvas.height;
      ctx.clearRect(0, 0, dtw, dth);

      if (detectedCorners && (detectedCorners as any).tl && settings.autoDetectEnabled) {
        const p0 = { x: (detectedCorners.tl.x / 100) * dtw, y: (detectedCorners.tl.y / 100) * dth };
        const p1 = { x: (detectedCorners.tr.x / 100) * dtw, y: (detectedCorners.tr.y / 100) * dth };
        const p2 = { x: (detectedCorners.br.x / 100) * dtw, y: (detectedCorners.br.y / 100) * dth };
        const p3 = { x: (detectedCorners.bl.x / 100) * dtw, y: (detectedCorners.bl.y / 100) * dth };

        ctx.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--primary').trim() || '#10b981';
        ctx.lineWidth = 3;
        
        ctx.beginPath();
        ctx.moveTo(p0.x, p0.y); ctx.lineTo(p1.x, p1.y); ctx.lineTo(p2.x, p2.y); ctx.lineTo(p3.x, p3.y);
        ctx.closePath();
        ctx.fillStyle = `color-mix(in srgb, ${getComputedStyle(document.documentElement).getPropertyValue('--primary').trim() || '#10b981'} 15%, transparent)`;
        ctx.fill();
        ctx.stroke();
        
        [p0, p1, p2, p3].forEach(p => {
          ctx.beginPath();
          ctx.arc(p.x, p.y, 6, 0, 2 * Math.PI);
          ctx.fillStyle = getComputedStyle(document.documentElement).getPropertyValue('--primary').trim() || '#10b981';
          ctx.fill();
        });
      }
      animFrameId = requestAnimationFrame(draw);
    };
    animFrameId = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(animFrameId);
  }, [detectedCorners, settings.autoDetectEnabled]);

  const capturedCount = filledSlots.filter(s => s === 'filled').length;
  let displaySlotLabel = "";
  if (mode === 'idcard') {
    displaySlotLabel = capturedCount % 2 === 0 ? "Front" : "Back";
  } else if (mode === 'grid') {
    const cardNum = Math.floor(capturedCount / 2) + 1;
    displaySlotLabel = capturedCount % 2 === 0 ? `Front ${cardNum}` : `Back ${cardNum}`;
  }

  return (
    <div
      className="w-full h-full flex flex-col relative min-h-0"
      id={`card-scanner-${mode}`}
    >
      <div className="relative flex-1 flex flex-col md:flex-row items-stretch min-h-0">
        <div className="relative flex-1 bg-black flex flex-col items-center justify-center overflow-hidden min-h-0 select-none">

          <input
            type="file"
            ref={fileInputRef}
            className="hidden"
            accept="image/*"
            capture={settings?.usePhoneCamera ? "environment" : undefined}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) {
                uploadImage(slotIndex, file);
                e.target.value = "";
              }
            }}
          />
          <UnifiedViewfinder
            ref={viewfinderRef}
            mode={mode === 'grid' ? 'grid' : 'idcard'}
            aspectRatio={85.6/53.98}
            quality={hdMode as any}
            onClose={onClose}
            flashMode={flashMode}
            onToggleFlash={toggleFlash}
            currentTab={mode}
            onChangeTab={onChangeTab}
            settings={settings}
            onUpdateSetting={updateSetting}
            onUpdateResolution={updateResolution}
            hdMode={hdMode}
            onCaptureClick={captureFrame}
            onFallbackUploadClick={() => fileInputRef.current?.click()}
            isBatchMode={true}
            onBatchToggle={() => {
              if (filledSlots.some((s) => s === "filled")) {
                setIsSlotsVisible(prev => !prev);
              }
            }}
            batchCount={filledSlots.filter((s) => s === "filled").length}
            isCapturing={isCapturing}
            activeSlotLabel={displaySlotLabel}
            hideShutter={isSlotsVisible}
            showGrid={settings?.showGrid}
          />
        </div>

        {/* Slots Sidebar / Bottom bar */}
        <div
          className={`bg-[var(--bg-primary)] flex flex-col z-50 overflow-hidden ${isSlotsVisible ? "fixed md:relative inset-0 md:inset-auto h-full w-full md:w-96 border-[var(--border-color)] md:border-t-0 md:border-l" : "hidden md:flex md:min-w-0 md:w-0 md:h-full shrink-0 border-0"}`}
        >
          {isSlotsVisible && (
            <>
              <div className="flex items-center justify-between px-4 h-12 shrink-0 border-b border-[var(--border-color)]">
                <div className="flex items-center gap-2 overflow-hidden">
                  <ScanLine
                    size={16}
                    className="text-[var(--primary)] shrink-0 animate-pulse"
                  />
                  <span className="text-xs font-black uppercase tracking-widest text-[var(--text-secondary)] truncate transition-all duration-200 animate-in fade-in">
                    Slots Queue
                  </span>
                </div>
                <button
                  onClick={() => setIsSlotsVisible(!isSlotsVisible)}
                  className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-card)] p-1 rounded-lg transition-all"
                  title="Toggle Slots Queue"
                >
                  <ChevronDown className="md:hidden w-4 h-4" />
                  <ChevronDown className="hidden md:block w-4 h-4 rotate-90" />
                </button>
              </div>

              <div className="flex-1 overflow-y-auto p-4 no-scrollbar">
              <div className="grid grid-cols-2 gap-3">
                {SLOTS.map((slot, idx) => (
                  <CardSlotItem
                    key={slot.id}
                    index={idx}
                    label={slot.name}
                    isSelected={slotIndex === idx}
                    status={filledSlots[idx]}
                    onSelect={handleSlotClick}
                    gridSlotsRef={gridSlotsRef}
                    onDelete={deleteSlot}
                    card={cardsRef.current[idx]}
                    onUpload={uploadImage}
                    previewUrl={previewUrls[idx]}
                  />
                ))}
              </div>

              {pdfReady && (
                <button
                  onClick={() => setShowExportModal(true)}
                  className="w-full mt-6 bg-[var(--primary)] hover:bg-[var(--primary-hover)] text-white font-black py-4 rounded-2xl flex items-center justify-center gap-2 shadow-xl shadow-[var(--primary)]/40 active:scale-95 transition-all text-xs uppercase tracking-widest cursor-pointer animate-in fade-in duration-200"
                >
                  <Download size={18} /> Export PDF
                </button>
              )}

              {/* Live A4 Layout Sheet Preview */}
              {filledSlots.some(s => s === 'filled') && mode === 'idcard' && (
                <div className="w-full flex flex-col gap-2.5 mt-8 border-t border-[var(--border-color)] pt-6 animate-in fade-in slide-in-from-bottom-3 duration-350">
                  <div className="flex items-center justify-between w-full">
                    <span className="text-[10px] uppercase font-black tracking-widest text-[var(--primary)]">
                      Print Preview A4 ({mode === 'idcard' ? '1 Card Repeated' : '8 Cards Grid'})
                    </span>
                    <span className="text-[10px] font-mono text-[var(--text-secondary)]">
                      210 x 297 mm
                    </span>
                  </div>
                  <div
                    className="w-full rounded-2xl overflow-hidden border border-[var(--border-color)] bg-[var(--bg-card)] shadow-2xl relative select-none"
                    style={{ aspectRatio: "210/297" }}
                  >
                    {Array.from({ length: 8 }).map((_, i) => {
                      const col = i % 2; 
                      const row = Math.floor(i / 2); 
                      
                      const leftMm = 15.4 + col * (85.6 + 8.0);
                      const topMm = 22.5 + row * (54.0 + 12.0);

                      const leftPercent = (leftMm / 210) * 100;
                      const topPercent = (topMm / 297) * 100;
                      const widthPercent = (85.6 / 210) * 100;
                      const heightPercent = (54.0 / 297) * 100;

                      // For idcard mode, we repeat the first 2 images (front/back) across 4 rows
                      const previewUrl = mode === 'idcard' ? previewUrls[col] : previewUrls[i];
                      if (!previewUrl) return null;

                      return (
                        <div
                          key={i}
                          className="absolute border border-zinc-700 bg-black rounded flex items-center justify-center overflow-hidden"
                          style={{
                            left: `${leftPercent}%`,
                            top: `${topPercent}%`,
                            width: `${widthPercent}%`,
                            height: `${heightPercent}%`,
                          }}
                        >
                          <img
                            src={previewUrl}
                            alt={`Slot ${i}`}
                            className="w-full h-full object-cover rounded"
                          />
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>

      {showExportModal && (
        <ExportModal
          isOpen={showExportModal}
          onClose={() => setShowExportModal(false)}
          defaultTitle={documentTitle}
          onExport={(opts) => executeExport(opts.title, opts.action as any)}
        />
      )}

      {cropCardIndex !== null && activeCropCard && (
        <div className="fixed inset-0 z-[60]">
          <CropWrapper
            card={activeCropCard}
            onSave={async (_blob: Blob, corners: any, rot: number, fil: any, adj: any) => {
              const card = cardsRef.current[cropCardIndex];
              if (card) {
                const updated = {
                  ...card,
                  corners,
                  rotation: rot,
                  filter: fil,
                  adjustments: adj,
                };
                cardsRef.current[cropCardIndex] = updated;
                await persistSession();
              }
              setCropCardIndex(null);
            }}
            onSaveAndNext={(() => {
              const nextIndex = filledSlots.slice(cropCardIndex + 1).findIndex(s => s === 'filled');
              if (nextIndex === -1) return undefined;
              
              return async (_blob: Blob, corners: any, rot: number, fil: any, adj: any) => {
                // Save current
                const currentCard = cardsRef.current[cropCardIndex];
                if (currentCard) {
                  const updated = {
                    ...currentCard,
                    corners,
                    rotation: rot,
                    filter: fil,
                    adjustments: adj,
                  };
                  cardsRef.current[cropCardIndex] = updated;
                  await persistSession();
                }
                
                // Jump to next absolute index
                const absoluteNext = cropCardIndex + 1 + nextIndex;
                setCropCardIndex(absoluteNext);
              };
            })()}
            onCancel={() => setCropCardIndex(null)}
          />
        </div>
      )}
      {showRestorePrompt && (
        <div className="fixed inset-0 z-[100] bg-[var(--bg-overlay)] backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[var(--bg-card)] border border-[var(--border-color)] rounded-2xl w-full max-w-sm p-6 flex flex-col gap-5 text-center shadow-2xl relative">
            <h3 className="text-lg font-bold text-[var(--text-primary)] tracking-tight">Recover Saved Session?</h3>
            <p className="text-xs text-[var(--text-secondary)] leading-relaxed">
              We found images from your previous scanning session. Would you like to restore them and continue editing?
            </p>
            <div className="flex gap-3 mt-2">
              <button
                type="button"
                onClick={async () => {
                  setShowRestorePrompt(false);
                  await restoreSession();
                }}
                className="flex-1 py-3 bg-[var(--primary)] hover:bg-[var(--primary-hover)] text-white font-extrabold rounded-xl transition duration-150 cursor-pointer text-xs"
              >
                Yes, Restore
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowRestorePrompt(false);
                  clearAllSlots();
                }}
                className="flex-1 py-3 bg-[var(--bg-card)] hover:bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-secondary)] font-bold rounded-xl transition duration-150 cursor-pointer text-xs"
              >
                No, Start Fresh
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function CropWrapper({
  card,
  onSave,
  onSaveAndNext,
  onCancel,
}: {
  card: CardData;
  onSave: any;
  onSaveAndNext?: any;
  onCancel: any;
}) {
  const [blob, setBlob] = useState<Blob | null>(null);
  useEffect(() => {
    getImageBlob(card.imageId).then(setBlob);
  }, [card.imageId]);

  if (!blob) return null;

  return (
    <Crop
      imageSrc={blob}
      initialCorners={card.corners}
      initialRotation={card.rotation}
      initialFilter={card.filter}
      initialAdjustments={card.adjustments}
      onSave={onSave}
      onSaveAndNext={onSaveAndNext}
      onCancel={onCancel}
      sourceType="idcard"
    />
  );
}
