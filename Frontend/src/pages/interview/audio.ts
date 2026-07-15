import type { InterviewType } from "./types";

export const PCM_SAMPLE_RATE = 16000;
export const PCM_CHUNK_MS = 200;
export const PCM_CHUNK_SAMPLES = Math.floor(PCM_SAMPLE_RATE * (PCM_CHUNK_MS / 1000));
export const MAX_ASR_BUFFERED_BYTES = 256 * 1024;
export const FINAL_TRANSCRIPT_TIMEOUT_MS = 10_000;
export const ANSWER_LIMIT_MS = 300_000;
export const SILENCE_AUTO_FINISH_MS = 3_000;
export const SILENCE_CALIBRATION_MS = 900;
export const MIN_VOICE_RMS = 0.018;
export const VOICE_MARGIN_RMS = 0.012;
export const VOICE_NOISE_RATIO = 2.3;
export const RECORDING_PROGRESS_INTERVAL_MS = 200;

export function resampleAudio(samples: Float32Array, inputSampleRate: number, outputSampleRate: number) {
  if (inputSampleRate === outputSampleRate) {
    return samples;
  }
  const ratio = inputSampleRate / outputSampleRate;
  const outputLength = Math.round(samples.length / ratio);
  const output = new Float32Array(outputLength);
  for (let index = 0; index < outputLength; index += 1) {
    const sourceIndex = index * ratio;
    const before = Math.floor(sourceIndex);
    const after = Math.min(before + 1, samples.length - 1);
    const weight = sourceIndex - before;
    output[index] = samples[before] * (1 - weight) + samples[after] * weight;
  }
  return output;
}

export function appendSamples(first: Float32Array, second: Float32Array) {
  if (first.length === 0) {
    return second;
  }
  const merged = new Float32Array(first.length + second.length);
  merged.set(first, 0);
  merged.set(second, first.length);
  return merged;
}

export function encodePcm16(samples: Float32Array) {
  const buffer = new ArrayBuffer(samples.length * 2);
  const view = new DataView(buffer);
  for (let index = 0; index < samples.length; index += 1) {
    const sample = Math.max(-1, Math.min(1, samples[index]));
    view.setInt16(index * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
  }
  return buffer;
}

export function getAnswerLimitMs(_interviewType?: InterviewType, _roundName?: string) {
  return ANSWER_LIMIT_MS;
}

export function formatDuration(ms: number) {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

export function formatAnswerLimit(interviewType: InterviewType, roundName?: string) {
  const limitSeconds = Math.round(getAnswerLimitMs(interviewType, roundName) / 1000);
  if (limitSeconds >= 300) {
    return "最长 5 分钟";
  }
  if (limitSeconds >= 60) {
    return `最长 ${Math.round(limitSeconds / 60)} 分钟`;
  }
  return `最长 ${limitSeconds} 秒`;
}

export function calculateRms(samples: Float32Array) {
  if (samples.length === 0) {
    return 0;
  }
  let sum = 0;
  for (let index = 0; index < samples.length; index += 1) {
    sum += samples[index] * samples[index];
  }
  return Math.sqrt(sum / samples.length);
}

export function isEffectiveVoice(rms: number, noiseFloor: number) {
  return rms > MIN_VOICE_RMS && rms > noiseFloor + VOICE_MARGIN_RMS && rms > noiseFloor * VOICE_NOISE_RATIO;
}
