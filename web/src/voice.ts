export type VoiceRecording = {
  stop: () => Promise<Blob>;
  cancel: () => Promise<void>;
  mode: 'wav' | 'browser';
  inputLevel?: () => number;
};

export function microphoneErrorMessage(error: unknown) {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError')
      return 'Microphone access was blocked. Allow it in Firefox’s site permissions and try again.';
    if (error.name === 'NotFoundError' || error.name === 'DevicesNotFoundError')
      return 'No microphone was found on this device.';
    if (error.name === 'NotReadableError' || error.name === 'TrackStartError')
      return 'Firefox could not use the microphone. Close other apps using it and try again.';
  }
  return error instanceof Error ? error.message : 'Could not start the microphone. Please try again.';
}

export function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms);
    promise.then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
  });
}

export function encodeWav(chunks: Float32Array[], sourceRate: number): Blob {
  if (!Number.isFinite(sourceRate) || sourceRate < 8000) throw new Error('Invalid microphone sample rate.');
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  if (!total) throw new Error('No microphone audio was captured. Please try again.');
  const source = new Float32Array(total);
  let offset = 0;
  for (const chunk of chunks) { source.set(chunk, offset); offset += chunk.length; }
  if (total / sourceRate < 0.25) throw new Error('Recording was too short. Speak after “Listening” appears, then tap to stop.');
  if (source.every(sample => Math.abs(sample) < 0.0005)) {
    throw new Error('The microphone captured silence. Check that it is not muted, then try again.');
  }

  const sampleRate = Math.min(16000, sourceRate);
  const sampleCount = Math.ceil(total * sampleRate / sourceRate);
  const buffer = new ArrayBuffer(44 + sampleCount * 2);
  const view = new DataView(buffer);
  const label = (at: number, value: string) => {
    for (let i = 0; i < value.length; i++) view.setUint8(at + i, value.charCodeAt(i));
  };
  label(0, 'RIFF'); view.setUint32(4, buffer.byteLength - 8, true);
  label(8, 'WAVE'); label(12, 'fmt '); view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true); view.setUint16(34, 16, true);
  label(36, 'data'); view.setUint32(40, sampleCount * 2, true);
  for (let i = 0; i < sampleCount; i++) {
    const position = i * sourceRate / sampleRate;
    const left = Math.floor(position);
    const fraction = position - left;
    const sample = source[left] * (1 - fraction) + source[Math.min(left + 1, total - 1)] * fraction;
    const clamped = Math.max(-1, Math.min(1, sample));
    view.setInt16(44 + i * 2, clamped < 0 ? clamped * 32768 : clamped * 32767, true);
  }
  return new Blob([buffer], { type: 'audio/wav' });
}

export function pcmInputLevel(samples: Float32Array): number {
  if (!samples.length) return 0;
  let energy = 0;
  for (const sample of samples) energy += sample * sample;
  return Math.min(1, Math.sqrt(energy / samples.length) * 12);
}

async function mediaRecorderFallback(stream: MediaStream): Promise<VoiceRecording> {
  const mimeType = ['audio/webm', 'audio/ogg', 'audio/mp4', 'audio/wav'].find(type => MediaRecorder.isTypeSupported(type));
  if (!mimeType) {
    stream.getTracks().forEach(track => track.stop());
    throw new Error('This browser does not support microphone recording.');
  }
  let recorder: MediaRecorder;
  const chunks: Blob[] = [];
  try {
    recorder = new MediaRecorder(stream, { mimeType });
    recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
    recorder.start();
  } catch (error) {
    stream.getTracks().forEach(track => track.stop());
    throw error;
  }
  return {
    mode: 'browser',
    stop: () => new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        stream.getTracks().forEach(track => track.stop());
        reject(new Error('Firefox did not finish the recording. Please try again.'));
      }, 3000);
      recorder.onerror = event => {
        clearTimeout(timer);
        stream.getTracks().forEach(track => track.stop());
        reject(new Error(`Recording failed: ${event.type}`));
      };
      recorder.onstop = () => {
        clearTimeout(timer);
        stream.getTracks().forEach(track => track.stop());
        const blob = new Blob(chunks, { type: recorder.mimeType || mimeType });
        blob.size ? resolve(blob) : reject(new Error('No microphone audio was captured. Please try again.'));
      };
      try { recorder.stop(); }
      catch (error) { clearTimeout(timer); stream.getTracks().forEach(track => track.stop()); reject(error); }
    }),
    cancel: async () => {
      recorder.onstop = null;
      if (recorder.state !== 'inactive') recorder.stop();
      stream.getTracks().forEach(track => track.stop());
    },
  };
}

