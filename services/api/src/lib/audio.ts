// Minimal, bounds-checked audio container parsers used by the media scan job.
// They read headers only (no decoding) to verify format and duration; any
// structural inconsistency is a rejection. Transcoding, if added later, must
// run in an isolated worker with resource limits.

export interface AudioInfo {
  format: 'wav' | 'mp4';
  durationMs: number;
  channels?: number;
  sampleRate?: number;
}

export function parseWav(buf: Buffer): AudioInfo | null {
  if (buf.length < 44) return null;
  if (buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WAVE') return null;
  const riffSize = buf.readUInt32LE(4);
  if (riffSize + 8 > buf.length + 1 || riffSize < 36) return null;
  let off = 12;
  let fmt: { format: number; channels: number; sampleRate: number; byteRate: number; blockAlign: number; bits: number } | null = null;
  let dataSize: number | null = null;
  let chunks = 0;
  while (off + 8 <= buf.length && chunks++ < 64) {
    const id = buf.toString('ascii', off, off + 4);
    const size = buf.readUInt32LE(off + 4);
    const body = off + 8;
    if (body + size > buf.length) return null;
    if (id === 'fmt ') {
      if (size < 16) return null;
      fmt = {
        format: buf.readUInt16LE(body),
        channels: buf.readUInt16LE(body + 2),
        sampleRate: buf.readUInt32LE(body + 4),
        byteRate: buf.readUInt32LE(body + 8),
        blockAlign: buf.readUInt16LE(body + 12),
        bits: buf.readUInt16LE(body + 14),
      };
    } else if (id === 'data') {
      dataSize = size;
    }
    off = body + size + (size % 2);
  }
  if (!fmt || dataSize === null) return null;
  if (fmt.format !== 1 && fmt.format !== 3) return null; // PCM or IEEE float only
  if (fmt.channels < 1 || fmt.channels > 2 || fmt.sampleRate < 8000 || fmt.sampleRate > 96000) return null;
  if (![8, 16, 24, 32].includes(fmt.bits) || fmt.blockAlign !== (fmt.channels * fmt.bits) / 8 || fmt.byteRate !== fmt.sampleRate * fmt.blockAlign) return null;
  return { format: 'wav', durationMs: Math.round((dataSize / fmt.byteRate) * 1000), channels: fmt.channels, sampleRate: fmt.sampleRate };
}

/** ISO-BMFF (.m4a): requires ftyp, and moov/mvhd for the duration. */
export function parseMp4(buf: Buffer): AudioInfo | null {
  const readBoxes = (start: number, end: number): { type: string; start: number; end: number }[] | null => {
    const out: { type: string; start: number; end: number }[] = [];
    let off = start;
    let n = 0;
    while (off + 8 <= end && n++ < 256) {
      let size = buf.readUInt32BE(off);
      const type = buf.toString('ascii', off + 4, off + 8);
      let header = 8;
      if (size === 1) {
        if (off + 16 > end) return null;
        const big = buf.readBigUInt64BE(off + 8);
        if (big > BigInt(end - off)) return null;
        size = Number(big);
        header = 16;
      } else if (size === 0) {
        size = end - off;
      }
      if (size < header || off + size > end) return null;
      out.push({ type, start: off + header, end: off + size });
      off += size;
    }
    return out;
  };
  const top = readBoxes(0, buf.length);
  if (!top || top[0]?.type !== 'ftyp') return null;
  const brand = buf.toString('ascii', top[0].start, top[0].start + 4);
  if (!['M4A ', 'mp42', 'isom', 'mp41', 'M4B '].includes(brand)) return null;
  const moov = top.find((b) => b.type === 'moov');
  if (!moov) return null;
  const inner = readBoxes(moov.start, moov.end);
  const mvhd = inner?.find((b) => b.type === 'mvhd');
  if (!mvhd || mvhd.end - mvhd.start < 20) return null;
  const version = buf.readUInt8(mvhd.start);
  let timescale: number;
  let duration: number;
  if (version === 1) {
    if (mvhd.end - mvhd.start < 32) return null;
    timescale = buf.readUInt32BE(mvhd.start + 20);
    duration = Number(buf.readBigUInt64BE(mvhd.start + 24));
  } else {
    timescale = buf.readUInt32BE(mvhd.start + 12);
    duration = buf.readUInt32BE(mvhd.start + 16);
  }
  if (timescale === 0) return null;
  if (!inner?.some((b) => b.type === 'trak')) return null;
  return { format: 'mp4', durationMs: Math.round((duration / timescale) * 1000) };
}

export function inspectAudio(buf: Buffer, mime: string): AudioInfo | null {
  if (mime === 'audio/wav') return parseWav(buf);
  if (mime === 'audio/mp4') return parseMp4(buf);
  return null;
}

/** 16-bit PCM mono WAV writer, used to synthesise the original bundled sounds. */
export function encodeWav(samples: Float32Array, sampleRate: number): Buffer {
  const data = Buffer.alloc(samples.length * 2);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]!));
    data.writeInt16LE(Math.round(s * 32767), i * 2);
  }
  const header = Buffer.alloc(44);
  header.write('RIFF', 0, 'ascii');
  header.writeUInt32LE(36 + data.length, 4);
  header.write('WAVE', 8, 'ascii');
  header.write('fmt ', 12, 'ascii');
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36, 'ascii');
  header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}
