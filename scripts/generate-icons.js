import { Jimp } from 'jimp';
import fs from 'fs';

async function generateIcons() {
    console.log('Generating icons...');
    const sizes = [192, 512, 96];
    
    for (const size of sizes) {
        const image = new Jimp({ width: size, height: size, color: 0x09090bFF }); // Theme color
        
        // Draw Document (white rectangle)
        const docW = size * 0.55;
        const docH = size * 0.70;
        const docX = (size - docW) / 2;
        const docY = (size - docH) / 2;
        
        for (let y = docY; y < docY + docH; y++) {
            for (let x = docX; x < docX + docW; x++) {
                image.setPixelColor(0xFFFFFFFF, x, y);
            }
        }
        
        // Draw document text lines
        const lineMarginX = size * 0.08;
        const lineStartY = docY + size * 0.1;
        const lineSpacing = size * 0.08;
        const lineH = Math.max(1, Math.floor(size * 0.015));
        
        for (let i = 0; i < 4; i++) {
            const lineY = lineStartY + (i * lineSpacing);
            const lineW = (i === 3) ? (docW - lineMarginX * 2) * 0.6 : (docW - lineMarginX * 2);
            for (let y = lineY; y < lineY + lineH; y++) {
                for (let x = docX + lineMarginX; x < docX + lineMarginX + lineW; x++) {
                    image.setPixelColor(0xCBD5E1FF, x, y);
                }
            }
        }
        
        // Draw Camera (purple rectangle at bottom right)
        const camW = size * 0.35;
        const camH = size * 0.25;
        const camX = docX + docW - camW * 0.6;
        const camY = docY + docH - camH * 0.6;
        const camColor = 0x8B5CF6FF; // Purple 500
        
        // Camera body
        for (let y = camY; y < camY + camH; y++) {
            for (let x = camX; x < camX + camW; x++) {
                image.setPixelColor(camColor, x, y);
            }
        }
        
        // Camera lens
        const lensRadius = camH * 0.35;
        const lensCx = camX + camW / 2;
        const lensCy = camY + camH / 2;
        
        for (let y = camY; y < camY + camH; y++) {
            for (let x = camX; x < camX + camW; x++) {
                const dx = x - lensCx;
                const dy = y - lensCy;
                if (dx*dx + dy*dy <= lensRadius*lensRadius) {
                    image.setPixelColor(0x09090bFF, x, y); // dark hole
                }
                const innerRadius = lensRadius * 0.6;
                if (dx*dx + dy*dy <= innerRadius*innerRadius) {
                    image.setPixelColor(0x3B82F6FF, x, y); // blue reflection
                }
            }
        }
        
        // Camera Flash
        const flashW = size * 0.04;
        const flashX = camX + camW * 0.8;
        const flashY = camY + camH * 0.2;
        for (let y = flashY; y < flashY + flashW; y++) {
            for (let x = flashX; x < flashX + flashW; x++) {
                image.setPixelColor(0xFFEAA7FF, x, y); // yellow flash
            }
        }

        if (size === 192) await image.write('./public/icon192.png');
        if (size === 512) await image.write('./public/icon512.png');
        if (size === 96) {
            await image.write('./public/shortcut_doc.png');
            await image.write('./public/shortcut_id.png');
        }
    }
    console.log('Icons generated.');
}

generateIcons().catch(console.error);
