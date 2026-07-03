import { BarcodeScanner, Barcode } from '@capacitor-mlkit/barcode-scanning';
import { Filesystem, Directory } from '@capacitor/filesystem';

export class BarcodeService {
  public static async processImage(base64Data: string): Promise<any> {
    const tempFileName = `temp_barcode_${Date.now()}.jpg`;
    
    try {
      const writeResult = await Filesystem.writeFile({
        path: tempFileName,
        data: base64Data,
        directory: Directory.Cache
      });

      const { barcodes } = await BarcodeScanner.readBarcodesFromImage({
        path: writeResult.uri,
      });

      // Cleanup
      await Filesystem.deleteFile({
        path: tempFileName,
        directory: Directory.Cache
      }).catch(console.error);

      if (!barcodes || barcodes.length === 0) {
        return { success: false, data: null };
      }

      // Format as Gemini response
      const transcript = barcodes.map(b => b.displayValue).join('\n');
      
      return {
        success: true,
        data: {
          documentType: 'Barcode/QR Code',
          detectedLanguage: 'N/A',
          summaryText: `Successfully extracted ${barcodes.length} barcode(s).`,
          extractedFields: barcodes.map((b, i) => ({
            label: `Barcode ${i + 1} (${b.format})`,
            value: b.displayValue || ''
          })),
          fullTranscript: transcript
        }
      };
    } catch (error: any) {
      console.error('[BarcodeService] scan error:', error);
      throw error;
    }
  }
}
