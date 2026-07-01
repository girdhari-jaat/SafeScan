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

    const isImage =
      fileName.toLowerCase().endsWith(".jpg") ||
      fileName.toLowerCase().endsWith(".png");

    if (isImage) {
      try {
        // Step 1: Write image to a temporary cache file
        const tempPath = `temp_${Date.now()}_${fileName}`;
        const tempFile = await Filesystem.writeFile({
          path: tempPath,
          data: base64Data,
          directory: Directory.Cache,
        });

        // Step 2: Use the @capacitor-community/media plugin to save it to DCIM/SafeScan gallery
        const { Media } = await import("@capacitor-community/media");
        
        let albumIdentifier: string | undefined;
        try {
          const albumsResult = await Media.getAlbums();
          let targetAlbum = albumsResult.albums.find(
            (a) => a.name.toLowerCase() === "safescan"
          );

          if (!targetAlbum) {
            await Media.createAlbum({ name: "SafeScan" });
            const updatedAlbums = await Media.getAlbums();
            targetAlbum = updatedAlbums.albums.find(
              (a) => a.name.toLowerCase() === "safescan"
            );
          }
          
          if (targetAlbum) {
            albumIdentifier = targetAlbum.identifier;
          }
        } catch (albumErr) {
          console.warn("Could not check/create album via getAlbums, will let savePhoto handle default or fallback:", albumErr);
        }

        const fileNameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.')) || fileName;
        await Media.savePhoto({
          path: tempFile.uri,
          albumIdentifier: albumIdentifier || "SafeScan",
          fileName: fileNameWithoutExt,
        });

        // Clean up the temp cache file
        try {
          await Filesystem.deleteFile({
            path: tempPath,
            directory: Directory.Cache,
          });
        } catch (e) {
          console.error("Error deleting temp file:", e);
        }

        await Toast.show({
          text: `Image saved to Gallery in DCIM/SafeScan/`,
          duration: "short",
          position: "bottom",
        });

        return; // Exit here as we've handled the image flow
      } catch (err) {
        console.error("Error saving image via Media Plugin:", err);

        // Fallback: Save directly to the Android media directory (WhatsApp-style scoped storage)
        const mediaPath = `Android/media/com.safescan.app/SafeScan/${fileName}`;
        try {
          const writeResult = await Filesystem.writeFile({
            path: mediaPath,
            data: base64Data,
            directory: Directory.External,
            recursive: true,
          });

          // Trigger native media scan for instant gallery refresh
          try {
            const { Media } = await import("@capacitor-community/media");
            if (Media && typeof (Media as any).scanFile === "function") {
              await (Media as any).scanFile({ path: writeResult.uri });
            }
          } catch (scanErr) {
            console.warn("Could not scan file:", scanErr);
          }

          await Toast.show({
            text: `Image saved to Android/media/com.safescan.app/SafeScan`,
            duration: "short",
            position: "bottom",
          });

          return; // Exit here as we've handled the image flow
        } catch (fallbackErr1) {
          console.error("Error saving image to Android media folder:", fallbackErr1);
          // Fallback 2: Save to standard external folder
          try {
            const writeResult = await Filesystem.writeFile({
              path: `SafeScan/${fileName}`,
              data: base64Data,
              directory: Directory.External,
              recursive: true,
            });

            // Trigger native media scan for instant gallery refresh
            try {
              const { Media } = await import("@capacitor-community/media");
              if (Media && typeof (Media as any).scanFile === "function") {
                await (Media as any).scanFile({ path: writeResult.uri });
              }
            } catch (scanErr) {
              console.warn("Could not scan file:", scanErr);
            }

            await Toast.show({
              text: `Image saved in SafeScan folder`,
              duration: "short",
              position: "bottom",
            });
          } catch (fallbackErr2) {
            console.error("Error saving image to fallback folder:", fallbackErr2);
            // Standard web fallback trigger
            const downloadUrl = URL.createObjectURL(blob);
            const link = document.createElement("a");
            link.href = downloadUrl;
            link.download = fileName;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(downloadUrl);

            await Toast.show({
              text: `Saving image ${fileName}... Check your downloads.`,
              duration: "short",
              position: "bottom",
            });
          }
          return;
        }
      }
    }

    if (forceSaveDirectly) {
      // Save directly to the Android media directory (WhatsApp-style scoped storage)
      const mediaPath = `Android/media/com.safescan.app/SafeScan/${fileName}`;
      try {
        const writeResult = await Filesystem.writeFile({
          path: mediaPath,
          data: base64Data,
          directory: Directory.External,
          recursive: true,
        });

        // Trigger native media scan for instant gallery refresh
        try {
          const { Media } = await import("@capacitor-community/media");
          if (Media && typeof (Media as any).scanFile === "function") {
            await (Media as any).scanFile({ path: writeResult.uri });
          }
        } catch (scanErr) {
          console.warn("Could not scan file:", scanErr);
        }

        await Toast.show({
          text: `PDF saved to Android/media/com.safescan.app/SafeScan`,
          duration: "short",
          position: "bottom",
        });

        return;
      } catch (err) {
        console.error("Error saving PDF to Android media folder:", err);
        // Fallback 1: Save to standard external folder
        try {
          const writeResult = await Filesystem.writeFile({
            path: `SafeScan/${fileName}`,
            data: base64Data,
            directory: Directory.External,
            recursive: true,
          });

          // Trigger native media scan for instant gallery refresh
          try {
            const { Media } = await import("@capacitor-community/media");
            if (Media && typeof (Media as any).scanFile === "function") {
              await (Media as any).scanFile({ path: writeResult.uri });
            }
          } catch (scanErr) {
            console.warn("Could not scan file:", scanErr);
          }

          await Toast.show({
            text: `PDF saved in SafeScan folder`,
            duration: "short",
            position: "bottom",
          });
        } catch (fallbackErr) {
          console.error("Error saving PDF to fallback folder:", fallbackErr);
          // Fallback 2: Standard web fallback trigger
          const downloadUrl = URL.createObjectURL(blob);
          const link = document.createElement("a");
          link.href = downloadUrl;
          link.download = fileName;
          document.body.appendChild(link);
          link.click();
          document.body.removeChild(link);
          URL.revokeObjectURL(downloadUrl);

          await Toast.show({
            text: `Saving PDF ${fileName}... Check your downloads.`,
            duration: "short",
            position: "bottom",
          });
        }
        return;
      }
    }

    const targetDirectory = Directory.External;

    const writeResult = await Filesystem.writeFile({
      path: `SafeScan/${fileName}`,
      data: base64Data,
      directory: targetDirectory,
      recursive: true,
    });

    // Trigger native media scan for instant gallery refresh
    try {
      const { Media } = await import("@capacitor-community/media");
      if (Media && typeof (Media as any).scanFile === "function") {
        await (Media as any).scanFile({ path: writeResult.uri });
      }
    } catch (scanErr) {
      console.warn("Could not scan file:", scanErr);
    }

    await Toast.show({
      text: `PDF saved to SafeScan folder`,
      duration: "short",
      position: "bottom",
    });

    await Share.share({
      title: fileName,
      text: "Scanned Document",
      files: [writeResult.uri],
    });
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
        // User voluntarily dismissed share menu - stop execution, do not download fallback
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
