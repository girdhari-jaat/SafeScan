import express from "express";
import path from "path";
import fs from "fs";
import { createServer as createViteServer } from "vite";
import { GoogleGenAI, Type } from "@google/genai";

async function startServer() {
  const app = express();
  const PORT = 3000;

  app.use(express.json({ limit: '10mb' }));

  // API routes defined BEFORE Vite middleware
  app.get("/api/health", (req, res) => {
    res.json({ status: "ok", name: "SafeScan" });
  });

  app.get("/api/gemini/health", (req, res) => {
    res.json({
      status: "online",
      geminiConnected: !!process.env.GEMINI_API_KEY,
      testingEnvironment: process.env.NODE_ENV === "production" ? "SafeScan-Cloud-Production" : "SafeScan-Development-Host"
    });
  });

  app.post("/api/gemini/detect-edges", async (req, res) => {
    try {
      const { imageBase64 } = req.body;
      if (!imageBase64) {
        return res.status(400).json({ error: "Missing imageBase64" });
      }

      if (!process.env.GEMINI_API_KEY) {
        return res.status(500).json({ error: "GEMINI_API_KEY is not configured" });
      }

      const ai = new GoogleGenAI({
        apiKey: process.env.GEMINI_API_KEY,
        httpOptions: {
          headers: {
            'User-Agent': 'aistudio-build',
          }
        }
      });

      const response = await ai.models.generateContent({
        model: "gemini-2.5-flash",
        contents: [
          {
            role: "user",
            parts: [
              {
                text: "Analyze this image and find the exactly 4 main corners of the printed document or card in it. You MUST return the coordinates as relative decimal values strictly between 0.0 and 1.0 (where x=0.0, y=0.0 is the top-left corner and x=1.0, y=1.0 is the bottom-right corner of the image). Do NOT return pixel coordinates or percentage values (like 10 or 80). Identify the corners in the order of: top-left, top-right, bottom-right, bottom-left."
              },
              {
                inlineData: {
                  mimeType: "image/jpeg",
                  data: imageBase64
                }
              }
            ]
          }
        ],
        config: {
          temperature: 0.1,
          responseMimeType: "application/json",
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              points: {
                type: Type.ARRAY,
                items: {
                  type: Type.OBJECT,
                  properties: {
                    x: {
                      type: Type.NUMBER,
                      description: "X coordinate of the document corner. Must be a relative float value strictly between 0.0 and 1.0."
                    },
                    y: {
                      type: Type.NUMBER,
                      description: "Y coordinate of the document corner. Must be a relative float value strictly between 0.0 and 1.0."
                    }
                  },
                  required: ["x", "y"]
                },
                description: "Array of exactly 4 document corners in the following clockwise order: top-left, top-right, bottom-right, bottom-left."
              }
            },
            required: ["points"]
          }
        }
      });

      const text = response.text || "";
      let points = [];
      try {
        const parsed = JSON.parse(text);
        points = parsed.points || [];
      } catch (e) {
        // Strip markdown if present
        const cleaned = text.replace(/```json/g, "").replace(/```/g, "").trim();
        const parsed = JSON.parse(cleaned);
        points = parsed.points || parsed || [];
      }

      res.json({ points });
    } catch (error: any) {
      const errorMsg = error?.message || "Failed to process image";
      if (error?.status === 429 || errorMsg.includes("429") || errorMsg.includes("quota") || errorMsg.includes("limit") || errorMsg.includes("RESOURCE_EXHAUSTED")) {
        console.warn("Gemini Edge Detection: API Quota limit exceeded (429/RESOURCE_EXHAUSTED). Graceful fallback.");
        return res.status(429).json({ error: "Gemini API quota limit exceeded. Safely falling back to local device engine." });
      }
      console.warn("Gemini Edge Detection API warning:", errorMsg);
      res.status(500).json({ error: errorMsg });
    }
  });

  app.post("/api/gemini/analyze", async (req, res) => {
    try {
      const { base64Data, mimeType, documentTitle, targetLanguage, appName } = req.body;
      if (!base64Data) {
        return res.status(400).json({ success: false, error: "Missing base64Data" });
      }

      if (!process.env.GEMINI_API_KEY) {
        return res.status(500).json({ success: false, error: "GEMINI_API_KEY is not configured" });
      }

      const ai = new GoogleGenAI({
        apiKey: process.env.GEMINI_API_KEY,
        httpOptions: {
          headers: {
            'User-Agent': 'aistudio-build',
          }
        }
      });

      const response = await ai.models.generateContent({
        model: "gemini-2.5-flash",
        contents: [
          {
            role: "user",
            parts: [
              {
                text: `Analyze this scanned document image for the application "${appName || 'SafeScan'}". The document title is: "${documentTitle || 'Untitled'}". Perform high-quality OCR to extract all text, identify the document type, determine the language, write a professional summary of the document contents translated into "${targetLanguage || 'English'}", and extract any key field/value pairs (like names, dates, amounts, invoice numbers, reference numbers, etc.).`
              },
              {
                inlineData: {
                  mimeType: mimeType || "image/jpeg",
                  data: base64Data
                }
              }
            ]
          }
        ],
        config: {
          temperature: 0.2,
          responseMimeType: "application/json",
          responseSchema: {
            type: Type.OBJECT,
            properties: {
              documentType: {
                type: Type.STRING,
                description: "Type of document, e.g. Invoice, Receipt, ID Card, Letter, Book Page, etc."
              },
              detectedLanguage: {
                type: Type.STRING,
                description: "The primary language detected in the document text."
              },
              summaryText: {
                type: Type.STRING,
                description: `A concise professional summary of the document, completely translated into ${targetLanguage || 'English'}.`
              },
              extractedFields: {
                type: Type.ARRAY,
                items: {
                  type: Type.OBJECT,
                  properties: {
                    label: { type: Type.STRING, description: "Label or key of the field (e.g. Total, Invoice Date, Sender Name)." },
                    value: { type: Type.STRING, description: "Extracted value corresponding to the label." }
                  },
                  required: ["label", "value"]
                },
                description: "A list of relevant key-value pairs extracted from the document."
              },
              fullTranscript: {
                type: Type.STRING,
                description: "The complete verbatim OCR transcription of all readable text in the document."
              }
            },
            required: ["documentType", "detectedLanguage", "summaryText", "extractedFields", "fullTranscript"]
          }
        }
      });

      const text = response.text || "";
      let data;
      try {
        data = JSON.parse(text);
      } catch (e) {
        const cleaned = text.replace(/```json/g, "").replace(/```/g, "").trim();
        data = JSON.parse(cleaned);
      }

      res.json({ success: true, data });
    } catch (error: any) {
      const errorMsg = error?.message || "Failed to analyze document";
      if (error?.status === 429 || errorMsg.includes("429") || errorMsg.includes("quota") || errorMsg.includes("limit") || errorMsg.includes("RESOURCE_EXHAUSTED")) {
        console.warn("Gemini Document AI: API Quota limit exceeded (429/RESOURCE_EXHAUSTED).");
        return res.status(429).json({ success: false, error: "Gemini API quota limit exceeded. Please try again later." });
      }
      console.warn("Gemini Document AI warning:", errorMsg);
      res.status(500).json({ success: false, error: errorMsg });
    }
  });

  // Vite middleware for development
  if (process.env.NODE_ENV !== "production") {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    // Static assets for production
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath, {
      maxAge: '1y',
      setHeaders: (res, filePath) => {
        if (filePath.endsWith('.html')) {
          res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
        } else if (filePath.endsWith('.png')) {
          res.setHeader('Content-Type', 'image/png');
        } else if (filePath.endsWith('.jpg') || filePath.endsWith('.jpeg')) {
          res.setHeader('Content-Type', 'image/jpeg');
        } else if (filePath.endsWith('.svg')) {
          res.setHeader('Content-Type', 'image/svg+xml');
        } else if (filePath.endsWith('.json')) {
          res.setHeader('Content-Type', 'application/json');
        }
      }
    }));
  }

  // SPA fallback
  app.use((req, res, next) => {
    if (req.path.startsWith('/api')) {
      return next();
    }

    if (process.env.NODE_ENV !== "production") {
      // In dev, Vite handles this via middlewares
      res.status(404).send("Not Found");
    } else {
      const distPath = path.join(process.cwd(), 'dist');
      const ext = path.extname(req.path);
      if (ext && ext !== '.html') {
        res.status(404).send("Not Found");
        return;
      }
      res.sendFile(path.join(distPath, 'index.html'));
    }
  });

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
}

startServer();
