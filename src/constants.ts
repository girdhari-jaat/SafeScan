// constants.ts 
// We only keep the core ratios for calculations to avoid mode constant issues.

// ===== RATIOS =====
export const PAPER_RATIOS = {
  A4: 210 / 297, // 1.4142 landscape (reciprocal of portrait)
  A4_PORTRAIT: 297 / 210, // 1.4142 portrait
  THREE_FOUR: 3 / 4 // 0.75 fallback
}

export const CARD_RATIOS = {
  LANDSCAPE: 85.6 / 53.98, // 1.586
  PORTRAIT: 53.98 / 85.6 // 0.63
}

// ===== EXPORT PRESETS =====
export const EXPORT_PRESETS = {
  fast: { width: 1240, dpi: 150, quality: 0.8 },
  standard: { width: 1654, dpi: 200, quality: 0.9 },
  high: { width: 2480, dpi: 300, quality: 0.95 }
} as const

export type ExportPreset = keyof typeof EXPORT_PRESETS

// ===== HELPER FUNCTIONS =====

export function getPaperRatio(rotation: number, fallback = false): number {
  const normalizedRotation = ((rotation % 360) + 360) % 360
  const isPortrait = normalizedRotation === 90 || normalizedRotation === 270

  if (fallback) return PAPER_RATIOS.THREE_FOUR
  return isPortrait ? PAPER_RATIOS.A4_PORTRAIT : PAPER_RATIOS.A4
}

export function getCardRatio(rotation: number): number {
  const normalizedRotation = ((rotation % 360) + 360) % 360
  const isPortrait = normalizedRotation === 90 || normalizedRotation === 270
  return isPortrait ? CARD_RATIOS.PORTRAIT : CARD_RATIOS.LANDSCAPE
}
