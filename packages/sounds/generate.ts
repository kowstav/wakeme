import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { encodeWav } from '../../services/api/src/lib/audio.ts';

// Original, procedurally synthesised sound set (no samples, no third-party
// rights). Files are 16-bit PCM WAV under 30 s, which both UNNotificationSound
// and Android raw resources accept. Run: npm run generate:sounds

const RATE = 22050;
const root = new URL('../../', import.meta.url);

function buffer(seconds: number): Float32Array {
  return new Float32Array(Math.round(seconds * RATE));
}

function gauss(x: number, mu: number, sigma: number): number {
  return Math.exp(-((x - mu) ** 2) / (2 * sigma ** 2));
}

/** A nasal, buzzy "quack": gliding fundamental, formant-shaped harmonics, quick envelope. */
function quack(out: Float32Array, startSec: number, gain = 0.8): void {
  const len = 0.24;
  const n = Math.round(len * RATE);
  const start = Math.round(startSec * RATE);
  let phase = 0;
  for (let i = 0; i < n && start + i < out.length; i++) {
    const t = i / RATE;
    const f0 = 330 - 120 * (t / len);
    phase += (2 * Math.PI * f0) / RATE;
    let s = 0;
    for (let k = 1; k <= 24; k++) {
      const f = k * f0;
      if (f > RATE / 2.2) break;
      const amp = 0.9 * gauss(f, 1050, 320) + 0.6 * gauss(f, 2500, 500) + 0.15 / k;
      s += amp * Math.sin(k * phase);
    }
    const env = Math.min(1, t / 0.012) * Math.exp(-((t / len) ** 2) * 2.2);
    out[start + i]! += gain * 0.11 * s * env;
  }
}

function bell(out: Float32Array, startSec: number, f: number, decay: number, gain: number): void {
  const partials: [number, number, number][] = [
    [1, 1, 1],
    [2, 0.5, 1.5],
    [2.76, 0.35, 2.2],
    [5.4, 0.15, 3.5],
  ];
  const start = Math.round(startSec * RATE);
  const n = Math.round(decay * 5 * RATE);
  for (let i = 0; i < n && start + i < out.length; i++) {
    const t = i / RATE;
    let s = 0;
    for (const [ratio, amp, speed] of partials) s += amp * Math.sin(2 * Math.PI * f * ratio * t) * Math.exp((-t * speed) / decay);
    out[start + i]! += gain * s * Math.min(1, t / 0.004);
  }
}

function tone(out: Float32Array, startSec: number, f: number, len: number, gain: number): void {
  const start = Math.round(startSec * RATE);
  const n = Math.round(len * RATE);
  for (let i = 0; i < n && start + i < out.length; i++) {
    const t = i / RATE;
    const env = Math.min(1, t / 0.01, (len - t) / 0.03);
    out[start + i]! += gain * env * (Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t));
  }
}

function normalize(s: Float32Array, peak = 0.89): Float32Array {
  let max = 0;
  for (const v of s) max = Math.max(max, Math.abs(v));
  if (max > 0) for (let i = 0; i < s.length; i++) s[i] = (s[i]! / max) * peak;
  return s;
}

const sounds: Record<string, () => Float32Array> = {
  'quack.classic': () => {
    const s = buffer(0.8);
    quack(s, 0.02);
    quack(s, 0.36);
    return normalize(s);
  },
  'quack.double': () => {
    const s = buffer(1.6);
    for (const t of [0.02, 0.3, 0.82, 1.1]) quack(s, t);
    return normalize(s);
  },
  'chime.soft': () => {
    const s = buffer(2.6);
    bell(s, 0, 659.25, 0.5, 0.6);
    bell(s, 0.35, 554.37, 0.6, 0.5);
    return normalize(s, 0.6);
  },
  'bell.bright': () => {
    const s = buffer(20);
    for (let k = 0; k < 13; k++) {
      const t = k * 1.5;
      bell(s, t, 880, 0.35, 0.6);
      bell(s, t + 0.25, 1174.66, 0.35, 0.5);
      bell(s, t + 0.5, 1318.51, 0.4, 0.5);
    }
    return normalize(s);
  },
  'rise.gentle': () => {
    // A slow pentatonic arpeggio that grows from quiet to full over 20 s.
    const s = buffer(20);
    const notes = [261.63, 293.66, 329.63, 392, 440, 523.25];
    for (let k = 0; k < 40; k++) tone(s, k * 0.5, notes[k % notes.length]!, 0.45, 0.15 + 0.85 * (k / 40));
    return normalize(s);
  },
  'pulse.wake': () => {
    const s = buffer(20);
    for (let k = 0; k < 20; k++) for (let b = 0; b < 4; b++) tone(s, k + b * 0.14, 880, 0.08, 1);
    return normalize(s, 0.8);
  },
};

const targets = ['apps/ios/WakeMe/Resources/Sounds', 'apps/android/app/src/main/res/raw'];
const manifest: { id: string; file: string; bytes: number; durationMs: number; sha256: string }[] = [];
for (const [id, make] of Object.entries(sounds)) {
  const wav = encodeWav(make(), RATE);
  const file = `${id.replace(/\./g, '_')}.wav`;
  for (const dir of targets) {
    const path = fileURLToPath(new URL(`${dir}/${file}`, root));
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, wav);
  }
  manifest.push({ id: `bundled:${id}`, file, bytes: wav.length, durationMs: Math.round(((wav.length - 44) / 2 / RATE) * 1000), sha256: createHash('sha256').update(wav).digest('hex') });
}
writeFileSync(fileURLToPath(new URL('packages/sounds/manifest.json', root)), JSON.stringify({ sampleRate: RATE, format: 'wav-pcm16-mono', sounds: manifest }, null, 2) + '\n');
console.log(manifest.map((m) => `${m.file.padEnd(20)} ${String(m.durationMs).padStart(6)} ms ${(m.bytes / 1024).toFixed(0).padStart(5)} KB`).join('\n'));
