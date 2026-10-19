class PcmRecorder extends AudioWorkletProcessor {
  constructor() {
    super();
    this.recording = true;
    this.pending = new Float32Array(4096);
    this.pendingCount = 0;
    this.port.onmessage = event => {
      if (event.data === 'stop') {
        this.recording = false;
        this.flush();
        this.port.postMessage({ type: 'stopped' });
      }
    };
  }

  flush() {
    if (!this.pendingCount) return;
    const samples = this.pendingCount === this.pending.length
      ? this.pending : this.pending.slice(0, this.pendingCount);
    this.port.postMessage({ type: 'samples', samples }, [samples.buffer]);
    this.pending = new Float32Array(4096);
    this.pendingCount = 0;
  }

  process(inputs, outputs) {
    const channels = inputs[0];
    if (this.recording && channels?.length) {
      // Some microphones expose a quiet first channel and speech on another.
      let loudest = channels[0];
      let loudestEnergy = -1;
      for (const channel of channels) {
        let energy = 0;
        for (const sample of channel) energy += sample * sample;
        if (energy > loudestEnergy) { loudest = channel; loudestEnergy = energy; }
      }
      for (let offset = 0; offset < loudest.length;) {
        const count = Math.min(loudest.length - offset, this.pending.length - this.pendingCount);
        this.pending.set(loudest.subarray(offset, offset + count), this.pendingCount);
        this.pendingCount += count;
        offset += count;
        if (this.pendingCount === this.pending.length) this.flush();
      }
    }
    for (const output of outputs[0] || []) output.fill(0);
    return this.recording;
  }
}

registerProcessor('pcm-recorder', PcmRecorder);
