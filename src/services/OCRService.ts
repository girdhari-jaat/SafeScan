
export class OCRService {
  public static async processImage(base64Data: string, documentTitle: string): Promise<any> {
    try {
      const result = await Ocr.detectText({
        base64: base64Data,
      });

      if (!result || !result.textDetections || result.textDetections.length === 0) {
         throw new Error("No text found in the document");
      }

      const text = result.textDetections.map(d => d.text).join('\n');
      
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
