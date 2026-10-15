#!/usr/bin/env node
// Local store stub (doc 04 section 7 and section 9, verification 5): answers the compatible-versions query of the
// compatibility reconcile with locally built artifacts, so an upgraded install can replace its old plugins and themes
// without the network. Nothing is downloaded; only the files given with --artifact are ever served.
//
//   node scripts/api-v1/store-stub.mjs --artifact <file|dir>... [--port <p>] [--host 127.0.0.1] [--token <t>] [--print-config]
//   node scripts/api-v1/store-stub.mjs --self-test
//
// An artifact is a plugin jar (id, version and `api-level` are read from its META-INF/MANIFEST.MF), a theme zip (from its
// manifest.json: id, version, apiLevel) or a built theme folder (zipped in memory, manifest.json at its root).
// The Pano under test points at the stub with `pano-api-url = "http://127.0.0.1:<port>"` in config.conf
// (--print-config prints the line). The routes are the ones PanoCompatibilityStore uses:
//
//   POST /platform/api/store/compatible-versions         anonymous: { apiLevel, minApiLevel, resources:[{id,version}] }
//                                                         -> { data: { items:[{ id, versionId, version, apiLevel }] } }
//   GET  /resources/:id/versions/:versionId/file         anonymous download
//   GET  /platform/api/store/resources/versions          linked (Bearer token, only when --token is given)
//                                                         -> { data:[{ id, type, versionId, version, apiLevel, hash }] }
//   GET  /platform/api/store/versions/:versionId/file    linked download
//   GET  /__stub/requests                                 what was asked so far, for the upgrade assertions
//   GET  /__stub/health                                   200 { ok: true }
//
// A resource is answered when its artifact level is inside minApiLevel..apiLevel; with several artifacts of one id the
// highest level wins, the later --artifact on a tie. The server binds to loopback only. Stop it with SIGTERM / SIGINT.
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describeArtifact, entriesOfDirectory, writeZip } from './upgrade-fixture/zip.mjs';

const SAFE_SEGMENT = /^[A-Za-z0-9._-]{1,128}$/;

// ------------------------------------------------------------------------------------------------ catalogue

/** Reads [target] (jar, zip or built theme folder) into a catalogue entry. */
export function loadArtifact(target) {
  const stat = fs.statSync(target);
  let buf;
  let fileName = path.basename(target);
  if (stat.isDirectory()) {
    buf = writeZip(entriesOfDirectory(target));
    fileName += '.zip';
  } else {
    buf = fs.readFileSync(target);
  }
  const info = describeArtifact(buf, fileName);
  if (!info.id || !info.version) throw new Error(`${target}: no id / version in its manifest`);
  const versionId = `${info.id}-${info.version}`.replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 128);
  if (!SAFE_SEGMENT.test(versionId)) throw new Error(`${target}: cannot build a safe version id`);
  return { ...info, versionId, buf, hash: crypto.createHash('sha256').update(buf).digest('hex'), source: target };
}

/** The artifact to offer for [id] inside [minApiLevel]..[apiLevel], or null. */
export function pickCompatible(catalogue, id, apiLevel, minApiLevel) {
  let best = null;
  for (const a of catalogue) {
    if (a.id !== id || a.apiLevel < minApiLevel || a.apiLevel > apiLevel) continue;
    if (!best || a.apiLevel >= best.apiLevel) best = a;
  }
  return best;
}

// ------------------------------------------------------------------------------------------------ server

