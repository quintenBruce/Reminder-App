export type VoiceRecording = {
  stop: () => Promise<Blob>;
  cancel: () => Promise<void>;
};

export function encodeWav(chunks: Float32Array[], sourceRate: number): Blob {
  if (!Number.isFinite(sourceRate) || sourceRate < 8000) throw new Error('Invalid microphone sample rate.');
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  if (!total) throw new Error('No microphone audio was captured. Please try again.');
  const source = new Float32Array(total);
  let offset = 0;
  for (const chunk of chunks) { source.set(chunk, offset); offset += chunk.length; }

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

async function mediaRecorderFallback(stream: MediaStream): Promise<VoiceRecording> {
  const mimeType = ['audio/webm', 'audio/mp4', 'audio/wav'].find(type => MediaRecorder.isTypeSupported(type));
  if (!mimeType) {
    stream.getTracks().forEach(track => track.stop());
    throw new Error('This browser does not support microphone recording.');
  }
  let recorder: MediaRecorder;
  try {
    recorder = new MediaRecorder(stream, { mimeType });
    recorder.start();
  } catch (error) {
    stream.getTracks().forEach(track => track.stop());
    throw error;
  }
  const chunks: Blob[] = [];
  recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
  return {
    stop: () => new Promise((resolve, reject) => {
      recorder.onerror = event => {
        stream.getTracks().forEach(track => track.stop());
        reject(new Error(`Recording failed: ${event.type}`));
      };
      recorder.onstop = () => {
        stream.getTracks().forEach(track => track.stop());
        const blob = new Blob(chunks, { type: recorder.mimeType || mimeType });
        blob.size ? resolve(blob) : reject(new Error('No microphone audio was captured. Please try again.'));
      };
      recorder.stop();
    }),
    cancel: async () => {
      recorder.onstop = null;
      if (recorder.state !== 'inactive') recorder.stop();
      stream.getTracks().forEach(track => track.stop());
    },
  };
}

export async function startVoiceRecording(): Promise<VoiceRecording> {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  let context: AudioContext | null = null;
  try {
    context = new AudioContext();
    await context.audioWorklet.addModule('/pcm-recorder.js');
    const source = context.createMediaStreamSource(stream);
    const processor = new AudioWorkletNode(context, 'pcm-recorder');
    const silentOutput = context.createGain();
    silentOutput.gain.value = 0;
    const chunks: Float32Array[] = [];
    let stopped: (() => void) | null = null;
    processor.port.onmessage = event => {
      if (event.data?.type === 'samples') chunks.push(event.data.samples as Float32Array);
      if (event.data?.type === 'stopped') stopped?.();
    };
    source.connect(processor);
    processor.connect(silentOutput);
    silentOutput.connect(context.destination);
    await context.resume();
    if (context.state !== 'running') throw new Error('Microphone audio is paused.');
    const sampleRate = context.sampleRate;
    const cleanup = async () => {
      source.disconnect(); processor.disconnect(); silentOutput.disconnect();
      stream.getTracks().forEach(track => track.stop());
      await context?.close();
    };
    return {
      stop: async () => {
        await new Promise<void>(resolve => {
          stopped = resolve;
          processor.port.postMessage('stop');
          window.setTimeout(resolve, 500);
        });
        await cleanup();
        return encodeWav(chunks, sampleRate);
      },
      cancel: cleanup,
    };
  } catch (error) {
    await context?.close().catch(() => undefined);
    if (typeof MediaRecorder !== 'undefined') return await mediaRecorderFallback(stream);
    stream.getTracks().forEach(track => track.stop());
    throw error;
  }
}
