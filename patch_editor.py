import re

with open('src/components/Editor.tsx', 'r') as f:
    content = f.read()

pattern = re.compile(r'let parsed;\s*try \{\s*// Try barcode first\s*const barcodeResult = await BarcodeService\.processImage\(base64DataString\);\s*if \(barcodeResult\.success\) \{\s*parsed = barcodeResult;\s*\} else \{\s*parsed = \{ success: false, error: "No barcode found in the document" \};\s*\}\s*\} catch \(err: any\) \{\s*parsed = \{ success: false, error: err\.message \|\| "Local Barcode scan failed" \};\s*\}')

replacement = """let parsed;
      try {
        // Try barcode first
        const barcodeResult = await BarcodeService.processImage(base64DataString);
        if (barcodeResult.success) {
          parsed = barcodeResult;
        } else {
          // Fall back to OCR
          const ocrText = await OCRService.detectText(base64DataString);
          if (ocrText && ocrText.trim().length > 0 && ocrText !== 'OCR is only supported on native Android/iOS devices.') {
             parsed = {
               success: true,
               data: {
                 documentType: "OCR Text",
                 detectedLanguage: "Auto",
                 summaryText: "Text extracted via ML Kit",
                 extractedFields: [],
                 fullTranscript: ocrText
               }
             };
          } else {
             parsed = { success: false, error: "No text or barcode found in the document" };
          }
        }
      } catch (err: any) { 
        parsed = { success: false, error: err.message || "Local Barcode scan failed" };
      }"""

if pattern.search(content):
    content = pattern.sub(replacement, content, 1)
    with open('src/components/Editor.tsx', 'w') as f:
        f.write(content)
    print("Patch applied successfully.")
else:
    print("Target not found. Please check exact string.")
