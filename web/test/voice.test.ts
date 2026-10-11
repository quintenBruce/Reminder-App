import { describe, expect, it } from 'vitest';
import { encodeWav } from '../src/voice';

describe('WAV microphone upload', () => {
  it('writes a valid mono 16 kHz PCM file from 48 kHz chunks', async () => {
    const samples = new Float32Array(4800);
    samples.fill(0.5);
    const blob = encodeWav([samples.subarray(0, 2000), samples.subarray(2000)], 48000);
    const bytes = await blob.arrayBuffer();
    const header = new DataView(bytes);
    const label = (offset: number, length: number) => String.fromCharCode(...new Uint8Array(bytes, offset, length));
    expect(blob.type).toBe('audio/wav');
    expect(label(0, 4)).toBe('RIFF');
    expect(label(8, 4)).toBe('WAVE');
    expect(header.getUint16(22, true)).toBe(1);
    expect(header.getUint32(24, true)).toBe(16000);
    expect(header.getUint32(40, true)).toBe(3200);
    expect(header.getInt16(44, true)).toBeGreaterThan(15000);
  });

  it('rejects a capture with no audio samples', () => {
    expect(() => encodeWav([], 48000)).toThrow('No microphone audio was captured');
  });
});
