import { Point, Line } from './types';

/**
 * Robust RANSAC-based line fitting to exclude noise/outliers and fit high-accuracy boundary equations.
 * Inspired by OpenCV's line fitting algorithms.
 */
export function fitLineRANSAC(
  points: Point[],
  isVertical: boolean,
  iterations: number = 30,
  threshold: number = 2.0
): { line: Line, inliersCount: number } | null {
  if (points.length < 2) return null;
  
  let bestInliers: Point[] = [];
  let bestLine: Line | null = null;
  const earlyExitCount = points.length * 0.82;
  
  for (let iter = 0; iter < iterations; iter++) {
    const p1 = points[Math.floor(Math.random() * points.length)];
    const p2 = points[Math.floor(Math.random() * points.length)];
    if (p1 === p2) continue;
    
    let m = 0;
    let c = 0;
    
    if (isVertical) {
      if (Math.abs(p2.y - p1.y) < 0.001) continue;
      m = (p2.x - p1.x) / (p2.y - p1.y);
      c = p1.x - m * p1.y;
    } else {
      if (Math.abs(p2.x - p1.x) < 0.001) continue;
      m = (p2.y - p1.y) / (p2.x - p1.x);
      c = p1.y - m * p1.x;
    }
    
    const inliers: Point[] = [];
    const norm = Math.sqrt(1 + m * m);
    
    for (const p of points) {
      const dist = isVertical ? Math.abs(p.x - (m * p.y + c)) / norm : Math.abs(p.y - (m * p.x + c)) / norm;
      if (dist < threshold) {
        inliers.push(p);
      }
    }
    
    if (inliers.length > bestInliers.length) {
      bestInliers = inliers;
      bestLine = { m, c };
      if (bestInliers.length >= earlyExitCount) break;
    }
  }
  
  // Refined Least Squares over detected inliers
  if (bestInliers.length >= 2) {
    let sumX = 0, sumY = 0, sumXY = 0, sumXX = 0, sumYY = 0;
    const N = bestInliers.length;
    for (const p of bestInliers) {
      sumX += p.x;
      sumY += p.y;
      sumXY += p.x * p.y;
      sumXX += p.x * p.x;
      sumYY += p.y * p.y;
    }
    
    if (isVertical) {
      const denom = (N * sumYY - sumY * sumY);
      if (Math.abs(denom) > 0.001) {
        const m = (N * sumXY - sumX * sumY) / denom;
        const c = (sumX - m * sumY) / N;
        return { line: { m, c }, inliersCount: N };
      }
    } else {
      const denom = (N * sumXX - sumX * sumX);
      if (Math.abs(denom) > 0.001) {
        const m = (N * sumXY - sumX * sumY) / denom;
        const c = (sumY - m * sumX) / N;
        return { line: { m, c }, inliersCount: N };
      }
    }
  }
  
  return bestLine ? { line: bestLine, inliersCount: bestInliers.length } : null;
}

/**
 * Calculates the exact pixel intersection of vertical-ish and horizontal-ish lines.
 */
export function intersectLines(vertical: Line, horizontal: Line, defaultX: number, defaultY: number): Point {
  const denom = 1 - vertical.m * horizontal.m;
  if (Math.abs(denom) < 0.001) return { x: defaultX, y: defaultY };
  const x = (vertical.m * horizontal.c + vertical.c) / denom;
  return { x, y: horizontal.m * x + horizontal.c };
}
