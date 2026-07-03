export class OCRService {
  private static getBaseUrl(): string {
    if (typeof window === 'undefined') return '';
    const { hostname, protocol } = window.location;
    // If running inside Capacitor webview (which has custom protocol or localhost without standard dev port 3000)
    if (
      protocol.startsWith('capacitor') || 
      (hostname === 'localhost' && !window.location.port)
    ) {
      // Dynamic fallback to the primary hosted development/production domain
      return 'https://ais-dev-dbsdm2xi3l7sfdv6bixrww-589811072691.asia-east1.run.app';
    }
    return '';
  }

  public static async processImage(base64Data: string, documentTitle: string): Promise<any> {
    // Use our secure server-side Gemini Document AI API
    try {
      const baseUrl = this.getBaseUrl();
      const response = await fetch(`${baseUrl}/api/gemini/analyze`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          base64Data,
          mimeType: 'image/jpeg',
          documentTitle,
          targetLanguage: 'English',
          appName: 'SafeScan'
        })
      });

      if (!response.ok) {
        throw new Error(`Server returned status code ${response.status}`);
      }

      const result = await response.json();
      if (!result.success || !result.data) {
        throw new Error(result.error || "Failed to analyze document text");
      }

      return {
        success: true,
        data: result.data
      };
    } catch (error: any) {
      console.error('[OCRService] Cloud Gemini OCR scan error:', error);
      throw new Error(`OCR processing failed. Detail: ${error.message || 'Network Error'}`);
    }
  }
}

