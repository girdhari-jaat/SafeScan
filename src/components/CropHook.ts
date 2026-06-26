// AUDITED: Fixed canvas leaks and removed unused exports
import React, { useState, useEffect, useRef } from "react";
import {
  PageCorners,
  ScanFilterType,
  PageAdjustments,
  Vector2D,
} from "../types";
import { CARD_RATIOS } from "../constants";
import { orderPoints } from "../utils/edge/geometry";
import { addLog } from "../utils/renderStats";
import { useSharedSettings } from "../lib/useSharedSettings";

interface UseCropHookProps {
  imageSrc: string | Blob;
  initialCorners?: PageCorners;
  initialRotation?: number;
  initialFilter?: ScanFilterType;
  initialAdjustments?: PageAdjustments;
  onSave: (
    finalBlob: Blob,
    corners: PageCorners,
    rotation: number,
    filter: ScanFilterType,
    adjustments: PageAdjustments,
  ) => void;
  onSaveAndNext?: (
    finalBlob: Blob,
    corners: PageCorners,
    rotation: number,
    filter: ScanFilterType,
    adjustments: PageAdjustments,
  ) => void;
  onCropChange?: (newCorners: PageCorners) => void;
  sourceType?: string;
}

export function useCropHook({
  imageSrc,
  initialCorners,
  initialRotation = 0,
  initialFilter = "original",
  initialAdjustments = {
    brightness: 0,
    contrast: 0,
    saturation: 0,
    sharpness: 0,
    shadows: 0,
    temperature: 0,
  },
  onSave,
  onSaveAndNext,
  onCropChange,
  sourceType,
}: UseCropHookProps) {
  const { settings } = useSharedSettings();
  const [imgUrl, setImgUrl] = useState<string>("");
  const [loading, setLoading] = useState<boolean>(true);
  const [isProcessing, setIsProcessing] = useState<boolean>(false);
  const [isAutoDetecting, setIsAutoDetecting] = useState<boolean>(false);
  const [showFlash, setShowFlash] = useState<boolean>(false);
  const [toast, setToast] = useState<string | null>(null);

  const workerAPIRef = useRef<any>(null);
  const [workerReady, setWorkerReady] = useState<boolean>(false);

  useEffect(() => {
    // Pre-load worker API for zero-latency capture
    import("../utils/imageWorkerClient").then((mod) => {
      workerAPIRef.current = mod;
      mod.initWorker();
      setWorkerReady(true);
    });
  }, []);

  const triggerLocalToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => {
      setToast((current) => (current === msg ? null : current));
    }, 3000);
  };

  const [corners, setCorners] = useState<PageCorners>(() => {
    if (initialCorners) return { ...initialCorners };
    return {
      tl: { x: 5, y: 5 },
      tr: { x: 95, y: 5 },
      br: { x: 95, y: 95 },
      bl: { x: 5, y: 95 },
    };
  });
  const [rotation, setRotation] = useState<number>(initialRotation);
  const [scale, setScale] = useState<number>(1);
  const [adjustmentsState, setAdjustmentsState] = useState<PageAdjustments>({
    ...initialAdjustments,
  });
  const adjustments = adjustmentsState;
  const adjustTimeoutRef = useRef<any>(null);

  const setAdjustments = (
    adj: PageAdjustments | ((prev: PageAdjustments) => PageAdjustments),
  ) => {
    if (adjustTimeoutRef.current) clearTimeout(adjustTimeoutRef.current);
    adjustTimeoutRef.current = setTimeout(() => {
      setAdjustmentsState(adj);
    }, 80);
  };

  const filterTimeoutRef = useRef<any>(null);
  const setFilter = (
    f: ScanFilterType | ((prev: ScanFilterType) => ScanFilterType),
  ) => {
    if (filterTimeoutRef.current) clearTimeout(filterTimeoutRef.current);
    filterTimeoutRef.current = setTimeout(() => {
      setFilterState(f);
    }, 80);
  };

  const [filterState, setFilterState] = useState<ScanFilterType>(initialFilter);
  const filter = filterState;

  const [activeTab, setActiveTab] = useState<
    "crop" | "filter" | "adjust" | "ai"
  >("crop");

  // Multi-render prevention: store corners in useRef during dragging
  const cornersRef = useRef<PageCorners>({ ...corners });

  // Keep cornersRef in sync with programmatic updates, rotation, reset
  useEffect(() => {
    cornersRef.current = corners;
  }, [corners]);

  // DOM Refs for direct handle updates to completely bypass React state triggers during dragging
  const tlDOMRef = useRef<HTMLDivElement>(null);
  const trDOMRef = useRef<HTMLDivElement>(null);
  const brDOMRef = useRef<HTMLDivElement>(null);
  const blDOMRef = useRef<HTMLDivElement>(null);
  const tDOMRef = useRef<HTMLDivElement>(null);
  const bDOMRef = useRef<HTMLDivElement>(null);
  const lDOMRef = useRef<HTMLDivElement>(null);
  const rDOMRef = useRef<HTMLDivElement>(null);
  const tlHalfDOMRef = useRef<HTMLDivElement>(null);
  const trHalfDOMRef = useRef<HTMLDivElement>(null);
  const blHalfDOMRef = useRef<HTMLDivElement>(null);
  const brHalfDOMRef = useRef<HTMLDivElement>(null);
  const ltHalfDOMRef = useRef<HTMLDivElement>(null);
  const lbHalfDOMRef = useRef<HTMLDivElement>(null);
  const rtHalfDOMRef = useRef<HTMLDivElement>(null);
  const rbHalfDOMRef = useRef<HTMLDivElement>(null);
  const polygonDOMRef = useRef<SVGPolygonElement>(null);
  const overlayPathDOMRef = useRef<SVGPathElement>(null);
  const zoomCircleDOMRef = useRef<HTMLDivElement>(null);

  const updateDOMFromCorners = (c: PageCorners) => {
    if (tlDOMRef.current) {
      tlDOMRef.current.style.left = `${c.tl.x}%`;
      tlDOMRef.current.style.top = `${c.tl.y}%`;
    }
    if (trDOMRef.current) {
      trDOMRef.current.style.left = `${c.tr.x}%`;
      trDOMRef.current.style.top = `${c.tr.y}%`;
    }
    if (brDOMRef.current) {
      brDOMRef.current.style.left = `${c.br.x}%`;
      brDOMRef.current.style.top = `${c.br.y}%`;
    }
    if (blDOMRef.current) {
      blDOMRef.current.style.left = `${c.bl.x}%`;
      blDOMRef.current.style.top = `${c.bl.y}%`;
    }

    // Medians (t, b, l, r)
    if (tDOMRef.current) {
      tDOMRef.current.style.left = `${(c.tl.x + c.tr.x) / 2}%`;
      tDOMRef.current.style.top = `${(c.tl.y + c.tr.y) / 2}%`;
    }
    if (bDOMRef.current) {
      bDOMRef.current.style.left = `${(c.bl.x + c.br.x) / 2}%`;
      bDOMRef.current.style.top = `${(c.bl.y + c.br.y) / 2}%`;
    }
    if (lDOMRef.current) {
      lDOMRef.current.style.left = `${(c.tl.x + c.bl.x) / 2}%`;
      lDOMRef.current.style.top = `${(c.tl.y + c.bl.y) / 2}%`;
    }
    if (rDOMRef.current) {
      rDOMRef.current.style.left = `${(c.tr.x + c.br.x) / 2}%`;
      rDOMRef.current.style.top = `${(c.tr.y + c.br.y) / 2}%`;
    }

    // Half-edges Proportionates
    if (tlHalfDOMRef.current) {
      tlHalfDOMRef.current.style.left = `${c.tl.x * 0.75 + c.tr.x * 0.25}%`;
      tlHalfDOMRef.current.style.top = `${c.tl.y * 0.75 + c.tr.y * 0.25}%`;
    }
    if (trHalfDOMRef.current) {
      trHalfDOMRef.current.style.left = `${c.tl.x * 0.25 + c.tr.x * 0.75}%`;
      trHalfDOMRef.current.style.top = `${c.tl.y * 0.25 + c.tr.y * 0.75}%`;
    }
    if (blHalfDOMRef.current) {
      blHalfDOMRef.current.style.left = `${c.bl.x * 0.75 + c.br.x * 0.25}%`;
      blHalfDOMRef.current.style.top = `${c.bl.y * 0.75 + c.br.y * 0.25}%`;
    }
    if (brHalfDOMRef.current) {
      brHalfDOMRef.current.style.left = `${c.bl.x * 0.25 + c.br.x * 0.75}%`;
      brHalfDOMRef.current.style.top = `${c.bl.y * 0.25 + c.br.y * 0.75}%`;
    }
    if (ltHalfDOMRef.current) {
      ltHalfDOMRef.current.style.left = `${c.tl.x * 0.75 + c.bl.x * 0.25}%`;
      ltHalfDOMRef.current.style.top = `${c.tl.y * 0.75 + c.bl.y * 0.25}%`;
    }
    if (lbHalfDOMRef.current) {
      lbHalfDOMRef.current.style.left = `${c.tl.x * 0.25 + c.bl.x * 0.75}%`;
      lbHalfDOMRef.current.style.top = `${c.tl.y * 0.25 + c.bl.y * 0.75}%`;
    }
    if (rtHalfDOMRef.current) {
      rtHalfDOMRef.current.style.left = `${c.tr.x * 0.75 + c.br.x * 0.25}%`;
      rtHalfDOMRef.current.style.top = `${c.tr.y * 0.75 + c.br.y * 0.25}%`;
    }
    if (rbHalfDOMRef.current) {
      rbHalfDOMRef.current.style.left = `${c.tr.x * 0.25 + c.br.x * 0.75}%`;
      rbHalfDOMRef.current.style.top = `${c.tr.y * 0.25 + c.br.y * 0.75}%`;
    }

    if (imageRef.current) {
      const w = imageRef.current.clientWidth || 100;
      const h = imageRef.current.clientHeight || 100;
      const abs_loc = {
        tl: { x: (c.tl.x / 100) * w, y: (c.tl.y / 100) * h },
        tr: { x: (c.tr.x / 100) * w, y: (c.tr.y / 100) * h },
        br: { x: (c.br.x / 100) * w, y: (c.br.y / 100) * h },
        bl: { x: (c.bl.x / 100) * w, y: (c.bl.y / 100) * h },
      };

      if (polygonDOMRef.current) {
        polygonDOMRef.current.setAttribute(
          "points",
          `${abs_loc.tl.x},${abs_loc.tl.y} ${abs_loc.tr.x},${abs_loc.tr.y} ${abs_loc.br.x},${abs_loc.br.y} ${abs_loc.bl.x},${abs_loc.bl.y}`,
        );
      }

      if (overlayPathDOMRef.current) {
        const d = `M 0,0 L ${w},0 L ${w},${h} L 0,${h} Z M ${abs_loc.tl.x},${abs_loc.tl.y} L ${abs_loc.tr.x},${abs_loc.tr.y} L ${abs_loc.br.x},${abs_loc.br.y} L ${abs_loc.bl.x},${abs_loc.bl.y} Z`;
        overlayPathDOMRef.current.setAttribute("d", d);
      }
    }
  };

  const [activeHandle, setActiveHandle] = useState<
    | "tl"
    | "tr"
    | "br"
    | "bl"
    | "t"
    | "r"
    | "b"
    | "l"
    | "tl_half"
    | "tr_half"
    | "bl_half"
    | "br_half"
    | "lt_half"
    | "lb_half"
    | "rt_half"
    | "rb_half"
    | null
  >(null);
  const [initialDragCorners, setInitialDragCorners] =
    useState<PageCorners | null>(null);
  const [initialDragPointer, setInitialDragPointer] = useState<Vector2D | null>(
    null,
  );
  const snapStateRef = useRef<{
    snappedX: "left" | "right" | null;
    snappedY: "top" | "bottom" | null;
    nearBorderX: "left" | "right" | null;
    nearBorderY: "top" | "bottom" | null;
    nearBorderStartX: number | null;
    nearBorderStartY: number | null;
  }>({
    snappedX: null,
    snappedY: null,
    nearBorderX: null,
    nearBorderY: null,
    nearBorderStartX: null,
    nearBorderStartY: null,
  });

  const containerRef = useRef<HTMLDivElement>(null);
  const imageRef = useRef<HTMLCanvasElement>(null);
  const hasLoadedInitialRef = useRef<boolean>(false);
  const [imgLoaded, setImgLoaded] = useState<boolean>(false);
  const [naturalAspect, setNaturalAspect] = useState<number>(3 / 4);

  useEffect(() => {
    if (!imgUrl) return;

    // Use Zero-Copy ImageBitmap to get aspect ratio without main-thread decompression
    const fetchDim = async () => {
      try {
        let blob: Blob;
        if (imageSrc instanceof Blob) {
          blob = imageSrc;
        } else {
          const response = await fetch(imgUrl);
          blob = await response.blob();
        }
        const bitmap = await createImageBitmap(blob);
        const w = bitmap.width || 800;
        const h = bitmap.height || 1000;
        setNaturalAspect(w / h);
        bitmap.close(); // Immediate cleanup
      } catch (e) {
        console.error("Failed to load dim via Zero-Copy:", e);
      }
    };
    fetchDim();
  }, [imgUrl]);

  const [overlayStyle, setOverlayStyle] = useState<React.CSSProperties>({
    left: 0,
    top: 0,
    width: "100%",
    height: "100%",
  });

  const imageDimensionsRef = useRef({
    w: 0,
    h: 0,
    overlayLeft: 0,
    overlayTop: 0,
  });

  const updateDimensions = () => {
    if (imageRef.current && imageRef.current.parentElement) {
      const imgW = imageRef.current.clientWidth;
      const imgH = imageRef.current.clientHeight;
      const parentW = imageRef.current.parentElement.clientWidth || imgW;
      const parentH = imageRef.current.parentElement.clientHeight || imgH;

      const overlayLeft = Math.max(0, (parentW - imgW) / 2);
      const overlayTop = Math.max(0, (parentH - imgH) / 2);

      imageDimensionsRef.current = {
        w: imgW,
        h: imgH,
        overlayLeft,
        overlayTop,
      };

      setOverlayStyle({
        left: `${overlayLeft}px`,
        top: `${overlayTop}px`,
        width: `${imgW}px`,
        height: `${imgH}px`,
      });

      // Recalculate absolute SVG crop mask coordinates live on resize / reload
      requestAnimationFrame(() => {
        updateDOMFromCorners(cornersRef.current);
      });
    }
  };

  const handleImageLoad = () => {
    setImgLoaded(true);
    updateDimensions();
  };

  useEffect(() => {
    if (loading) return;

    updateDimensions();
    const id = requestAnimationFrame(updateDimensions);

    window.addEventListener("resize", updateDimensions);
    return () => {
      cancelAnimationFrame(id);
      window.removeEventListener("resize", updateDimensions);
    };
  }, [rotation, loading, activeTab, imgLoaded]);

  useEffect(() => {
    if (typeof imageSrc === "string") {
      setImgUrl(imageSrc);
      setLoading(false);
    } else if (imageSrc instanceof Blob) {
      const url = URL.createObjectURL(imageSrc);
      setImgUrl(url);
      setLoading(false);
      return () => {
        URL.revokeObjectURL(url);
      };
    }
    hasLoadedInitialRef.current = false;
    setImgLoaded(false);

    // Reset parameters to respective initial page props to avoid state carry-over/collision
    setRotation(initialRotation);
    setScale(1);
    setFilterState(initialFilter);
    setAdjustmentsState({ ...initialAdjustments });
  }, [
    imageSrc,
    initialRotation,
    initialFilter,
    JSON.stringify(initialAdjustments),
  ]);

  useEffect(() => {
    if (!imgUrl) return;
    if (hasLoadedInitialRef.current) return;

    if (initialCorners) {
      setCorners({ ...initialCorners });
      hasLoadedInitialRef.current = true;
      return;
    }

    const loadDefault = async () => {
      try {
        let blob: Blob;
        if (imageSrc instanceof Blob) {
          blob = imageSrc;
        } else {
          const response = await fetch(imgUrl);
          blob = await response.blob();
        }
        const bitmap = await createImageBitmap(blob);
        const imgW = bitmap.width || 800;
        const imgH = bitmap.height || 1000;
        const isLandscape = imgW > imgH;
        const imgRatio = imgW / imgH;

        const isCardMode = !!(
          sourceType?.includes("idcard") ||
          sourceType?.includes("cnic") ||
          sourceType?.includes("slot") ||
          sourceType?.includes("grid")
        );

        let targetRatio = imgRatio; // For 'paper' mode, auto-adapt EXACTLY to the actual image aspect ratio!
        if (isCardMode) {
          targetRatio = isLandscape
            ? CARD_RATIOS.LANDSCAPE
            : 1 / CARD_RATIOS.LANDSCAPE; // ID-1 Card Standard ratio
        }

        let targetWPercent = 85;
        let targetHPercent = 85;

        if (isCardMode) {
          if (imgRatio > targetRatio) {
            targetHPercent = 55;
            targetWPercent = targetHPercent * (targetRatio / imgRatio);
          } else {
            targetWPercent = 65;
            targetHPercent = targetWPercent * (imgRatio / targetRatio);
          }
        } else {
          // Paper / manual crop mode: Perfect proportional 85% centered crop bounds matching actual image aspect ratio
          targetWPercent = 85;
          targetHPercent = 85;
        }

        targetWPercent = Math.min(95, Math.max(10, targetWPercent));
        targetHPercent = Math.min(95, Math.max(10, targetHPercent));

        const x1 = Math.round(50 - targetWPercent / 2);
        const x2 = Math.round(50 + targetWPercent / 2);
        const y1 = Math.round(50 - targetHPercent / 2);
        const y2 = Math.round(50 + targetHPercent / 2);

        setCorners({
          tl: { x: x1, y: y1 },
          tr: { x: x2, y: y1 },
          br: { x: x2, y: y2 },
          bl: { x: x1, y: y2 },
        });
        hasLoadedInitialRef.current = true;
        bitmap.close();
      } catch (e) {
        console.error("Default corner calc fail:", e);
      }
    };
    loadDefault();
  }, [imgUrl, sourceType, initialCorners]);

  const handleRotate = React.useCallback(() => {
    const nextRotation = (rotation + 90) % 360;
    setRotation(nextRotation);
    addLog(`Rotated to ${nextRotation} degrees`);
  }, [rotation]);

  const handleResetCrop = React.useCallback(() => {
    setCorners({
      tl: { x: 0, y: 0 },
      tr: { x: 100, y: 0 },
      br: { x: 100, y: 100 },
      bl: { x: 0, y: 100 },
    });
  }, []);

  const hasAutoDetectedRef = useRef<boolean>(false);

  useEffect(() => {
    hasAutoDetectedRef.current = false;
  }, [imgUrl]);

  useEffect(() => {
    if (
      imgUrl &&
      hasLoadedInitialRef.current &&
      settings.autoDetectEnabled &&
      !initialCorners
    ) {
      if (!hasAutoDetectedRef.current && !isAutoDetecting && workerReady) {
        hasAutoDetectedRef.current = true;
        handleAutoDetect(false);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    imgUrl,
    settings.autoDetectEnabled,
    initialCorners,
    isAutoDetecting,
    workerReady,
  ]);

  const handleAutoDetect = async (useGemini: boolean = false) => {
    if (settings.offlineMode) {
      useGemini = false;
    }
    if (isAutoDetecting) return;
    setIsAutoDetecting(true);
    triggerLocalToast(
      useGemini ? "AI detecting borders..." : "Analyzing image bounds...",
    );

    await new Promise((resolve) => requestAnimationFrame(resolve));

    try {
      // OPTIMIZATION: Use Zero-Copy branch for autodetect - absolutely no main-thread canvas draws!
      let blob: Blob;
      if (imageSrc instanceof Blob) {
        blob = imageSrc;
      } else {
        const response = await fetch(imgUrl);
        blob = await response.blob();
      }
      const bitmapData = await createImageBitmap(blob);
      addLog(
        useGemini
          ? "AI detecting image bounds..."
          : "Autodetecting image bounds...",
      );

      // CPU offload: perform high-precision line detection inside background worker
      const workerAPI = workerAPIRef.current;
      if (!workerAPI) throw new Error("Worker API not ready");

      // ROTATE BITMAP DATA matching currently selected visual rotation
      let processedBitmap = bitmapData;
      if (rotation !== 0) {
        const canvas = document.createElement("canvas");
        const isSwapped = rotation === 90 || rotation === 270;
        canvas.width = isSwapped ? bitmapData.height : bitmapData.width;
        canvas.height = isSwapped ? bitmapData.width : bitmapData.height;

        const ctx = canvas.getContext("2d");
        if (ctx) {
          ctx.imageSmoothingEnabled = true;
          ctx.imageSmoothingQuality = "high";
          ctx.translate(canvas.width / 2, canvas.height / 2);
          ctx.rotate((rotation * Math.PI) / 180);
          ctx.drawImage(
            bitmapData,
            -bitmapData.width / 2,
            -bitmapData.height / 2,
          );
          processedBitmap = await createImageBitmap(canvas);
          bitmapData.close(); // Immediate memory release
        }
      }

      const isCardModeOnDetect = !!(
        sourceType?.includes("idcard") ||
        sourceType?.includes("cnic") ||
        sourceType?.includes("slot") ||
        sourceType?.includes("grid")
      );
      const scanMode = isCardModeOnDetect ? "manual_cnic" : "manual_a4";
      const result = await workerAPI.detectCornersOffThread(
        processedBitmap,
        scanMode as any,
        !useGemini,
      );

      if (result && result.corners && result.corners.length === 4) {
        addLog("Corners detected successfully");
        const cornersFound = result.corners;
        const finalW = result.originalWidth;
        const finalH = result.originalHeight;

        // 1. Get relative percentage coordinates [0, 1] relative to the processed oriented image
        let relCorners = cornersFound.map((c) => ({
          x: c.x / finalW,
          y: c.y / finalH,
        }));

        // 2. UNROTATE percentage coordinates back to the original unrotated coordinates space
        if (rotation === 90) {
          relCorners = relCorners.map((c) => ({
            x: c.y,
            y: 1 - c.x,
          }));
        } else if (rotation === 180) {
          relCorners = relCorners.map((c) => ({
            x: 1 - c.x,
            y: 1 - c.y,
          }));
        } else if (rotation === 270) {
          relCorners = relCorners.map((c) => ({
            x: 1 - c.y,
            y: c.x,
          }));
        }

        // 3. Sort corners clockwise (TL, TR, BR, BL) in the unrotated canvas space
        const orderedRel = orderPoints(relCorners);

        setCorners({
          tl: { x: orderedRel[0].x * 100, y: orderedRel[0].y * 100 },
          tr: { x: orderedRel[1].x * 100, y: orderedRel[1].y * 100 },
          br: { x: orderedRel[2].x * 100, y: orderedRel[2].y * 100 },
          bl: { x: orderedRel[3].x * 100, y: orderedRel[3].y * 100 },
        });

        if (useGemini) {
          if (result.aiSucceeded) {
            triggerLocalToast("Gemini detected bounds!");
          } else {
            const errStr = (result.aiErrorMsg || "").toLowerCase();
            if (
              errStr.includes("429") ||
              errStr.includes("quota") ||
              errStr.includes("limit")
            ) {
              triggerLocalToast("Gemini quota limit. Used local engine!");
            } else {
              triggerLocalToast("Gemini offline. Used local engine!");
            }
          }
        } else {
          triggerLocalToast("Bounds detected!");
        }

        setShowFlash(true);
        setTimeout(() => setShowFlash(false), 1200);
      } else {
        addLog("Autodetect fallback (snug fit)");
        // SMART FALLBACK: If image is already closely cropped, fall back to a snug 3% margin fit
        setCorners({
          tl: { x: 3, y: 3 },
          tr: { x: 97, y: 3 },
          br: { x: 97, y: 97 },
          bl: { x: 3, y: 97 },
        });

        if (useGemini && !result?.aiSucceeded) {
          const errStr = (result?.aiErrorMsg || "").toLowerCase();
          if (
            errStr.includes("429") ||
            errStr.includes("quota") ||
            errStr.includes("limit")
          ) {
            triggerLocalToast("Quota limit. Clean borders fitted!");
          } else {
            triggerLocalToast("Gemini offline. Clean borders fitted!");
          }
        } else {
          triggerLocalToast("Clean borders auto-fitted!");
        }

        setShowFlash(true);
        setTimeout(() => setShowFlash(false), 1200);
      }
    } catch (err) {
      addLog(`Autodetect failed: ${err}`);
      console.error("Auto detect failed (Zero-Copy):", err);
      // Even on exception, do the smart fallback to snug borders
      setCorners({
        tl: { x: 3, y: 3 },
        tr: { x: 97, y: 3 },
        br: { x: 97, y: 97 },
        bl: { x: 3, y: 97 },
      });
      triggerLocalToast("Clean borders auto-fitted!");
    } finally {
      setIsAutoDetecting(false);
    }
  };

  const getLocalCoords = (clientX: number, clientY: number): Vector2D => {
    if (!imageRef.current) return { x: 0, y: 0 };
    const rect = imageRef.current.getBoundingClientRect();
    const centerX = rect.left + rect.width / 2;
    const centerY = rect.top + rect.height / 2;

    const dx = (clientX - centerX) / scale;
    const dy = (clientY - centerY) / scale;

    const rad = (-rotation * Math.PI) / 180;
    const rx = dx * Math.cos(rad) - dy * Math.sin(rad);
    const ry = dx * Math.sin(rad) + dy * Math.cos(rad);

    const uw = imageRef.current.clientWidth || 1;
    const uh = imageRef.current.clientHeight || 1;

    const px = ((rx + uw / 2) / uw) * 100;
    const py = ((ry + uh / 2) / uh) * 100;

    const cx = Math.max(0, Math.min(100, px));
    const cy = Math.max(0, Math.min(100, py));

    return { x: cx, y: cy };
  };

  const handlePointerDown = (
    corner:
      | "tl"
      | "tr"
      | "br"
      | "bl"
      | "t"
      | "r"
      | "b"
      | "l"
      | "tl_half"
      | "tr_half"
      | "bl_half"
      | "br_half"
      | "lt_half"
      | "lb_half"
      | "rt_half"
      | "rb_half",
    e: React.PointerEvent,
  ) => {
    e.preventDefault();
    e.stopPropagation();
    setActiveHandle(corner);
    snapStateRef.current = {
      snappedX: null,
      snappedY: null,
      nearBorderX: null,
      nearBorderY: null,
      nearBorderStartX: null,
      nearBorderStartY: null,
    };
    const local = getLocalCoords(e.clientX, e.clientY);
    setInitialDragPointer(local);
    setInitialDragCorners({ ...cornersRef.current });
    if (containerRef.current) {
      containerRef.current.setPointerCapture(e.pointerId);
    }

    // Direct DOM interaction: make zoom bubble overlay visible
    if (zoomCircleDOMRef.current) {
      zoomCircleDOMRef.current.style.display = "flex";
      zoomCircleDOMRef.current.style.backgroundImage = `url(${imgUrl})`;
      zoomCircleDOMRef.current.style.backgroundPosition = `${local.x}% ${local.y}%`;
    }

    // Trigger a 50ms haptic feedback/vibration on mobile for corner dragging
    if (
      typeof window !== "undefined" &&
      window.navigator &&
      window.navigator.vibrate
    ) {
      if (
        [
          "tl",
          "tr",
          "br",
          "bl",
          "tl_half",
          "tr_half",
          "bl_half",
          "br_half",
          "lt_half",
          "lb_half",
          "rt_half",
          "rb_half",
        ].includes(corner)
      ) {
        try {
          window.navigator.vibrate(50);
        } catch (_err) {
          // ignore potential block on sandboxed iframe contexts
        }
      }
    }
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!activeHandle || !containerRef.current || !imageRef.current) return;
    e.preventDefault();

    const local = getLocalCoords(e.clientX, e.clientY);
    const clampedX = local.x;
    const clampedY = local.y;

    const uw = imageRef.current.clientWidth || 100;
    const uh = imageRef.current.clientHeight || 100;

    const getSnappedX = (mousePixelX: number, w: number) => {
      const targetLeft = 3;
      const targetRight = w - 3;

      const distLeft = Math.abs(mousePixelX - targetLeft);
      const distRight = Math.abs(mousePixelX - targetRight);

      let nearBorder: "left" | "right" | null = null;
      if (distLeft <= 10) {
        nearBorder = "left";
      } else if (distRight <= 10) {
        nearBorder = "right";
      }

      const now = Date.now();
      if (nearBorder !== snapStateRef.current.nearBorderX) {
        snapStateRef.current.nearBorderX = nearBorder;
        snapStateRef.current.nearBorderStartX = nearBorder ? now : null;
      }

      // Snap logic: only snap if stays within border region for >= 150ms
      if (nearBorder && !snapStateRef.current.snappedX) {
        if (
          snapStateRef.current.nearBorderStartX &&
          now - snapStateRef.current.nearBorderStartX >= 150
        ) {
          snapStateRef.current.snappedX = nearBorder;
        }
      }

      // Release snap logic: if user drags away from the snap line by > 12px
      if (snapStateRef.current.snappedX === "left") {
        if (distLeft > 12) {
          snapStateRef.current.snappedX = null;
          snapStateRef.current.nearBorderStartX = now; // prevent instant re-snap
        }
      } else if (snapStateRef.current.snappedX === "right") {
        if (distRight > 12) {
          snapStateRef.current.snappedX = null;
          snapStateRef.current.nearBorderStartX = now;
        }
      }

      if (snapStateRef.current.snappedX === "left") {
        return targetLeft;
      } else if (snapStateRef.current.snappedX === "right") {
        return targetRight;
      }
      return mousePixelX;
    };

    const getSnappedY = (mousePixelY: number, h: number) => {
      const targetTop = 3;
      const targetBottom = h - 3;

      const distTop = Math.abs(mousePixelY - targetTop);
      const distBottom = Math.abs(mousePixelY - targetBottom);

      let nearBorder: "top" | "bottom" | null = null;
      if (distTop <= 10) {
        nearBorder = "top";
      } else if (distBottom <= 10) {
        nearBorder = "bottom";
      }

      const now = Date.now();
      if (nearBorder !== snapStateRef.current.nearBorderY) {
        snapStateRef.current.nearBorderY = nearBorder;
        snapStateRef.current.nearBorderStartY = nearBorder ? now : null;
      }

      // Snap logic: only snap if stays within border region for >= 150ms
      if (nearBorder && !snapStateRef.current.snappedY) {
        if (
          snapStateRef.current.nearBorderStartY &&
          now - snapStateRef.current.nearBorderStartY >= 150
        ) {
          snapStateRef.current.snappedY = nearBorder;
        }
      }

      // Release snap logic: if user drags away from the snap line by > 12px
      if (snapStateRef.current.snappedY === "top") {
        if (distTop > 12) {
          snapStateRef.current.snappedY = null;
          snapStateRef.current.nearBorderStartY = now;
        }
      } else if (snapStateRef.current.snappedY === "bottom") {
        if (distBottom > 12) {
          snapStateRef.current.snappedY = null;
          snapStateRef.current.nearBorderStartY = now;
        }
      }

      if (snapStateRef.current.snappedY === "top") {
        return targetTop;
      } else if (snapStateRef.current.snappedY === "bottom") {
        return targetBottom;
      }
      return mousePixelY;
    };

    // Calculate snapped coordinates
    const originalPixelX = (clampedX / 100) * uw;
    const originalPixelY = (clampedY / 100) * uh;

    const snappedPixelX = getSnappedX(originalPixelX, uw);
    const snappedPixelY = getSnappedY(originalPixelY, uh);

    const snappedLocalX = (snappedPixelX / uw) * 100;
    const snappedLocalY = (snappedPixelY / uh) * 100;

    if (zoomCircleDOMRef.current) {
      zoomCircleDOMRef.current.style.backgroundPosition = `${snappedLocalX}% ${snappedLocalY}%`;
    }

    let next = { ...cornersRef.current };

    if (
      activeHandle === "tl" ||
      activeHandle === "tr" ||
      activeHandle === "br" ||
      activeHandle === "bl"
    ) {
      next[activeHandle] = {
        x: Math.max(0, Math.min(100, Math.round(snappedLocalX * 10) / 10)),
        y: Math.max(0, Math.min(100, Math.round(snappedLocalY * 10) / 10)),
      };
    } else if (initialDragPointer && initialDragCorners) {
      const dx = snappedLocalX - initialDragPointer.x;
      const dy = snappedLocalY - initialDragPointer.y;

      if (activeHandle === "tl_half") {
        next.tl = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tl.x + dx * 0.75) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tl.y + dy * 0.75) * 10) / 10,
            ),
          ),
        };
        next.tr = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tr.x + dx * 0.25) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tr.y + dy * 0.25) * 10) / 10,
            ),
          ),
        };
      } else if (activeHandle === "tr_half") {
        next.tr = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tr.x + dx * 0.75) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tr.y + dy * 0.75) * 10) / 10,
            ),
          ),
        };
        next.tl = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tl.x + dx * 0.25) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tl.y + dy * 0.25) * 10) / 10,
            ),
          ),
        };
      } else if (activeHandle === "bl_half") {
        next.bl = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.bl.x + dx * 0.75) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.bl.y + dy * 0.75) * 10) / 10,
            ),
          ),
        };
        next.br = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.br.x + dx * 0.25) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.br.y + dy * 0.25) * 10) / 10,
            ),
          ),
        };
      } else if (activeHandle === "br_half") {
        next.br = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.br.x + dx * 0.75) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.br.y + dy * 0.75) * 10) / 10,
            ),
          ),
        };
        next.bl = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.bl.x + dx * 0.25) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.bl.y + dy * 0.25) * 10) / 10,
            ),
          ),
        };
      } else if (activeHandle === "lt_half") {
        next.tl = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tl.x + dx * 0.75) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tl.y + dy * 0.75) * 10) / 10,
            ),
          ),
        };
        next.bl = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.bl.x + dx * 0.25) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.bl.y + dy * 0.25) * 10) / 10,
            ),
          ),
        };
      } else if (activeHandle === "lb_half") {
        next.bl = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.bl.x + dx * 0.75) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.bl.y + dy * 0.75) * 10) / 10,
            ),
          ),
        };
        next.tl = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tl.x + dx * 0.25) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tl.y + dy * 0.25) * 10) / 10,
            ),
          ),
        };
      } else if (activeHandle === "rt_half") {
        next.tr = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tr.x + dx * 0.75) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tr.y + dy * 0.75) * 10) / 10,
            ),
          ),
        };
        next.br = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.br.x + dx * 0.25) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.br.y + dy * 0.25) * 10) / 10,
            ),
          ),
        };
      } else if (activeHandle === "rb_half") {
        next.br = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.br.x + dx * 0.75) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.br.y + dy * 0.75) * 10) / 10,
            ),
          ),
        };
        next.tr = {
          x: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tr.x + dx * 0.25) * 10) / 10,
            ),
          ),
          y: Math.max(
            0,
            Math.min(
              100,
              Math.round((initialDragCorners.tr.y + dy * 0.25) * 10) / 10,
            ),
          ),
        };
      } else if (activeHandle === "t") {
        const newTLY = initialDragCorners.tl.y + dy;
        const newTRY = initialDragCorners.tr.y + dy;
        next.tl = {
          x: initialDragCorners.tl.x,
          y: Math.max(
            0,
            Math.min(initialDragCorners.bl.y - 1, Math.round(newTLY * 10) / 10),
          ),
        };
        next.tr = {
          x: initialDragCorners.tr.x,
          y: Math.max(
            0,
            Math.min(initialDragCorners.br.y - 1, Math.round(newTRY * 10) / 10),
          ),
        };
        next.br = { ...initialDragCorners.br };
        next.bl = { ...initialDragCorners.bl };
      } else if (activeHandle === "b") {
        const newBLY = initialDragCorners.bl.y + dy;
        const newBRY = initialDragCorners.br.y + dy;
        next.bl = {
          x: initialDragCorners.bl.x,
          y: Math.max(
            initialDragCorners.tl.y + 1,
            Math.min(100, Math.round(newBLY * 10) / 10),
          ),
        };
        next.br = {
          x: initialDragCorners.br.x,
          y: Math.max(
            initialDragCorners.tr.y + 1,
            Math.min(100, Math.round(newBRY * 10) / 10),
          ),
        };
        next.tl = { ...initialDragCorners.tl };
        next.tr = { ...initialDragCorners.tr };
      } else if (activeHandle === "l") {
        const newTLX = initialDragCorners.tl.x + dx;
        const newBLX = initialDragCorners.bl.x + dx;
        next.tl = {
          x: Math.max(
            0,
            Math.min(initialDragCorners.tr.x - 1, Math.round(newTLX * 10) / 10),
          ),
          y: initialDragCorners.tl.y,
        };
        next.bl = {
          x: Math.max(
            0,
            Math.min(initialDragCorners.br.x - 1, Math.round(newBLX * 10) / 10),
          ),
          y: initialDragCorners.bl.y,
        };
        next.tr = { ...initialDragCorners.tr };
        next.br = { ...initialDragCorners.br };
      } else if (activeHandle === "r") {
        const newTRX = initialDragCorners.tr.x + dx;
        const newBRX = initialDragCorners.br.x + dx;
        next.tr = {
          x: Math.max(
            initialDragCorners.tl.x + 1,
            Math.min(100, Math.round(newTRX * 10) / 10),
          ),
          y: initialDragCorners.tr.y,
        };
        next.br = {
          x: Math.max(
            initialDragCorners.bl.x + 1,
            Math.min(100, Math.round(newBRX * 10) / 10),
          ),
          y: initialDragCorners.br.y,
        };
        next.tl = { ...initialDragCorners.tl };
        next.bl = { ...initialDragCorners.bl };
      }

      next.tl.x = Math.max(0, Math.min(100, next.tl.x));
      next.tl.y = Math.max(0, Math.min(100, next.tl.y));
      next.tr.x = Math.max(0, Math.min(100, next.tr.x));
      next.tr.y = Math.max(0, Math.min(100, next.tr.y));
      next.br.x = Math.max(0, Math.min(100, next.br.x));
      next.br.y = Math.max(0, Math.min(100, next.br.y));
      next.bl.x = Math.max(0, Math.min(100, next.bl.x));
      next.bl.y = Math.max(0, Math.min(100, next.bl.y));
    }

    cornersRef.current = next;
    updateDOMFromCorners(next);
  };

  const handlePointerUp = (e: React.PointerEvent) => {
    if (!activeHandle) return;

    // Push the finalized coordinates values up to state to trigger final React reconciliation
    const finalCorners = { ...cornersRef.current };
    setCorners(finalCorners);

    setActiveHandle(null);
    setInitialDragPointer(null);
    setInitialDragCorners(null);

    if (zoomCircleDOMRef.current) {
      zoomCircleDOMRef.current.style.display = "none";
    }

    if (containerRef.current) {
      containerRef.current.releasePointerCapture(e.pointerId);
    }

    if (onCropChange) {
      onCropChange(finalCorners);
    }
  };

  const getAbsoluteCoords = React.useCallback(() => {
    if (!imageRef.current || !imgLoaded) {
      return {
        tl: { x: 0, y: 0 },
        tr: { x: 100, y: 0 },
        br: { x: 100, y: 100 },
        bl: { x: 0, y: 100 },
      };
    }
    const w = imageRef.current.clientWidth || 100;
    const h = imageRef.current.clientHeight || 100;

    return {
      tl: { x: (corners.tl.x / 100) * w, y: (corners.tl.y / 100) * h },
      tr: { x: (corners.tr.x / 100) * w, y: (corners.tr.y / 100) * h },
      br: { x: (corners.br.x / 100) * w, y: (corners.br.y / 100) * h },
      bl: { x: (corners.bl.x / 100) * w, y: (corners.bl.y / 100) * h },
    };
  }, [imgLoaded, corners, imageRef]);

  const handleApplyChanges = async (isNext: boolean) => {
    if (!imgUrl) return;
    setIsProcessing(true);
    try {
      // Non-Destructive Architecture: we no longer process the image on save!
      // We pass the raw Blob directly to onSave, which will just update the metadata JSON.
      const rawBlob =
        typeof imageSrc === "string" ? new Blob() : (imageSrc as Blob);

      if (isNext && onSaveAndNext) {
        addLog("Saving and going to next page");
        onSaveAndNext(rawBlob, corners, rotation, filter, adjustments);
      } else {
        addLog("Saving changes");
        onSave(rawBlob, corners, rotation, filter, adjustments);
      }
    } catch (e) {
      addLog(`Save error: ${e}`);
      console.error(e);
      alert("Fail: " + String(e));
    } finally {
      setIsProcessing(false);
    }
  };

  return {
    imgUrl,
    loading,
    isProcessing,
    corners,
    setCorners,
    rotation,
    setRotation,
    filter,
    setFilter,
    adjustments,
    setAdjustments,
    activeTab,
    setActiveTab,
    activeHandle,
    containerRef,
    imageRef,
    overlayStyle,
    handleRotate,
    handleResetCrop,
    handleAutoDetect,
    handlePointerDown,
    handlePointerMove,
    handlePointerUp,
    getAbsoluteCoords,
    handleApplyChanges,
    isAutoDetecting,
    showFlash,
    toast,
    setToast,
    imgLoaded,
    handleImageLoad,
    naturalAspect,
    scale,
    setScale,
    // Export fast DOM Refs
    tlDOMRef,
    trDOMRef,
    brDOMRef,
    blDOMRef,
    tDOMRef,
    bDOMRef,
    lDOMRef,
    rDOMRef,
    tlHalfDOMRef,
    trHalfDOMRef,
    blHalfDOMRef,
    brHalfDOMRef,
    ltHalfDOMRef,
    lbHalfDOMRef,
    rtHalfDOMRef,
    rbHalfDOMRef,
    polygonDOMRef,
    overlayPathDOMRef,
    zoomCircleDOMRef,
  };
}