export function createStub(catalogue, { token = '' } = {}) {
  const requests = [];

  const json = (res, status, body) => {
    const text = JSON.stringify(body);
    res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) });
    res.end(text);
  };
  const file = (res, artifact) => {
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': artifact.buf.length });
    res.end(artifact.buf);
  };
  const readBody = (req) =>
    new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', (c) => {
        size += c.length;
        if (size > 1_000_000) {
          reject(new Error('body too large'));
          req.destroy();
        } else chunks.push(c);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  const authorized = (req) => !token || req.headers.authorization === `Bearer ${token}`;
  const byVersionId = (versionId) => catalogue.find((a) => a.versionId === versionId);

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://stub.local');
    const entry = { method: req.method, path: url.pathname, query: Object.fromEntries(url.searchParams), linked: Boolean(req.headers.authorization) };

    try {
      if (req.method === 'GET' && url.pathname === '/__stub/health') return json(res, 200, { ok: true });
      if (req.method === 'GET' && url.pathname === '/__stub/requests') return json(res, 200, { requests });

      requests.push(entry);

      if (req.method === 'POST' && url.pathname === '/platform/api/store/compatible-versions') {
        const body = JSON.parse((await readBody(req)) || '{}');
        entry.body = body;
        const apiLevel = Number(body.apiLevel);
        const minApiLevel = Number(body.minApiLevel ?? 0);
        const items = [];
        for (const asked of Array.isArray(body.resources) ? body.resources : []) {
          const hit = pickCompatible(catalogue, String(asked.id), apiLevel, minApiLevel);
          if (hit) items.push({ id: hit.id, versionId: hit.versionId, version: hit.version, apiLevel: hit.apiLevel });
        }
        entry.answered = items.map((i) => i.id);
        return json(res, 200, { data: { items } });
      }

      let m = url.pathname.match(/^\/resources\/([^/]+)\/versions\/([^/]+)\/file$/);
      if (req.method === 'GET' && m) {
        const artifact = byVersionId(m[2]);
        if (!artifact || artifact.id !== m[1]) return json(res, 404, { error: { code: 'NOT_FOUND' } });
        entry.served = artifact.versionId;
        return file(res, artifact);
      }

      if (req.method === 'GET' && url.pathname === '/platform/api/store/resources/versions') {
        if (!token || !authorized(req)) return json(res, 401, { error: { code: 'UNAUTHORIZED' } });
        const apiLevel = Number(url.searchParams.get('apiLevel'));
        const minApiLevel = Number(url.searchParams.get('minApiLevel') ?? 0);
        const ids = [...new Set(catalogue.map((a) => a.id))];
        const data = ids
          .map((id) => pickCompatible(catalogue, id, apiLevel, minApiLevel))
          .filter(Boolean)
          .map((a) => ({ id: a.id, type: a.type, versionId: a.versionId, version: a.version, apiLevel: a.apiLevel, hash: a.hash }));
        entry.answered = data.map((d) => d.id);
        return json(res, 200, { data });
      }

      m = url.pathname.match(/^\/platform\/api\/store\/versions\/([^/]+)\/file$/);
      if (req.method === 'GET' && m) {
        if (!token || !authorized(req)) return json(res, 401, { error: { code: 'UNAUTHORIZED' } });
        const artifact = byVersionId(m[1]);
        if (!artifact) return json(res, 404, { error: { code: 'NOT_FOUND' } });
        entry.served = artifact.versionId;
        return file(res, artifact);
      }

      return json(res, 404, { error: { code: 'NOT_FOUND' } });
    } catch (e) {
      return json(res, 400, { error: { code: 'BAD_REQUEST', message: String(e.message ?? e) } });
    }
  });

  return { server, requests };
}

export async function listen(server, port, host = '127.0.0.1') {
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, resolve);
  });
  return server.address().port;
}

// ------------------------------------------------------------------------------------------------ self test

function assert(cond, message) {
  if (!cond) throw new Error(`self-test: ${message}`);
}

