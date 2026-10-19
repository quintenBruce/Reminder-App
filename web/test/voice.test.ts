import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { encodeWav, microphoneErrorMessage, pcmInputLevel, startVoiceRecording, withTimeout } from '../src/voice';

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('WAV microphone upload', () => {
  it('writes a valid mono 16 kHz PCM file from 48 kHz chunks', async () => {
    const samples = new Float32Array(24000);
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
    expect(header.getUint32(40, true)).toBe(16000);
    expect(header.getInt16(44, true)).toBeGreaterThan(15000);
  });

  it('rejects a capture with no audio samples', () => {
    expect(() => encodeWav([], 48000)).toThrow('No microphone audio was captured');
  });

  it('rejects very short and silent captures before upload', () => {
    expect(() => encodeWav([new Float32Array(4800).fill(0.5)], 48000)).toThrow('too short');
    expect(() => encodeWav([new Float32Array(24000)], 48000)).toThrow('captured silence');
    expect(pcmInputLevel(new Float32Array(4096))).toBe(0);
    expect(pcmInputLevel(new Float32Array(4096).fill(0.5))).toBe(1);
  });
});

describe('audio worklet', () => {
  it('captures a live second channel when the first is silent and flushes on stop', () => {
    const messages: { type: string; samples?: Float32Array }[] = [];
    let Processor: new () => { port: { onmessage: ((event: { data: string }) => void) | null }; process: (inputs: Float32Array[][], outputs: Float32Array[][]) => boolean };
    const code = readFileSync('web/public/pcm-recorder.js', 'utf8');
    runInNewContext(code, {
      Float32Array,
      AudioWorkletProcessor: class { port = { onmessage: null, postMessage: (message: { type: string; samples?: Float32Array }) => { messages.push(message); } }; },
      registerProcessor: (_name: string, constructor: typeof Processor) => { Processor = constructor; },
    });
    const recorder = new Processor!();
    const silent = new Float32Array(128);
    const speech = new Float32Array(128).fill(0.25);
    expect(recorder.process([[silent, speech]], [[new Float32Array(128)]])).toBe(true);
    recorder.port.onmessage?.({ data: 'stop' });
    expect(messages.map(message => message.type)).toEqual(['samples', 'stopped']);
    expect(messages[0].samples?.length).toBe(128);
    expect(messages[0].samples?.[0]).toBe(0.25);
  });
});

describe('microphone startup', () => {
  it('explains a denied microphone permission', () => {
    expect(microphoneErrorMessage(new DOMException('Denied', 'NotAllowedError')))
      .toContain('Allow it in Firefox');
  });

  it('times out a pending audio operation', async () => {
    vi.useFakeTimers();
    const pending = withTimeout(new Promise<void>(() => undefined), 1000, 'Audio did not start.');
    const failure = expect(pending).rejects.toThrow('Audio did not start.');
    await vi.advanceTimersByTimeAsync(1000);
    await failure;
  });

  it('uses an Ogg browser recorder when AudioContext never resumes', async () => {
    vi.useFakeTimers();
    const track = { stop: vi.fn() };
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [track] }) } });
    vi.stubGlobal('AudioContext', class {
      resume() { return new Promise<void>(() => undefined); }
      close() { return Promise.resolve(); }
    });
    vi.stubGlobal('MediaRecorder', class {
      static isTypeSupported(type: string) { return type === 'audio/ogg'; }
      mimeType = 'audio/ogg';
      state = 'inactive';
      ondataavailable: ((event: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      onerror: (() => void) | null = null;
      constructor(_stream: MediaStream, _options: { mimeType: string }) {}
      start() { this.state = 'recording'; }
      stop() {
        this.state = 'inactive';
        this.ondataavailable?.({ data: new Blob(['audio'], { type: this.mimeType }) });
        this.onstop?.();
      }
    });

    const starting = startVoiceRecording();
    await vi.advanceTimersByTimeAsync(2600);
    const capture = await starting;
    expect(capture.mode).toBe('browser');
    const audio = await capture.stop();
    expect(audio.type).toBe('audio/ogg');
    expect(audio.size).toBeGreaterThan(0);
    expect(track.stop).toHaveBeenCalled();
  });

  it('uses the browser recorder when a running worklet produces no samples', async () => {
    vi.useFakeTimers();
    const track = { stop: vi.fn() };
    const close = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [track] }) } });
    vi.stubGlobal('AudioContext', class {
      audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) };
      destination = {};
      resume() { return Promise.resolve(); }
      close = close;
      createMediaStreamSource() { return { connect: vi.fn(), disconnect: vi.fn() }; }
      createGain() { return { gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() }; }
    });
    vi.stubGlobal('AudioWorkletNode', class {
      port = { onmessage: null, postMessage: vi.fn() };
      connect() {}
      disconnect() {}
    });
    vi.stubGlobal('MediaRecorder', class {
      static isTypeSupported(type: string) { return type === 'audio/webm'; }
      mimeType = 'audio/webm';
      state = 'inactive';
      ondataavailable: ((event: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      start() { this.state = 'recording'; }
      stop() { this.state = 'inactive'; this.ondataavailable?.({ data: new Blob(['audio']) }); this.onstop?.(); }
    });

    const starting = startVoiceRecording();
    await vi.advanceTimersByTimeAsync(2600);
    const capture = await starting;
    expect(capture.mode).toBe('browser');
    expect(close).toHaveBeenCalled();
    await capture.cancel();
    expect(track.stop).toHaveBeenCalled();
  });

  it('stops a working worklet and returns captured WAV audio', async () => {
    vi.useFakeTimers();
    const track = { stop: vi.fn() };
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [track] }) } });
    vi.stubGlobal('AudioContext', class {
      audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) };
      destination = {};
      sampleRate = 48000;
      resume() { return Promise.resolve(); }
      close() { return Promise.resolve(); }
      createMediaStreamSource() { return { connect: vi.fn(), disconnect: vi.fn() }; }
      createGain() { return { gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() }; }
    });
    vi.stubGlobal('AudioWorkletNode', class {
      port = {
        onmessage: null as ((event: { data: unknown }) => void) | null,
        postMessage: (message: string) => {
          if (message === 'stop') this.port.onmessage?.({ data: { type: 'stopped' } });
        },
      };
      connect() {
        setTimeout(() => this.port.onmessage?.({ data: { type: 'samples', samples: new Float32Array(24000).fill(0.5) } }), 1);
      }
      disconnect() {}
    });

    const starting = startVoiceRecording();
    await vi.advanceTimersByTimeAsync(10);
    const capture = await starting;
    expect(capture.mode).toBe('wav');
    const audio = await capture.stop();
    expect(audio.type).toBe('audio/wav');
    expect(audio.size).toBeGreaterThan(44);
    expect(track.stop).toHaveBeenCalled();
  });
});
