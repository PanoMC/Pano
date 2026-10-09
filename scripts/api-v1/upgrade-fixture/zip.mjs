// Minimal zip reader / writer for the upgrade fixture (no dependency, works under node and bun).
// Reads stored and deflated entries; writes stored entries only (what a theme or plugin zip needs to be inspected).
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** The central directory of [buf]: [{ name, method, compressedSize, size, offset }]. */
export function listZip(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65535); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('not a zip file (no end of central directory)');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const entries = [];
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('corrupt zip central directory');
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    entries.push({
      name: buf.toString('utf8', p + 46, p + 46 + nameLen),
      method: buf.readUInt16LE(p + 10),
      compressedSize: buf.readUInt32LE(p + 20),
      size: buf.readUInt32LE(p + 24),
      offset: buf.readUInt32LE(p + 42),
    });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

/** The bytes of entry [name] of [buf], or null. */
export function readZipEntry(buf, name) {
  const e = listZip(buf).find((x) => x.name === name);
  if (!e) return null;
  const nameLen = buf.readUInt16LE(e.offset + 26);
  const extraLen = buf.readUInt16LE(e.offset + 28);
  const start = e.offset + 30 + nameLen + extraLen;
  const raw = buf.subarray(start, start + e.compressedSize);
  if (e.method === 0) return Buffer.from(raw);
  if (e.method === 8) return zlib.inflateRawSync(raw);
  throw new Error(`zip entry ${name} uses unsupported method ${e.method}`);
}

/** A zip (stored entries) from [{ name, data: Buffer|string }]. */
export function writeZip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const { name, data } of entries) {
    const body = Buffer.isBuffer(data) ? data : Buffer.from(data);
    const nameBuf = Buffer.from(name, 'utf8');
    const crc = crc32(body);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(body.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    locals.push(local, nameBuf, body);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(body.length, 20);
    central.writeUInt32LE(body.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBuf);
    offset += 30 + nameBuf.length + body.length;
  }
  const centralBuf = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBuf, end]);
}

/** Every file under [dir] as zip entries (forward slashes, sorted), for zipping a built theme folder. */
export function entriesOfDirectory(dir) {
  const out = [];
  const walk = (d, rel) => {
    for (const name of fs.readdirSync(d).sort()) {
      const full = path.join(d, name);
      const stat = fs.statSync(full);
      if (stat.isDirectory()) walk(full, rel + name + '/');
      else if (stat.isFile()) out.push({ name: rel + name, data: fs.readFileSync(full) });
    }
  };
  walk(dir, '');
  return out;
}

/** `Key: value` lines of a jar manifest (continuation lines joined), as an object. */
export function parseManifest(text) {
  const out = {};
  let last = null;
  for (const line of text.split(/\r?\n/)) {
    if (line.startsWith(' ') && last) out[last] += line.slice(1);
    else {
      const i = line.indexOf(':');
      if (i > 0) {
        last = line.slice(0, i).trim();
        out[last] = line.slice(i + 1).trim();
      }
    }
  }
  return out;
}

/** What a plugin jar or theme zip says about itself: { type, id, version, apiLevel }. apiLevel is 0 when nothing is declared. */
export function describeArtifact(buf, fileName = '') {
  const jarManifest = readZipEntry(buf, 'META-INF/MANIFEST.MF');
  if (jarManifest && /\.jar$/i.test(fileName || '.jar')) {
    const m = parseManifest(jarManifest.toString('utf8'));
    return { type: 'PLUGIN', id: m.id ?? '', version: m.version ?? '', apiLevel: Number(m['api-level'] ?? 0) || 0 };
  }
  const themeManifest = readZipEntry(buf, 'manifest.json');
  if (themeManifest) {
    const m = JSON.parse(themeManifest.toString('utf8'));
    return { type: 'THEME', id: m.id ?? '', version: m.version ?? '', apiLevel: Number(m.apiLevel ?? 0) || 0 };
  }
  throw new Error(`${fileName || 'file'} is neither a plugin jar (META-INF/MANIFEST.MF) nor a theme zip (manifest.json)`);
}

/**
 * A copy of [buf] where the entries named in [changes] ({ name: Buffer|string|null }) are replaced, added (stored) or, with
 * null, dropped. Every other entry keeps its compressed bytes, so a large jar is not recompressed.
 */
export function replaceEntries(buf, changes) {
  const parts = [];
  const centrals = [];
  let offset = 0;
  const seen = new Set();

  const push = (name, method, crc, compressedSize, size, data) => {
    const nameBuf = Buffer.from(name, 'utf8');
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(method, 8);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(compressedSize, 18);
    local.writeUInt32LE(size, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    parts.push(local, nameBuf, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(method, 10);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(compressedSize, 20);
    central.writeUInt32LE(size, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBuf);
    offset += 30 + nameBuf.length + data.length;
  };

  for (const e of listZip(buf)) {
    seen.add(e.name);
    if (e.name in changes) {
      const next = changes[e.name];
      if (next === null) continue;
      const body = Buffer.isBuffer(next) ? next : Buffer.from(next);
      push(e.name, 0, crc32(body), body.length, body.length, body);
      continue;
    }
    const nameLen = buf.readUInt16LE(e.offset + 26);
    const extraLen = buf.readUInt16LE(e.offset + 28);
    const start = e.offset + 30 + nameLen + extraLen;
    // The central directory has the final crc (the local header of a streamed entry may hold zeros).
    const centralCrc = centralCrcOf(buf, e.name);
    push(e.name, e.method, centralCrc, e.compressedSize, e.size, buf.subarray(start, start + e.compressedSize));
  }

  for (const [name, next] of Object.entries(changes)) {
    if (seen.has(name) || next === null) continue;
    const body = Buffer.isBuffer(next) ? next : Buffer.from(next);
    push(name, 0, crc32(body), body.length, body.length, body);
  }

  const centralBuf = Buffer.concat(centrals);
  const count = centrals.length / 2;
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(count, 8);
  end.writeUInt16LE(count, 10);
  end.writeUInt32LE(centralBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, centralBuf, end]);
}

function centralCrcOf(buf, name) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  let p = buf.readUInt32LE(eocd + 16);
  const count = buf.readUInt16LE(eocd + 10);
  for (let n = 0; n < count; n++) {
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    if (buf.toString('utf8', p + 46, p + 46 + nameLen) === name) return buf.readUInt32LE(p + 16);
    p += 46 + nameLen + extraLen + commentLen;
  }
  throw new Error(`entry ${name} not found`);
}