async function selfTest() {
  const dir = fs.mkdtempSync(path.join(process.env.TMPDIR || '/tmp', 'store-stub-'));
  try {
    const jarManifest = (id, version, level) =>
      `Manifest-Version: 1.0\nid: ${id}\nversion: ${version}\n${level === null ? '' : `api-level: ${level}\n`}main-class: x.Y\n`;
    const plugin = (name, id, version, level) => {
      const f = path.join(dir, name);
      fs.writeFileSync(f, writeZip([{ name: 'META-INF/MANIFEST.MF', data: jarManifest(id, version, level) }, { name: 'x/Y.class', data: 'bytes' }]));
      return f;
    };
    const jarOld = plugin('comments-old.jar', 'pano-plugin-comments', '1.0.0', null);
    const jarNew = plugin('comments-new.jar', 'pano-plugin-comments', 'local-build', 1);
    const market = plugin('market.jar', 'pano-plugin-market', 'local-build', 1);
    const themeDir = path.join(dir, 'blaze');
    fs.mkdirSync(path.join(themeDir, 'client'), { recursive: true });
    fs.writeFileSync(path.join(themeDir, 'manifest.json'), JSON.stringify({ id: 'blaze-theme', version: 'local-build', apiLevel: 1 }));
    fs.writeFileSync(path.join(themeDir, 'client', 'a.js'), 'x');

    const catalogue = [jarOld, jarNew, market, themeDir].map(loadArtifact);
    assert(catalogue[0].apiLevel === 0 && catalogue[1].apiLevel === 1, 'api-level is read from the manifest, 0 when missing');
    assert(catalogue[3].type === 'THEME' && catalogue[3].id === 'blaze-theme', 'a theme folder is zipped and described');
    assert(pickCompatible(catalogue, 'pano-plugin-comments', 1, 1)?.version === 'local-build', 'level 0 is outside 1..1');
    assert(pickCompatible(catalogue, 'pano-plugin-comments', 1, 0)?.apiLevel === 1, 'the highest level wins');
    assert(pickCompatible(catalogue, 'nope', 1, 0) === null, 'unknown id');

    const { server, requests } = createStub(catalogue);
    const port = await listen(server, 0);
    const base = `http://127.0.0.1:${port}`;
    try {
      const post = (body) => fetch(`${base}/platform/api/store/compatible-versions`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      const res = await post({
        apiLevel: 1,
        minApiLevel: 1,
        resources: [{ id: 'pano-plugin-comments', version: '1.0.0' }, { id: 'blaze-theme', version: 'v1.1.0' }, { id: 'pano-plugin-unknown', version: '1' }],
      });
      assert(res.status === 200, `compatible-versions status ${res.status}`);
      const items = (await res.json()).data.items;
      assert(items.length === 2 && items.map((i) => i.id).sort().join() === 'blaze-theme,pano-plugin-comments', `items: ${JSON.stringify(items)}`);

      const hit = items.find((i) => i.id === 'pano-plugin-comments');
      const file = await fetch(`${base}/resources/${hit.id}/versions/${hit.versionId}/file`);
      assert(file.status === 200, 'download status');
      const bytes = Buffer.from(await file.arrayBuffer());
      assert(bytes.equals(catalogue[1].buf), 'the served file is the artifact');
      assert((await fetch(`${base}/resources/other/versions/${hit.versionId}/file`)).status === 404, 'wrong id is 404');
      assert((await fetch(`${base}/platform/api/store/resources/versions?apiLevel=1&minApiLevel=1`)).status === 401, 'linked routes are closed without --token');

      const log = await (await fetch(`${base}/__stub/requests`)).json();
      assert(log.requests.length === requests.length && log.requests[0].answered.length === 2, 'the request log records what was answered');
      assert((await fetch(`${base}/__stub/health`)).status === 200, 'health');
    } finally {
      await new Promise((r) => server.close(r));
    }

    const linked = createStub(catalogue, { token: 't0k' });
    const lport = await listen(linked.server, 0);
    try {
      const url = `http://127.0.0.1:${lport}/platform/api/store/resources/versions?apiLevel=1&minApiLevel=1`;
      assert((await fetch(url)).status === 401, 'linked: no token');
      const ok = await fetch(url, { headers: { Authorization: 'Bearer t0k' } });
      const data = (await ok.json()).data;
      assert(ok.status === 200 && data.length === 3 && data.every((d) => d.hash && d.type), `linked: ${JSON.stringify(data)}`);
      const theme = data.find((d) => d.id === 'blaze-theme');
      assert(theme.type === 'THEME', 'a theme is typed THEME');
      const dl = await fetch(`http://127.0.0.1:${lport}/platform/api/store/versions/${theme.versionId}/file`, { headers: { Authorization: 'Bearer t0k' } });
      assert(dl.status === 200 && crypto.createHash('sha256').update(Buffer.from(await dl.arrayBuffer())).digest('hex') === theme.hash, 'linked download matches the hash');
    } finally {
      await new Promise((r) => linked.server.close(r));
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

// ------------------------------------------------------------------------------------------------ main

function parseArgs(argv) {
  const o = { artifacts: [], port: Number(process.env.PANO_OF_SLOT_STUB_PORT || 0), host: '127.0.0.1', token: '', printConfig: false, selfTest: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const v = () => argv[++i] ?? '';
    if (a === '--artifact') o.artifacts.push(path.resolve(v()));
    else if (a === '--port') o.port = Number(v());
    else if (a === '--host') o.host = v();
    else if (a === '--token') o.token = v();
    else if (a === '--print-config') o.printConfig = true;
    else if (a === '--self-test') o.selfTest = true;
    else throw new Error(`unknown option ${a}`);
  }
  return o;
}

async function main() {
  const o = parseArgs(process.argv.slice(2));

  if (o.selfTest) {
    await selfTest();
    console.log('store-stub: self-test ok (catalogue, compatible-versions, downloads, linked routes, request log)');
    return;
  }

  if (!o.artifacts.length) throw new Error('give at least one --artifact <jar|zip|theme folder>');
  if (!o.port) throw new Error('no port: pass --port or run inside a slot (/home/kahverengi/Projects/Pano/pano-open-frontend-spec/tools/of-slot.sh), which sets PANO_OF_SLOT_STUB_PORT');
  if ([8088, 3005, 3000, 3001, 3002, 3003, 18087].includes(o.port)) throw new Error(`refusing port ${o.port}`);
  if (!['127.0.0.1', '::1', 'localhost'].includes(o.host)) throw new Error('the stub binds to loopback only');

  const catalogue = o.artifacts.map(loadArtifact);
  const { server } = createStub(catalogue, { token: o.token });
  const port = await listen(server, o.port, o.host);

  for (const a of catalogue) console.error(`store-stub: ${a.type} ${a.id} ${a.version} (api level ${a.apiLevel}) <- ${a.source}`);
  console.error(`store-stub: listening on http://${o.host}:${port}`);
  if (o.printConfig) console.log(`pano-api-url = "http://${o.host}:${port}"`);

  const stop = () => server.close(() => process.exit(0));
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(`store-stub: ${e.message}`);
    process.exit(2);
  });
}
