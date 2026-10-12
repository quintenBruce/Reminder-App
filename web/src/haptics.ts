type HapticKind = 'tap' | 'success';
type VibrationDevice = { vibrate?: (pattern: number | number[]) => boolean };

const patterns: Record<HapticKind, number | number[]> = {
  tap: 12,
  success: [18, 35, 18],
};

export function playHaptic(kind: HapticKind, device: VibrationDevice = navigator): boolean {
  if (typeof device.vibrate !== 'function') return false;
  try { return device.vibrate(patterns[kind]); }
  catch { return false; }
}
