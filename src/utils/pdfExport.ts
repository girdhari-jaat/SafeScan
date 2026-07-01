// AUDITED: Removed unused imports (warpQuadrilateral, loadImageElement)
import { getImageBlob } from "./db";
import { ScanPage } from "../types";

export interface PDFExportOptions {
  pageSize: "a4" | "letter" | "fit";
  orientation: "portrait" | "landscape" | "auto";
  quality: number; // 0.1 to 1.0
  documentTitle: string;
  password?: string;
  pageCorners?: Array<{ x: number; y: number }>;
}

/**
 * Downloads a multi-page PDF generated fully client-side using Web Worker background thread
 */
export async function exportDocumentToPDF(
  pages: ScanPage[],
  options: PDFExportOptions,
  onProgress?: (current: number, total: number) => void,
): Promise<Blob> {
  const { pageSize, orientation, quality, password } = options;
  const total = pages.length;

  if (total === 0) {
    throw new Error("No pages to export");
  }

  const pagesData: { blob: Blob; page: ScanPage }[] = [];

  const savedHdMode =
    typeof window !== "undefined" ? localStorage.getItem("hdMode") : "Fast";
  const hdModeSuffix =
    savedHdMode === "High"
      ? "_High"
      : savedHdMode === "Standard"
        ? "_Standard"
        : "_Fast";

  for (let i = 0; i < total; i++) {
    if (onProgress) {
      onProgress(i + 1, total);
    }

    const page = pages[i];
    const imageId = page.originalImageId;
    const blob = await getImageBlob(imageId);
    if (!blob) {
      console.warn(`Could not load page image: ${imageId}`);
      continue;
    }
    const pageWithSourceType = {
      ...page,
      sourceType: ((page as any).sourceType || "paper") + hdModeSuffix,
    };
    pagesData.push({ blob, page: pageWithSourceType as any });
  }

  // Import worker client helper to run the PDF generation process fully off-thread
  const { generatePDFOffThread } = await import("./imageWorkerClient");
  return generatePDFOffThread(pagesData, {
    pageSize,
    orientation,
    quality: quality || 1.0,
    password,
  });
}

export async function generatePDFFromCards(
  cards: any[],
  title: string,
  action: "save" | "share" | "print" | "download",
  mode: "idcard" | "grid",
): Promise<void> {
  try {
    const isIdCard = mode === "idcard";
    const iterations = isIdCard ? 8 : cards.length;
    const cardsData: ({ blob: Blob; card: any } | null)[] = new Array(
      iterations,
    ).fill(null);
    const loadedBlobs = new Map<string, Blob>();

    for (let i = 0; i < iterations; i++) {
      const sourceIndex = isIdCard ? i % cards.length : i;
      const card = cards[sourceIndex];
      if (!card) continue;

      const imageId = card.imageId || card.originalImageId;

      if (!loadedBlobs.has(imageId)) {
        const b = await getImageBlob(imageId);
        if (b) loadedBlobs.set(imageId, b);
      }

      const blob = loadedBlobs.get(imageId);
      if (!blob) continue;

      // Pass the details we need. Include corners, rotation, filter, adjustments
      const meta = (card as any).meta || {
        cropPoints: card.corners,
        rotate: card.rotation,
        filter: card.filter,
        adjustments: card.adjustments,
      };

      const savedHdMode =
        typeof window !== "undefined" ? localStorage.getItem("hdMode") : "Fast";
      const hdModeSuffix =
        savedHdMode === "High"
          ? "_High"
          : savedHdMode === "Standard"
            ? "_Standard"
            : "_Fast";

      const finalCard = {
        corners: meta.cropPoints || meta.corners || card.corners,
        rotation:
          typeof meta.rotate === "number" ? meta.rotate : card.rotation || 0,
        filter: meta.filter || card.filter || "original",
        adjustments: card.adjustments || {
          brightness: 0,
          contrast: 0,
          saturation: 0,
          sharpness: 0,
          shadows: 0,
          temperature: 0,
        },
        originalIndex: sourceIndex,
        sourceType: (mode === "idcard" ? "idcard" : "grid") + hdModeSuffix,
      };

      cardsData[i] = { blob, card: finalCard };
    }

    if (cardsData.filter((c) => !!c).length === 0) {
      throw new Error("No valid cards captured to generate PDF");
    }

    const { generateCardPDFOffThread } = await import("./imageWorkerClient");
    const pdfBlob = await generateCardPDFOffThread(cardsData, {
      mode,
      title,
      quality: 0.9,
    });

    const filename = `${title || "Scan"}.pdf`;
    await shareOrDownloadFile(
      pdfBlob,
      filename,
      title,
      action === "download" || action === "save",
    );
  } catch (err) {
    console.error("PDF generation from cards failed:", err);
    throw err;
  }
}

