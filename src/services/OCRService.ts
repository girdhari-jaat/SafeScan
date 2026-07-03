import { TextRecognition } from '@capacitor-mlkit/text-recognition';
import { Filesystem, Directory } from '@capacitor/filesystem';

export class OCRService {
  public static async processImage(base64Data: string, documentTitle: string): Promise<any> {
    const tempFileName = `temp_ocr_${Date.now()}.jpg`;
    
    try {
      const writeResult = await Filesystem.writeFile({
        path: tempFileName,
        data: base64Data,
        directory: Directory.Cache
      });

      const result = await TextRecognition.recognizeText({
        path: writeResult.uri,
      });

      // Cleanup
      await Filesystem.deleteFile({
        path: tempFileName,
        directory: Directory.Cache
      }).catch(console.error);

      if (!result || !result.text) {
         throw new Error("No text found in the document");
      }

      const text = result.text;
      
      // Basic extraction simulating Gemini
      const summaryText = text.substring(0, 150) + (text.length > 150 ? '...' : '');

      return {
        success: true,
        data: {
          documentType: 'Document',
          detectedLanguage: 'Auto',
          summaryText: `Offline OCR completed. ${summaryText}`,
          extractedFields: [
            { label: 'Document Name', value: documentTitle },
            { label: 'Word Count', value: text.split(/\s+/).length.toString() }
          ],
          fullTranscript: text
        }
      };
    } catch (error: any) {
      console.error('[OCRService] scan error:', error);
      throw error;
    }
  }
}
