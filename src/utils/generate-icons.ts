import fs from 'fs';
import path from 'path';

const srcIconPath = path.join(process.cwd(), 'src', 'assets', 'images', 'scanner_icon_512_1782123210919.jpg');
const publicDir = path.join(process.cwd(), 'public');

const targets = [
  'icon16.png',
  'icon48.png',
  'icon128.png',
  'icon-512.png'
];

if (fs.existsSync(srcIconPath)) {
  for (const target of targets) {
    const destPath = path.join(publicDir, target);
    fs.copyFileSync(srcIconPath, destPath);
    // console.log(`Copied ${srcIconPath} -> ${destPath}`);
  }
} else {
  // console.error(`Source icon not found at: ${srcIconPath}`);
}
