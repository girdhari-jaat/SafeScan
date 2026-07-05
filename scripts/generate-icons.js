import { Jimp } from 'jimp';
import fs from 'fs';

async function generateIcons() {
    console.log('Generating icons...');
    // Create placeholder icons since we can't easily convert SVG to PNG with jimp
    const sizes = [192, 512, 96];
    for (const size of sizes) {
        const image = new Jimp({ width: size, height: size, color: 0x09090bFF }); // Theme color
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
