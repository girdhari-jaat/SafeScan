// AUDITED: Removed unused imports (warpQuadrilateral, loadImageElement)
import { getImageBlob } from "./db";
import { ScanPage } from "../types";

export interface PDFExportOptions {
  pageSize: "a4" | "letter" | "fit";
  orientation: "portrait" | "landscape" | "auto";
  quality: number;
  documentTitle: string;
  password?: string;
  pageCorners?: Array<{ x: number; y: number }>;
}

export async function exportDocumentToPDF(
  pages: ScanPage[],
  options: PDFExportOptions,
  onProgress?: (current: number, total: number) => void,
): Promise<Blob> {
  const { pageSize, orientation, quality, password } = options;
  const total = pages.length;
  if (total === 0) throw new Error("No pages to export");
  const pagesData: { blob: Blob; page: ScanPage }[] = [];
  const savedHdMode = typeof window!== "undefined"? localStorage.getItem("hdMode") : "Fast";
  const hdModeSuffix = savedHdMode === "High"? "_High" : savedHdMode === "Standard"? "_Standard" : "_Fast";
  for (let i = 0; i < total; i++) {
    if (onProgress) onProgress(i + 1, total);
    const page = pages[i];
    const blob = await getImageBlob(page.originalImageId);
    if (!blob) { console.warn(`Could not load page image: ${page.originalImageId}`); continue; }
    pagesData.push({ blob, page: {...page, sourceType: ((page as any).sourceType || "paper") + hdModeSuffix } as any });
  }
  const { generatePDFOffThread } = await import("./imageWorkerClient");
  return generatePDFOffThread(pagesData, { pageSize, orientation, quality: quality || 1.0, password });
}

export async function generatePDFFromCards(
  cards: any[],
  title: string,
  action: "save" | "share" | "print" | "download",
  mode: "idcard" | "grid",
): Promise<void> {
  try {
    const isIdCard = mode === "idcard";
    const iterations = isIdCard? 8 : cards.length;
    const cardsData: ({ blob: Blob; card: any } | null)[] = new Array(iterations).fill(null);
    const loadedBlobs = new Map<string, Blob>();
    for (let i = 0; i < iterations; i++) {
      const sourceIndex = isIdCard? i % cards.length : i;
      const card = cards[sourceIndex];
      if (!card) continue;
      const imageId = card.imageId || card.originalImageId;
      if (!loadedBlobs.has(imageId)) { const b = await getImageBlob(imageId); if (b) loadedBlobs.set(imageId, b); }
      const blob = loadedBlobs.get(imageId);
      if (!blob) continue;
      const meta = (card as any).meta || { cropPoints: card.corners, rotate: card.rotation, filter: card.filter, adjustments: card.adjustments };
      const savedHdMode = typeof window!== "undefined"? localStorage.getItem("hdMode") : "Fast";
      const hdModeSuffix = savedHdMode === "High"? "_High" : savedHdMode === "Standard"? "_Standard" : "_Fast";
      cardsData[i] = {
        blob,
        card: {
          corners: meta.cropPoints || meta.corners || card.corners,
          rotation: typeof meta.rotate === "number"? meta.rotate : card.rotation || 0,
          filter: meta.filter || card.filter || "original",
          adjustments: card.adjustments || { brightness: 0, contrast: 0, saturation: 0, sharpness: 0, shadows: 0, temperature: 0 },
          originalIndex: sourceIndex,
          sourceType: (mode === "idcard"? "idcard" : "grid") + hdModeSuffix,
        },
      };
    }
    if (cardsData.filter((c) =>!!c).length === 0) throw new Error("No valid cards captured to generate PDF");
    const { generateCardPDFOffThread } = await import("./imageWorkerClient");
    const pdfBlob = await generateCardPDFOffThread(cardsData, { mode, title, quality: 0.9 });
    await shareOrDownloadFile(pdfBlob, `${title || "Scan"}.pdf`, title, action === "download" || action === "save");
  } catch (err) { console.error("PDF generation from cards failed:", err); throw err; }
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => resolve((reader.result as string).split(",")[1]);
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}

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
    const { FilePicker } = await import("@capawesome/capacitor-file-picker");
    const base64Data = await blobToBase64(blob);
    const isImage = fileName.toLowerCase().endsWith(".jpg") || fileName.toLowerCase().endsWith(".png");

    if (isImage || forceSaveDirectly) {
      try {
        const mimeType = blob.type || (isImage? "image/jpeg" : "application/pdf");
        const result = await FilePicker.saveFile({ data: base64Data, name: fileName, mimeType });
        if (result.path) await Toast.show({ text: `Saved`, duration: "short", position: "bottom" });
        return;
      } catch (err: any) {
        if (err.message?.includes("cancelled") || err.message?.includes("canceled")) return;
      }
    }

    if (isImage) {
      try {
        await Filesystem.writeFile({ path: `Android/media/com.safescan.app/SafeScan/${fileName}`, data: base64Data, directory: Directory.External, recursive: true });
        await Toast.show({ text: `Image saved to Android/media/com.safescan.app/SafeScan`, duration: "short", position: "bottom" });
        return;
      } catch (err) {
        await Filesystem.writeFile({ path: `SafeScan/${fileName}`, data: base64Data, directory: Directory.External, recursive: true });
        await Toast.show({ text: `Image saved in SafeScan folder`, duration: "short", position: "bottom" });
        return;
      }
    }

    if (forceSaveDirectly) {
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url; link.download = fileName;
      document.body.appendChild(link); link.click(); document.body.removeChild(link);
      URL.revokeObjectURL(url);
      await Toast.show({ text: `Downloading ${fileName}...`, duration: "short", position: "bottom" });
      return;
    }

    const writeResult = await Filesystem.writeFile({ path: `SafeScan/${fileName}`, data: base64Data, directory: Directory.External, recursive: true });
    await Toast.show({ text: `PDF saved to SafeScan folder`, duration: "short", position: "bottom" });
    await Share.share({ title: fileName, text: "Scanned Document", files: [writeResult.uri] });
    return;
  } else {
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url; link.download = fileName;
    document.body.appendChild(link); link.click(); document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }
}

export async function shareOrDownloadFile(
  blob: Blob,
  fileName: string,
  title?: string,
  forceDownload: boolean = false,
): Promise<void> {
  let normalizedName = fileName.trim() || "Scanned_Doc";
  if (!normalizedName.toLowerCase().endsWith(".pdf")) normalizedName += ".pdf";
  const file = new File(, normalizedName, { type: "application/pdf" }); // <- Yahan fix hai
  if (!forceDownload && navigator.share && navigator.canShare && navigator.canShare({ files: })) { // <- Yahan fix hai
    try {
      await navigator.share({ files:, title: title || normalizedName, text: "Scanned Document (PDF)" }); // <- Yahan fix hai
      return;
    } catch (err) { if (err instanceof Error && err.name === "AbortError") return; }
  }
  await saveOrShareBlob(blob, normalizedName, title, forceDownload);
}