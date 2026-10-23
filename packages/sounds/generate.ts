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

/** A small fixed-seed generator, so breath noise (and the files' hashes) are reproducible. */
let seed = 0x2f6b9d1;
function noise(): number {
  seed = (seed * 1103515245 + 12345) & 0x7fffffff;
  return (seed / 0x7fffffff) * 2 - 1;
}

interface Call {
  /** Fundamental (Hz) at u = 0..1 through the call; `t` is seconds, for vibrato. */
  f0: (u: number, t: number) => number;
  /** Resonances [centre Hz, width Hz, strength] at u, the vowel the animal "says". */
  formants: (u: number) => [number, number, number][];
  gain?: number;
  /** 0..1 depth of a rattle (the "r" of a ribbit, a donkey's bray) at `roughHz`. */
  rough?: number;
  roughHz?: number;
  /** 0..1 breath noise. */
  breath?: number;
  attack?: number;
  release?: number;
}

/** A voiced animal call: harmonics of a gliding fundamental, shaped by moving formants. */
function call(out: Float32Array, startSec: number, len: number, c: Call): void {
  const n = Math.round(len * RATE);
  const start = Math.round(startSec * RATE);
  const attack = c.attack ?? 0.015;
  const release = c.release ?? 0.06;
  let phase = 0;
  for (let i = 0; i < n && start + i < out.length; i++) {
    const t = i / RATE;
    const u = t / len;
    const f0 = c.f0(u, t);
    phase += (2 * Math.PI * f0) / RATE;
    const formants = c.formants(u);
    let s = 0;
    for (let k = 1; k <= 40; k++) {
      const f = k * f0;
      if (f > RATE / 2.2) break;
      let amp = 0.06 / k;
      for (const [centre, width, strength] of formants) amp += strength * gauss(f, centre, width);
      s += amp * Math.sin(k * phase);
    }
    const am = 1 - (c.rough ?? 0) * 0.5 * (1 + Math.sin(2 * Math.PI * (c.roughHz ?? 30) * t));
    const env = Math.min(1, t / attack) * Math.min(1, Math.max(0, len - t) / release);
    out[start + i]! += (c.gain ?? 1) * env * (0.1 * s * am + (c.breath ?? 0) * 0.3 * noise());
  }
}

const sine = (u: number) => Math.sin(Math.PI * u);

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
  // Animal calls for quacks (the duck's quack stays the default). Cartoon voices, short and loud
  // enough to notice, under 2 s each.
  'frog.ribbit': () => {
    const s = buffer(1);
    for (const at of [0.02, 0.5]) {
      call(s, at, 0.16, { f0: (u) => 130 - 20 * u, formants: () => [[420, 150, 1], [1100, 300, 0.4]], rough: 0.9, roughHz: 34 });
      call(s, at + 0.17, 0.09, { f0: (u) => 270 - 70 * u, formants: () => [[620, 200, 1], [1800, 400, 0.5]], rough: 0.3, roughHz: 20, release: 0.03 });
    }
    return normalize(s);
  },
  'monkey.hoot': () => {
    const s = buffer(1.25);
    for (const at of [0.02, 0.3]) call(s, at, 0.22, { f0: (u) => 420 + 220 * u, formants: () => [[360, 130, 1], [900, 250, 0.3]] });
    for (const at of [0.6, 0.92]) call(s, at, 0.27, { f0: (u) => 760 + 180 * sine(u), formants: () => [[820, 220, 1], [1300, 300, 0.7]], rough: 0.2, roughHz: 18 });
    return normalize(s);
  },
  'donkey.heehaw': () => {
    const s = buffer(1.9);
    for (const at of [0.02, 0.95]) {
      call(s, at, 0.34, { f0: (u) => 950 - 160 * u, formants: () => [[350, 150, 0.6], [2400, 450, 1]], breath: 0.15 });
      call(s, at + 0.38, 0.46, { f0: (u) => 210 - 50 * u, formants: () => [[700, 200, 1], [1150, 260, 0.8]], rough: 0.7, roughHz: 28, breath: 0.08 });
    }
    return normalize(s);
  },
  'cat.meow': () => {
    const s = buffer(0.8);
    call(s, 0.02, 0.72, {
      f0: (u) => 520 + 260 * sine(Math.min(1, u * 1.3)) - 80 * u,
      formants: (u) => [[300 + 450 * sine(u), 140, 1], [1600 + 600 * sine(u) - 600 * u, 320, 0.45]],
      attack: 0.04,
      release: 0.12,
    });
    return normalize(s);
  },
  'dog.woof': () => {
    const s = buffer(0.62);
    for (const at of [0.02, 0.32]) call(s, at, 0.17, { f0: (u) => 300 - 120 * u, formants: () => [[560, 200, 1], [1300, 300, 0.6]], rough: 0.3, roughHz: 50, breath: 0.25, attack: 0.005 });
    return normalize(s);
  },
  'cow.moo': () => {
    const s = buffer(1.4);
    call(s, 0.02, 1.3, {
      f0: (u) => 120 + 25 * sine(u) - 15 * u,
      formants: (u) => [[300 + 300 * sine(u * 0.8), 150, 1], [800, 250, 0.4]],
      breath: 0.05,
      attack: 0.15,
      release: 0.3,
    });
    return normalize(s);
  },
  'pig.oink': () => {
    const s = buffer(0.62);
    for (const at of [0.02, 0.32]) call(s, at, 0.2, { f0: (u) => 240 - 60 * u, formants: () => [[300, 150, 1], [1100, 300, 0.5]], rough: 0.8, roughHz: 45, breath: 0.1 });
    return normalize(s);
  },
  'sheep.baa': () => {
    const s = buffer(0.95);
    call(s, 0.02, 0.85, {
      f0: (u, t) => 320 - 40 * u + 30 * Math.sin(2 * Math.PI * 7 * t),
      formants: () => [[760, 200, 1], [1250, 300, 0.7]],
      rough: 0.6,
      roughHz: 9,
      attack: 0.03,
      release: 0.15,
    });
    return normalize(s);
  },
  'owl.hoot': () => {
    const s = buffer(1.55);
    for (const [at, len] of [[0.02, 0.3], [0.45, 0.3], [0.95, 0.5]] as const) {
      call(s, at, len, { f0: (u) => 380 - 30 * u, formants: () => [[380, 80, 1]], breath: 0.03, attack: 0.05, release: 0.15 });
    }
    return normalize(s);
  },
  'rooster.crow': () => {
    const s = buffer(1.45);
    const voice = (u: number): [number, number, number][] => [[650 + 100 * u, 200, 1], [1500, 300, 0.8]];
    for (const [at, f] of [[0.02, 520], [0.2, 620], [0.38, 700]] as const) call(s, at, 0.15, { f0: () => f, formants: voice, rough: 0.35, roughHz: 30 });
    call(s, 0.6, 0.78, { f0: (u) => 780 + 80 * sine(u) - 150 * u, formants: voice, rough: 0.35, roughHz: 30, release: 0.2 });
    return normalize(s);
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
