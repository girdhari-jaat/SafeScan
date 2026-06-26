import { PAPER_RATIOS, CARD_RATIOS } from '../../constants';
import { Point } from './types';

export function getDefaultQuad(_w: number, _h: number, _scanMode: string): { tl: Point, tr: Point, br: Point, bl: Point } {
  // 70% frame coverage for fallback
  const marginW = 15;
  const marginH = 15;
  return {
    tl: { x: marginW, y: marginH },
    tr: { x: 100 - marginW, y: marginH },
    br: { x: 100 - marginW, y: 100 - marginH },
    bl: { x: marginW, y: 100 - marginH }
  };
}

/**
 * Organizes quadrilateral points in clockwise order:
 * Top-Left, Top-Right, Bottom-Right, Bottom-Left
 */
export function orderPoints(pts: Point[]): Point[] {
  if (pts.length !== 4) return pts;
  const s = [...pts].sort((a, b) => a.y - b.y);
  const t = s.slice(0, 2).sort((a, b) => a.x - b.x);
  const b = s.slice(2, 4).sort((a, b) => a.x - b.x);
  return [t[0], t[1], b[1], b[0]];
}

function getAngle(p: Point, a: Point, b: Point): number {
  const dx1 = a.x - p.x;
  const dy1 = a.y - p.y;
  const dx2 = b.x - p.x;
  const dy2 = b.y - p.y;
  const len1 = Math.hypot(dx1, dy1);
  const len2 = Math.hypot(dx2, dy2);
  if (len1 < 1e-4 || len2 < 1e-4) return 90;
  const dot = dx1 * dx2 + dy1 * dy2;
  const cosTheta = Math.max(-1, Math.min(1, dot / (len1 * len2)));
  return Math.acos(cosTheta) * (180 / Math.PI);
}

/**
 * Reconstructs a single skewed/glitched corner of a quadrilateral if the other
 * 3 corners form a robust orthogonal shape. Extremely resilient to local noise/shadows!
 */
export function refineSkewedCorner(pts: Point[]): Point[] {
  if (pts.length !== 4) return pts;
  
  const tl = pts[0];
  const tr = pts[1];
  const br = pts[2];
  const bl = pts[3];
  
  const angleTL = getAngle(tl, tr, bl);
  const angleTR = getAngle(tr, tl, br);
  const angleBR = getAngle(br, tr, bl);
  const angleBL = getAngle(bl, tl, br);
  
  const devTL = Math.abs(angleTL - 90);
  const devTR = Math.abs(angleTR - 90);
  const devBR = Math.abs(angleBR - 90);
  const devBL = Math.abs(angleBL - 90);
  
  const devs = [
    { index: 0, dev: devTL, pt: tl },
    { index: 1, dev: devTR, pt: tr },
    { index: 2, dev: devBR, pt: br },
    { index: 3, dev: devBL, pt: bl }
  ];
  
  devs.sort((a, b) => b.dev - a.dev);
  
  const worst = devs[0];
  const secondWorst = devs[1];
  
  // If one corner is noticeably skewed (> 5.5 degrees deviation) and its deviation is significantly worse 
  // than the other corners (either twice as bad, or the others are highly square under 6.5 degrees), 
  // we rebuild that corner to form a perfect orthogonal parallelogram matching the other 3 stable corners.
  const skewRatio = worst.dev / Math.max(0.5, secondWorst.dev);
  if (worst.dev > 5.5 && (skewRatio > 1.35 || secondWorst.dev < 6.5)) {
    const result = [...pts];
    if (worst.index === 0) {
      result[0] = { x: tr.x + bl.x - br.x, y: tr.y + bl.y - br.y };
    } else if (worst.index === 1) {
      result[1] = { x: tl.x + br.x - bl.x, y: tl.y + br.y - bl.y };
    } else if (worst.index === 2) {
      result[2] = { x: tr.x + bl.x - tl.x, y: tr.y + bl.y - tl.y };
    } else if (worst.index === 3) {
      result[3] = { x: tl.x + br.x - tr.x, y: tl.y + br.y - tr.y };
    }
    return result;
  }
  
  return pts;
}

/**
 * Calculates the exact area of any polygon using the Shoelace formula (Gauss's area formula).
 */
export function polygonArea(p: Point[]): number {
  let a = 0;
  for (let i = 0; i < p.length; i++) {
    const j = (i + 1) % p.length;
    a += p[i].x * p[j].y - p[j].x * p[i].y;
  }
  return Math.abs(a / 2);
}

/**
 * Verifies if a detected polygon's aspect ratio matches standard documents or cards with tolerance.
 */
export function filterBestQuad(c: Point[], w: number, h: number, scanMode: string = 'paper'): Point[] {
  if (c.length !== 4) return [];
  const a = polygonArea(c);
  
  const isManualCheck = scanMode.startsWith('manual_');
  const cleanScanMode = isManualCheck ? scanMode.replace('manual_', '') : scanMode;
  
  const isCardMode = ['card', 'grid', 'cnic', 'idcard'].includes(cleanScanMode);
  const minAreaRatio = isManualCheck ? 0.03 : 0.08;
  const maxAreaRatio = isManualCheck ? 0.999 : (isCardMode ? 0.94 : 0.99);
  
  // Rule: Area coverage of search bounding space
  if (a < w * h * minAreaRatio || a > w * h * maxAreaRatio) return [];
  
  const W = Math.hypot(c[1].x - c[0].x, c[1].y - c[0].y);
  const H = Math.hypot(c[3].x - c[0].x, c[3].y - c[0].y);
  const r = W / H;
  
  const tolerance = isManualCheck ? 0.45 : 0.25; // Far more relaxed ratio tolerance for manual crop
  
  let isValid = false;
  if (isCardMode) {
    const cardRatio = CARD_RATIOS.LANDSCAPE; // 1.586
    isValid = Math.abs(r - cardRatio) < tolerance || Math.abs(r - (1 / cardRatio)) < tolerance;
  } else {
    const ratio34 = PAPER_RATIOS.THREE_FOUR; // 0.75
    const ratioA4 = PAPER_RATIOS.A4; // 0.707
    isValid = 
      Math.abs(r - ratio34) < tolerance || Math.abs(r - (1 / ratio34)) < tolerance ||
      Math.abs(r - ratioA4) < tolerance || Math.abs(r - (1 / ratioA4)) < tolerance ||
      isManualCheck; // Manual mode always falls back to valid if RANSAC lines intersected
  }
  
  return isValid ? c : [];
}