export async function startVoiceRecording(): Promise<VoiceRecording> {
  if (!navigator.mediaDevices?.getUserMedia) throw new Error('This browser cannot access the microphone.');
  // Start Web Audio during the tap. Resuming it after getUserMedia's permission
  // prompt can leave Firefox waiting forever for another user gesture.
  let context: AudioContext | null = null;
  let resumed: Promise<void> | null = null;
  try {
    context = new AudioContext();
    resumed = context.resume();
    void resumed.catch(() => undefined);
  } catch { if (context) void context.close().catch(() => undefined); context = null; }
  let stream: MediaStream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); }
  catch (error) { if (context) void context.close().catch(() => undefined); throw error; }
  if (!context || !resumed) {
    if (typeof MediaRecorder !== 'undefined') return mediaRecorderFallback(stream);
    stream.getTracks().forEach(track => track.stop());
    throw new Error('This browser does not support microphone recording.');
  }
  let source: MediaStreamAudioSourceNode | null = null;
  let processor: AudioWorkletNode | null = null;
  let silentOutput: GainNode | null = null;
  try {
    await withTimeout(resumed, 2500, 'Audio could not start.');
    await withTimeout(context.audioWorklet.addModule('/pcm-recorder.js'), 4000, 'Audio recorder did not load.');
    source = context.createMediaStreamSource(stream);
    processor = new AudioWorkletNode(context, 'pcm-recorder');
    silentOutput = context.createGain();
    silentOutput.gain.value = 0;
    const chunks: Float32Array[] = [];
    let inputLevel = 0;
    let stopped: (() => void) | null = null;
    let firstSample: (() => void) | null = null;
    const ready = new Promise<void>(resolve => { firstSample = resolve; });
    processor.port.onmessage = event => {
      if (event.data?.type === 'samples') {
        const samples = event.data.samples as Float32Array;
        chunks.push(samples);
        inputLevel = pcmInputLevel(samples);
        firstSample?.(); firstSample = null;
      }
      if (event.data?.type === 'stopped') stopped?.();
    };
    source.connect(processor);
    processor.connect(silentOutput);
    silentOutput.connect(context.destination);
    await withTimeout(ready, 2500, 'The microphone did not produce audio.');
    const sampleRate = context.sampleRate;
    const cleanup = async () => {
      processor!.port.onmessage = null;
      source!.disconnect(); processor!.disconnect(); silentOutput!.disconnect();
      stream.getTracks().forEach(track => track.stop());
      void context!.close().catch(() => undefined);
    };
    return {
      mode: 'wav',
      inputLevel: () => inputLevel,
      stop: async () => {
        await new Promise<void>(resolve => {
          stopped = resolve;
          processor!.port.postMessage('stop');
          setTimeout(resolve, 1500);
        });
        await cleanup();
        return encodeWav(chunks, sampleRate);
      },
      cancel: cleanup,
    };
  } catch {
    source?.disconnect(); processor?.disconnect(); silentOutput?.disconnect();
    if (processor) processor.port.onmessage = null;
    void context.close().catch(() => undefined);
    if (typeof MediaRecorder !== 'undefined') return await mediaRecorderFallback(stream);
    stream.getTracks().forEach(track => track.stop());
    throw new Error('Firefox could not start audio recording. Please try again.');
  }
}
