import { CapacitorPluginMlKitTextRecognition } from '@pantrist/capacitor-plugin-ml-kit-text-recognition';
import { Capacitor } from '@capacitor/core';

export const OCRService = {
  detectText: async (base64Image: string): Promise<string> => {
    if (Capacitor.isNativePlatform()) {
      try {
        const result = await CapacitorPluginMlKitTextRecognition.detectText({ base64Image });
        return result.text;
      } catch (error) {
        console.error('OCR Error:', error);
        throw error;
      }
    }
    return 'OCR is only supported on native Android/iOS devices.';
  }
};
