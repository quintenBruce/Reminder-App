class PcmRecorder extends AudioWorkletProcessor {
  constructor() {
    super();
    this.recording = true;
    this.port.onmessage = event => {
      if (event.data === 'stop') {
        this.recording = false;
        this.port.postMessage({ type: 'stopped' });
      }
    };
  }

  process(inputs, outputs) {
    const input = inputs[0]?.[0];
    if (this.recording && input?.length) {
      const samples = new Float32Array(input);
      this.port.postMessage({ type: 'samples', samples }, [samples.buffer]);
    }
    for (const output of outputs[0] || []) output.fill(0);
    return this.recording;
  }
}

registerProcessor('pcm-recorder', PcmRecorder);
