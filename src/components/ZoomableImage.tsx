import React, { useState, useRef, useEffect, useCallback } from 'react';

interface ZoomableImageProps {
  src?: string;
  className?: string;
  style?: React.CSSProperties;
  minZoom?: number;
  maxZoom?: number;
  onSwipeLeft?: () => void;
  onSwipeRight?: () => void;
  onSingleTap?: () => void;
  onScaleChange?: (scale: number) => void;
  // A capability to force reset zoom from parent component (e.g. when changing page)
  resetTrigger?: any;
  title?: string;
  zoom?: number;
}

export function ZoomableImage({
  src,
  className = '',
  style = {},
  minZoom = 1,
  maxZoom = 4,
  onSwipeLeft,
  onSwipeRight,
  onSingleTap,
  onScaleChange,
  resetTrigger,
  zoom
}: ZoomableImageProps) {
  // Refs to track state
  const scaleRef = useRef(1);
  const panRef = useRef({ x: 0, y: 0 });
  const imageRef = useRef<HTMLImageElement>(null);
  
  // State for visual indicator to avoid constant re-renders during high-frequency moves
  const [displayScale, setDisplayScale] = useState(1);
  const scaleUpdateTimer = useRef<any>(null);

  const debouncedSetDisplayScale = useCallback((val: number) => {
    if (scaleUpdateTimer.current) clearTimeout(scaleUpdateTimer.current);
    scaleUpdateTimer.current = setTimeout(() => {
      setDisplayScale(val);
    }, 200);
  }, []);

  // Refs to track pointer/touch states across event callbacks
  const containerRef = useRef<HTMLDivElement>(null);

  // Helper to update transform DOM directly
  const updateTransform = () => {
    if (imageRef.current) {
        imageRef.current.style.transform = `translate(${panRef.current.x}px, ${panRef.current.y}px) scale(${scaleRef.current})`;
    }
    debouncedSetDisplayScale(scaleRef.current);
  };

  // Debounced scale change
  const debouncedScaleChange = useCallback(
    (() => {
      let timeoutId: number | null = null;
      return (scale: number) => {
        if (timeoutId) window.clearTimeout(timeoutId);
        timeoutId = window.setTimeout(() => {
          onScaleChange?.(scale);
        }, 200);
      };
    })(),
    [onScaleChange]
  );
  const activePointers = useRef<Map<number, { x: number, y: number }>>(new Map());
  const initialDistance = useRef<number>(0);
  const initialScale = useRef<number>(1);
  const initialPan = useRef<{ x: number, y: number }>({ x: 0, y: 0 });
  const isDragging = useRef<boolean>(false);
  
  // For double-tap / single-tap detection
  const lastTap = useRef<{ time: number; x: number; y: number }>({ time: 0, x: 0, y: 0 });
  const tapTimeout = useRef<number | null>(null);

  // For swipe horizontal navigation
  const swipeStart = useRef<{ x: number; y: number; time: number }>({ x: 0, y: 0, time: 0 });

  // Keep track of the last scale value sent to/received from parent to break synchronization loops
  const lastSharedScale = useRef(1);

  // Keep latest callback stored in a Ref to avoid triggering scale-update loops on inline lambda changes
  const onScaleChangeRef = useRef(onScaleChange);
  useEffect(() => {
    onScaleChangeRef.current = onScaleChange;
  }, [onScaleChange]);

  // Reset zoom state whenever the resetTrigger changes (e.g., active page changed in slider)
  useEffect(() => {
    scaleRef.current = 1;
    panRef.current = { x: 0, y: 0 };
    updateTransform();
    debouncedScaleChange(1);
    lastSharedScale.current = 1;
    activePointers.current.clear();
    isDragging.current = false;
  }, [resetTrigger, src]);

  // Constrain panning coordinates to prevent dragging completely out of view
  const getConstrainedPan = useCallback((x: number, y: number, currentScale: number) => {
    if (!containerRef.current || currentScale <= 1) {
      return { x: 0, y: 0 };
    }
    
    const rect = containerRef.current.getBoundingClientRect();
    
    // Bounds depend on scaled content dimensions relative to the container dimensions
    const limitX = (rect.width * (currentScale - 1)) / 2;
    const limitY = (rect.height * (currentScale - 1)) / 2;
    
    return {
      x: Math.max(-limitX, Math.min(limitX, x)),
      y: Math.max(-limitY, Math.min(limitY, y))
    };
  }, []);

  // Synchronize internal scale state with external zoom prop
  useEffect(() => {
    if (zoom !== undefined && Math.abs(zoom - lastSharedScale.current) > 0.01) {
      lastSharedScale.current = zoom;
      scaleRef.current = zoom;
      if (zoom <= 1) {
        panRef.current = { x: 0, y: 0 };
      } else {
        panRef.current = getConstrainedPan(panRef.current.x, panRef.current.y, zoom);
      }
      updateTransform();
    }
  }, [zoom, getConstrainedPan]);

  useEffect(() => {
    if (!src || !imageRef.current) return;
    const canvas = imageRef.current as unknown as HTMLCanvasElement;
    const img = new Image();
    img.src = src;
    img.onload = () => {
      canvas.width = img.width;
      canvas.height = img.height;
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0);
      }
    };
  }, [src]);

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!containerRef.current) return;
    
    const container = containerRef.current;
    container.setPointerCapture(e.pointerId);
    
    // Store original coordinate
    activePointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    
    // Tap or Swipe starts if 1 pointer is active
    if (activePointers.current.size === 1) {
      isDragging.current = true;
      initialScale.current = scaleRef.current;
      initialPan.current = { ...panRef.current };
      
      // Store starting states for swipe
      swipeStart.current = {
        x: e.clientX,
        y: e.clientY,
        time: Date.now()
      };
    } 
    // Pinch starts if 2 pointers are active
    else if (activePointers.current.size === 2) {
      isDragging.current = false; // Disable normal single pointer panning
      
      const pointersList = Array.from(activePointers.current.values()) as { x: number; y: number }[];
      const p1 = pointersList[0];
      const p2 = pointersList[1];
      
      initialDistance.current = Math.hypot(p1.x - p2.x, p1.y - p2.y);
      initialScale.current = scaleRef.current;
      initialPan.current = { ...panRef.current };
    }
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!activePointers.current.has(e.pointerId)) return;
    
    // Update pointer coordinate
    activePointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    
    // Manage dragging/panning (Single Touch or Mouse drag)
    if (scaleRef.current > 1 && isDragging.current && activePointers.current.size === 1) {
      const pStart = swipeStart.current;
      const dx = e.clientX - pStart.x;
      const dy = e.clientY - pStart.y;
      
      const targetPanX = initialPan.current.x + dx;
      const targetPanY = initialPan.current.y + dy;
      
      panRef.current = getConstrainedPan(targetPanX, targetPanY, scaleRef.current);
      updateTransform();
    } 
    // Manage pinch zoom (Dual touch pinch and spread gestures)
    else if (activePointers.current.size === 2) {
      const pointersList = Array.from(activePointers.current.values()) as { x: number; y: number }[];
      const p1 = pointersList[0];
      const p2 = pointersList[1];
      
      const dist = Math.hypot(p1.x - p2.x, p1.y - p2.y);
      if (initialDistance.current > 0) {
        const scaleRatio = dist / initialDistance.current;
        const newScale = Math.max(minZoom, Math.min(maxZoom, initialScale.current * scaleRatio));
        
        scaleRef.current = newScale;
        
        // Dynamic boundary adjustments during active multi-touch gestures
        panRef.current = getConstrainedPan(panRef.current.x, panRef.current.y, newScale);
        updateTransform();
        debouncedScaleChange(newScale);
      }
    }
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!activePointers.current.has(e.pointerId)) return;
    
    if (containerRef.current) {
      try {
        containerRef.current.releasePointerCapture(e.pointerId);
      } catch (err) {
        // Safe fallback for pointer capture exceptions in older runtimes
      }
    }
    
    const clientX = e.clientX;
    const clientY = e.clientY;
    
    activePointers.current.delete(e.pointerId);
    
    if (activePointers.current.size === 0) {
      isDragging.current = false;
      
      const duration = Date.now() - swipeStart.current.time;
      const dx = clientX - swipeStart.current.x;
      const dy = clientY - swipeStart.current.y;
      
      // Horizontal swipe navigation logic when image is unzoomed (scaleRef.current = 1)
      if (scaleRef.current === 1 && duration < 300) {
        if (Math.abs(dx) > 60 && Math.abs(dy) < 40) {
          if (dx < 0 && onSwipeLeft) {
            onSwipeLeft();
            return;
          } else if (dx > 0 && onSwipeRight) {
            onSwipeRight();
            return;
          }
        }
      }
      
      // Double tap / Single tap detection & execution
      const now = Date.now();
      const tapInterval = now - lastTap.current.time;
      const tapDist = Math.hypot(clientX - lastTap.current.x, clientY - lastTap.current.y);
      
      if (tapInterval < 300 && tapDist < 30) {
        // Clear active single tap timer to prevent double actions
        if (tapTimeout.current) {
          window.clearTimeout(tapTimeout.current);
          tapTimeout.current = null;
        }
        
        // Double tap toggles zoom
        if (scaleRef.current > 1) {
          scaleRef.current = 1;
          panRef.current = { x: 0, y: 0 };
        } else {
          scaleRef.current = 2.5;
          
          // Center zoom on double tap point if container is available
          if (containerRef.current) {
            const rect = containerRef.current.getBoundingClientRect();
            const tapCenterX = clientX - rect.left - rect.width / 2;
            const tapCenterY = clientY - rect.top - rect.height / 2;
            
            // Adjust the pan to zoom target position centered
            const initialPanX = -tapCenterX * 1.5;
            const initialPanY = -tapCenterY * 1.5;
            panRef.current = getConstrainedPan(initialPanX, initialPanY, 2.5);
          }
        }
        
        updateTransform();
        debouncedScaleChange(scaleRef.current);
        
        lastTap.current = { time: 0, x: 0, y: 0 }; // Clear tap sequence
      } else {
        lastTap.current = { time: now, x: clientX, y: clientY };
        
        // Delay single tap dispatch to wait for potential double click
        if (onSingleTap) {
          if (tapTimeout.current) {
            window.clearTimeout(tapTimeout.current);
          }
          tapTimeout.current = window.setTimeout(() => {
            onSingleTap();
            tapTimeout.current = null;
          }, 250);
        }
      }
    } else if (activePointers.current.size === 1) {
      // Re-enable dragging with the sole remaining pointer
      isDragging.current = true;
      const remainingPointerId = Array.from(activePointers.current.keys())[0];
      const remainingPoint = activePointers.current.get(remainingPointerId);
      if (remainingPoint) {
        swipeStart.current = {
          x: remainingPoint.x,
          y: remainingPoint.y,
          time: Date.now()
        };
        initialPan.current = { ...panRef.current };
      }
    }
  };

  const handlePointerCancel = (e: React.PointerEvent<HTMLDivElement>) => {
    activePointers.current.delete(e.pointerId);
    if (activePointers.current.size === 0) {
      isDragging.current = false;
    }
  };

  return (
    <div
      ref={containerRef}
      id="zoomable-image-container"
      className="relative w-full h-full overflow-hidden select-none flex items-center justify-center cursor-move"
      style={{
        touchAction: scaleRef.current > 1 ? 'none' : 'pan-y', // Allow vertical scrolling when not zoomed
      }}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerCancel}
    >
      <canvas
        ref={imageRef as unknown as React.RefObject<HTMLCanvasElement>}
        className={`w-full h-full object-contain pointer-events-none transition-transform duration-75 ease-out desc-zoomable-img ${className}`}
        style={{
          ...style,
          userSelect: 'none',
        }}
      />
      
      {/* Visual Indicator of Zoom Level */}
      {displayScale > 1.05 && (
        <div className="absolute top-3 right-3 bg-zinc-900/85 backdrop-blur-md text-[var(--primary)] font-mono text-[9px] font-black px-2.5 py-1 rounded-full border border-[var(--primary)]/10 pointer-events-none shadow-lg tracking-wider select-none animate-fade-in animate-duration-100 z-10">
          ZOOM {displayScale.toFixed(1)}x
        </div>
      )}
    </div>
  );
}
