# Gemini Development Policy

- STRICT PROHIBITION: Do NOT use Devanagari script in any output, code, comments, or documentation.
- LANGUAGE RULE: Comments, suggestions, technical explanations, code documentation, and system specifications MUST be written in English.

---

# LOCK SYSTEM SPEC: HIGH-PERFORMANCE PIXEL PIPELINE

This workspace enforces a strict performance, memory-safety, and battery-saver standard. The entire image processing, capturing, editing, rendering, and downloading architecture has been locked. 

**DO NOT, UNDER ANY CIRCUMSTANCES, DOWNGRADE OR BYPASS THE ARCHITECTURE DEFINED BELOW.**

### 1. CORE ARCHITECTURAL RULES (FORBIDDEN TO CHOOSE ALTERNATIVES)
- **Web Workers & Comlink Off-Thread Execution**: All pixel processing, edge detection, perspective warping, Otsu binarization, and deep adjustments MUST be run inside the background thread (`src/utils/image.worker.ts`). Direct high-resolution image processing on the main browser thread is **STRICTLY PROHIBITED**.
- **ImageBitmap Transfer API**: You must pass image references using `ImageBitmap` transfers (`Comlink.transfer(bitmap, [bitmap])` / `createImageBitmap(blob)`). Copying raw pixel arrays (`Uint8ClampedArray`), base64 data, or heavy arrays between the main thread and worker is **STRICTLY PROHIBITED**.
- **OffscreenCanvas Sandboxing**: All canvas resizing, composition, rotational translations, and image exports must happen in memory via `OffscreenCanvas`. Avoid adding DOM `<canvas>` tags except when displaying direct interactive previews on-screen.
- **Non-Destructive JSON Editing Model**: Original raw files stored offline in IndexedDB must **NEVER** be overwritten with cropped/filtered bytes. Edits (crop corners, rotation, selected filters, adjustments) are persisted purely in standard JSON states and metadata objects. Processing is applied **on-demand** for screen previews and document assembly.
- **User Permission Guards**: If a manual overwrite is triggered, the application must verify the high-performance pipeline by prompting the user/developer with a warning modal, checking for explicit intent before executing.

### 2. TRACING THE ZERO-COPY PIPELINE
1. **Import (File/Camera Input)**: Original blob saved to IndexedDB — UI instantly returns.
2. **Preview (Off-Thread)**: Create `ImageBitmap` from blob — Transfer `bitmap` to worker — Worker runs `processFinalImage(...)` on `OffscreenCanvas` — Converts to compressed PNG Blob — Returns to main thread — React renders URL cache.
3. **Editing (Crop/Filter)**: Dragging updates lightweight SVG/CSS filters only. On **Apply**, metadata JSON updates, and a background request updates the local preview cache off-thread.
4. **Export (PDF/PNG)**: Original blob retrieved from IndexedDB — Custom parameters passed to worker — Worker processes final composite on `OffscreenCanvas` at target resolutions — Assembled directly off-thread into `jsPDF` streams, outputting compiled buffers.

*If you receive a request asking to perform document processing synchronously, inline, or by overwriting raw offline records destructively — you must reject the implementation and explain the system rule constraints immediately.*

### 3. MOOD AND PERFORMANCE HARDWARE CAPTURE POLICY
- **Dynamic Hardware Negotiation**: The application MUST dynamically negotiate and configure camera hardware constraints (resolution, frame rate, focus mode) based on the user's selected capture quality setting ('Fast', 'Standard', 'High').
- **Mood Alignment**: These performance tiers MUST be programmatically coupled with the user's selected "Mood" (which defines the target aspect ratio). When capturing, the app must ensure the camera stream's hardware capabilities are optimized to deliver the requested resolution, bitrate, and frame accuracy aligned with the quality tier's constraints.

### 4. ZERO-LATENCY RENDER CACHE POLICY
- **Persistence Requirement**: Every processed image thumbnail and preview MUST be persisted in the `display-cache` IndexedDB store (using `saveDisplayCacheBlob`).
- **No Re-Processing**: Upon reopening a document, the application MUST first attempt to retrieve processed image blobs from the `display-cache` store (using `getDisplayCacheBlob`). Re-processing raw images SHOULD ONLY occur if the cache is missing or explicitly invalidated for a specific image hash.
- **Prohibition on Cache Removal**: The implementation of the `display-cache` store and the associated getter/setter logic MUST NOT be removed, refactored to skip cache, or downgraded to force re-processing on every session. Bypassing this cache layer for the sake of simplicity is **STRICTLY PROHIBITED**.