/**
 * Converts a Blob to a base64 string
 */
function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = reader.result as string;
      const base64 = result.split(",")[1];
      resolve(base64);
    };
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}

/**
 * Universal file saver/sharer that works in both standard web browsers and Capacitor-wrapped mobile apps (APKs).
 */
export async function saveOrShareBlob(
  blob: Blob,
  fileName: string,
  title?: string,
  forceSaveDirectly: boolean = false,
): Promise<void> {
  const { Capacitor } = await import("@capacitor/core");

  if (Capacitor.isNativePlatform()) {
    const { Filesystem, Directory } = await import("@capacitor/filesystem");
    const { Share } = await import("@capacitor/share");
    const { Toast } = await import("@capacitor/toast");
    const base64Data = await blobToBase64(blob);

    // Storing inside user's public Documents folder
    const relativePath = `SafeScan/${fileName}`;
    
    try {
      // 1. Write file to public Documents directory using Capacitor plugin
      const writeResult = await Filesystem.writeFile({
        path: relativePath,
        data: base64Data,
        directory: Directory.Documents,
        recursive: true,
      });

      await Toast.show({
        text: `${fileName} saved to Documents/SafeScan folder`,
        duration: "short",
        position: "bottom",
      });

      // 2. If it's a direct download/save action, exit early.
      if (forceSaveDirectly) {
        return;
      }

      // Open native mobile share sheet using the file uri generated by Capacitor
      await Share.share({
        title: fileName,
        text: "Scanned Document",
        files: [writeResult.uri],
      });
      
    } catch (err) {
      console.error("Error saving file via Capacitor Filesystem:", err);
      await Toast.show({
        text: `Failed to save file: ${err instanceof Error ? err.message : String(err)}`,
        duration: "long",
        position: "bottom",
      });
    }
    return;
  } else {
    // Web Browser fallback
    const downloadUrl = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = downloadUrl;
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(downloadUrl);
  }
}

/**
 * Shares a PDF file using Web Share API if supported (excellent for APKs / mobile browsers),
 * or falls back to our universal saver/sharer.
 */
export async function shareOrDownloadFile(
  blob: Blob,
  fileName: string,
  title?: string,
  forceDownload: boolean = false,
): Promise<void> {
  // Normalize fileName to end with .pdf if not yet present
  let normalizedName = fileName.trim() || "Scanned_Doc";
  if (!normalizedName.toLowerCase().endsWith(".pdf")) {
    normalizedName += ".pdf";
  }

  // FIXED: Array brackets intact [blob]
  const file = new File([blob], normalizedName, { type: "application/pdf" });

  // Native Web Share API integration (perfect for Android APK wrapper context)
  if (
    !forceDownload &&
    navigator.share &&
    navigator.canShare &&
    navigator.canShare({ files: [file] })
  ) {
    try {
      await navigator.share({
        files: [file],
        title: title || normalizedName,
        text: "Scanned Document (PDF)",
      });
      return; // Shared natively with success
    } catch (err) {
      if (err instanceof Error && err.name === "AbortError") {
        // User voluntarily dismissed share menu
        return;
      }
      console.warn(
        "Native web share failed, falling back to instant browser downloader:",
        err,
      );
    }
  }

  // Fallback to standard universal downloader/sharer
  await saveOrShareBlob(blob, normalizedName, title, forceDownload);
}
