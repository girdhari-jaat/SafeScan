import { CapacitorPluginMlKitTextRecognition } from '@pantrist/capacitor-plugin-ml-kit-text-recognition';
import { Capacitor } from '@capacitor/core';

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
    // 1. If we are running on a native platform, try offline ML Kit OCR first
    if (Capacitor.isNativePlatform()) {
      try {
        console.log('[OCRService] Native platform detected, running local ML Kit OCR...');
        const cleanBase64 = base64Data.split(',')[1] || base64Data;
        const ocrResult = await CapacitorPluginMlKitTextRecognition.detectText({
          base64Image: cleanBase64
        });

        if (ocrResult && ocrResult.text) {
          const parsedLocalData = this.parseTextOffline(ocrResult.text);
          return {
            success: true,
            data: parsedLocalData
          };
        }
      } catch (nativeError: any) {
        console.warn('[OCRService] Local native OCR failed or is not configured. Falling back to Cloud OCR:', nativeError);
        // Fall through to cloud fallback
      }
    }

    // 2. Web or fallback: Use our secure server-side Gemini Document AI API
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

  private static parseTextOffline(text: string): any {
    let documentType = 'Unclassified Document';
    const textLower = text.toLowerCase();
    
    if (textLower.includes('invoice') || textLower.includes('bill to') || textLower.includes('purchase order')) {
      documentType = 'Invoice';
    } else if (textLower.includes('receipt') || textLower.includes('cashier') || textLower.includes('total due') || textLower.includes('change due')) {
      documentType = 'Receipt';
    } else if (textLower.includes('passport') || textLower.includes('id card') || textLower.includes('driver license') || textLower.includes('identity')) {
      documentType = 'ID Card';
    } else if (textLower.includes('letter') || textLower.includes('dear') || textLower.includes('sincerely')) {
      documentType = 'Letter';
    } else if (textLower.includes('prescription') || textLower.includes('rx') || textLower.includes('patient')) {
      documentType = 'Medical Prescription';
    } else if (textLower.includes('bank statement') || textLower.includes('account statement')) {
      documentType = 'Bank Statement';
    }

    let detectedLanguage = 'English';
    if (textLower.includes('und') || textLower.includes('der') || textLower.includes('die')) {
      detectedLanguage = 'German';
    } else if (textLower.includes('le ') || textLower.includes('la ') || textLower.includes('les ')) {
      detectedLanguage = 'French';
    } else if (textLower.includes('el ') || textLower.includes('los ') || textLower.includes('las ')) {
      detectedLanguage = 'Spanish';
    }

    const extractedFields: Array<{ label: string; value: string }> = [];
    
    // Date (e.g. DD/MM/YYYY, YYYY-MM-DD)
    const dateRegex = /\b(\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{4}[-/.]\d{1,2}[-/.]\d{1,2})\b/;
    const dateMatch = text.match(dateRegex);
    if (dateMatch) {
      extractedFields.push({ label: 'Date', value: dateMatch[1] });
    }

    // Invoice Number
    const invRegex = /(invoice|inv|receipt|ticket)\s*(number|no\.?|#)?\s*[:#-]?\s*([a-z0-9-]+)/i;
    const invMatch = text.match(invRegex);
    if (invMatch) {
      extractedFields.push({ label: 'Invoice/Receipt No', value: invMatch[3] });
    }

    // Amount / Total
    const totalRegex = /(total|grand total|amount due|sum|net)\s*[:=-]?\s*([$€£¥₹]?\s*\d+[.,]\d{2})/i;
    const totalMatch = text.match(totalRegex);
    if (totalMatch) {
      extractedFields.push({ label: 'Total Amount', value: totalMatch[2] });
    }

    // Email
    const emailRegex = /\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b/;
    const emailMatch = text.match(emailRegex);
    if (emailMatch) {
      extractedFields.push({ label: 'Email', value: emailMatch[0] });
    }

    // Phone
    const phoneRegex = /(\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}/;
    const phoneMatch = text.match(phoneRegex);
    if (phoneMatch) {
      extractedFields.push({ label: 'Phone', value: phoneMatch[0] });
    }

    let summaryText = `Offline OCR completed successfully on a native device. Recognized ${text.split(/\s+/).length} words.`;
    if (extractedFields.length > 0) {
      summaryText += ' Extracted key details: ' + extractedFields.map(f => `${f.label}: ${f.value}`).join(', ') + '.';
    }

    return {
      documentType,
      detectedLanguage,
      summaryText,
      extractedFields,
      fullTranscript: text
    };
  }
}