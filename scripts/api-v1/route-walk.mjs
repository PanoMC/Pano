#!/usr/bin/env node
// Route walk (doc 04 section 9, verification 3). Runs against a booted, isolated Pano instance.
//
//   node scripts/api-v1/route-walk.mjs --url "$PANO_OF_SLOT_URL" [options]     (inside a slot: tools/of-slot.sh; --url defaults to the slot's instance)
//
// Options
//   --url <base>          the instance (default $PANO_OF_SLOT_URL; one of them is required; refuses the owner's dev ports)
//   --root <dir>          pano-web-platform checkout (default: two folders above this file)
//   --plugin-ids a,b,c    only walk the routes of these plugins (default: every plugin folder found); core is always walked
//   --admin-env <file>    admin.env of the instance (SMOKE_ADMIN_USER / SMOKE_ADMIN_PASSWORD); enables the signed-in pass
//                         that reads the panel lists. Without it the panel list check is skipped and said so.
//   --allow <file>        allow-list JSON (default: route-walk.allow.json next to this file): by-design binaries / redirects /
//                         status-only errors, and `known` = recorded deviations of other units (printed every run, do not fail the
//                         gate, deleted by the unit that fixes them; a stale entry is reported)
//   --report <file>       write the full result as JSON
//   --specs               (BOOT-02) fetch the three kinds of OpenAPI document (needs --admin-env for the internal one) and assert:
//                         the operations of the fetched specs equal the static extract, no operation is in two specs, a panel
//                         operation is never in the public core spec, every public core operation has `doc`, every public
//                         operation of a gate plugin has `doc`, and no schema has `totalPage` / `totalCount` or `meta` / `data`
//                         beside a list. The schema check has no allow-list.
//   --specs-only          --specs without the walk (no request to any route, no old-path pass): the documents and snapshots only
//   --strict-lists        list-shape deviations (LIST_SHAPE, PANEL_LIST_SHAPE) are never waived by the `known` entries
//   --write-snapshots     with --specs: write <core-dir>/openapi-core.json and openapi-internal.json and, per gate plugin,
//                         <snapshots-dir>/<pluginId>/openapi.json (same stable JSON as `pano-api openapi-snapshot`), then re-read
//                         them and compare; refuses a write that breaks the previous snapshot (api-compat)
//   --core-dir <dir>      where the core snapshots live (default <root>/Pano/api)
//   --snapshots-dir <dir> where the plugin snapshots go (default <umbrella>/.open-frontend-run/snapshots)
//   --upgrade <fixture.json>  the upgrade check instead of the walk: asserts an upgraded fixture instance (see boot-gate.sh --full);
//                         --stub-url <base> also checks what the local store stub was asked; --reconcile first runs gate 0 through
//                         POST /panel/compatibility/reconcile (the diagnostic path when the boot reconcile cannot run);
//                         --after-restart asserts only the end state (the second boot after a reconcile)
//
// What it asserts (exit 1 on any failure, 0 otherwise):
//   1. every route of `pano-api extract-routes` (core and each plugin) called without credentials or body answers JSON
//      (or a declared binary / redirect from the allow-list), never 5xx;
//   2. any non-2xx body is exactly the envelope { "error": { code, message?, details?, fields? } };
//   3. no success body carries `result`; a list body is `{ items, page }`, or `{ items }` on an endpoint with no paging parameter (decision 80, list-shape.mjs) (site lists in both passes; panel lists in the
//      signed-in pass; the ones that are not yet are `known` entries of kind PANEL_LIST_SHAPE that later units empty);
//   4. twenty old paths (`/api/posts`, `/panel/api/panel/basicData`, `/api/server/connect`, ...) reach no endpoint.
//
// Every failure is printed with the unit that owns the route (see ownerOf).
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { declaresPaging, pageProblems } from './list-shape.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));

// ------------------------------------------------------------------------------------------------ arguments

function parseArgs(argv) {
  const o = {
    url: '', root: path.resolve(here, '..', '..'), pluginIds: null, adminEnv: '', allow: path.join(here, 'route-walk.allow.json'), report: '',
    specs: false, specsOnly: false, strictLists: false, writeSnapshots: false, coreDir: '', snapshotsDir: '', upgrade: '', stubUrl: '', reconcile: false, afterRestart: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const v = () => argv[++i] ?? '';
    if (a === '--url') o.url = v().replace(/\/$/, '');
    else if (a === '--root') o.root = path.resolve(v());
    else if (a === '--plugin-ids') o.pluginIds = v().split(',').map((s) => s.trim()).filter(Boolean);
    else if (a === '--admin-env') o.adminEnv = v();
    else if (a === '--allow') o.allow = path.resolve(v());
    else if (a === '--report') o.report = path.resolve(v());
    else if (a === '--specs') o.specs = true;
    else if (a === '--specs-only') o.specs = o.specsOnly = true;
    else if (a === '--strict-lists') o.strictLists = true;
    else if (a === '--write-snapshots') o.writeSnapshots = true;
    else if (a === '--core-dir') o.coreDir = path.resolve(v());
    else if (a === '--snapshots-dir') o.snapshotsDir = path.resolve(v());
    else if (a === '--upgrade') o.upgrade = path.resolve(v());
    else if (a === '--stub-url') o.stubUrl = v().replace(/\/$/, '');
    else if (a === '--reconcile') o.reconcile = true;
    else if (a === '--after-restart') o.afterRestart = true;
    else throw new Error(`unknown option ${a}`);
  }
  if (!o.url && process.env.PANO_OF_SLOT_URL) o.url = process.env.PANO_OF_SLOT_URL.replace(/\/$/, '');
  if (!o.url) throw new Error('--url <base> is required (inside a slot it defaults to PANO_OF_SLOT_URL; run it through /home/kahverengi/Projects/Pano/pano-open-frontend-spec/tools/of-slot.sh)');
  if (!o.coreDir) o.coreDir = path.join(o.root, 'Pano', 'api');
  if (!o.snapshotsDir) o.snapshotsDir = path.resolve(o.root, '..', '.open-frontend-run', 'snapshots');
  if (/:(8088|3005|3000|3001|3002|3003|18087)(\/|$)/.test(o.url)) throw new Error(`refusing to walk ${o.url}: not an isolated instance`);
  return o;
}

// ------------------------------------------------------------------------------------------------ routes

function sdkDir() {
  const env = process.env.PANO_SDK_DIR;
  if (env) return env;
  return path.resolve(here, '..', '..', '..', 'theme-core', 'packages', 'sdk');
}

function extract(dir, root) {
  const cli = path.join(sdkDir(), 'bin', 'pano-api.js');
  const res = spawnSync('node', [cli, 'extract-routes', dir], { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (res.status !== 0) throw new Error(`extract-routes ${dir} failed: ${(res.stderr || '').slice(0, 400)}`);
  return JSON.parse(res.stdout);
}

/** @returns {{ routes: any[], skippedPlugins: string[] }} */
function collectRoutes(o) {
  const routes = extract(o.root, o.root).map((r) => ({ ...r, owner: 'core' }));
  const skippedPlugins = [];
  const pluginsRoot = path.join(o.root, 'plugins');
  const dirs = [];
  for (const d of fs.readdirSync(pluginsRoot, { withFileTypes: true })) {
    if (!d.isDirectory() || d.name.startsWith('.') || d.name === 'build') continue;
    const dir = path.join(pluginsRoot, d.name);
    if (fs.existsSync(path.join(dir, 'src'))) dirs.push(dir);
    else
      for (const c of fs.readdirSync(dir, { withFileTypes: true }))
        if (c.isDirectory() && fs.existsSync(path.join(dir, c.name, 'src'))) dirs.push(path.join(dir, c.name));
  }
  for (const dir of dirs.sort()) {
    const id = path.basename(dir);
    if (o.pluginIds && !o.pluginIds.includes(id)) {
      skippedPlugins.push(`${id} (not loaded in the instance)`);
      continue;
    }
    for (const r of extract(dir, o.root)) routes.push({ ...r, owner: id });
  }
  return { routes, skippedPlugins };
}

// ------------------------------------------------------------------------------------------------ http

const TIMEOUT_MS = 12000;
const UI_TIMEOUT_MS = 40000; // answers that the SvelteKit UI renders behind the proxy (old paths) can be slow on a cold theme

function fill(p) {
  return p.replace(/:[A-Za-z0-9_]+/g, '1').replace(/\*/g, 'x');
}

/** Sends one request, reads headers, then the body (bounded). Never throws. */
async function call(base, method, p, { headers = {}, body, timeout = TIMEOUT_MS } = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeout);
  try {
    const res = await fetch(base + p, { method, headers: { accept: 'application/json', ...headers }, body, redirect: 'manual', signal: ctl.signal });
    const type = (res.headers.get('content-type') || '').toLowerCase();
    const streaming = type.includes('text/event-stream') || type.includes('octet-stream') || type.includes('zip');
    let text = '';
    let bytes = 0;
    if (streaming) {
      // do not wait for a stream to end: headers are the answer
      await res.body?.cancel().catch(() => {});
    } else {
      const buf = Buffer.from(await res.arrayBuffer());
      bytes = buf.length;
      text = buf.toString('utf8');
    }
    let json;
    let jsonOk = false;
    if (type.includes('json') || /^\s*[{[]/.test(text)) {
      try {
        json = JSON.parse(text);
        jsonOk = true;
      } catch {
        /* not json */
      }
    }
    return { status: res.status, type, text, bytes, json, jsonOk, location: res.headers.get('location'), apiLevel: res.headers.get('pano-api-level'), setCookie: res.headers.getSetCookie?.() ?? [], streaming };
  } catch (e) {
    return { status: 0, type: '', text: '', bytes: 0, json: undefined, jsonOk: false, error: ctl.signal.aborted ? 'TIMEOUT' : String(e?.cause?.code || e.message) };
  } finally {
    clearTimeout(timer);
  }
}

// ------------------------------------------------------------------------------------------------ checks

const ENVELOPE_KEYS = new Set(['code', 'message', 'details', 'fields']);

/** @returns {string | null} the reason the body is not the envelope */
function envelopeProblem(json) {
  if (json === null || typeof json !== 'object' || Array.isArray(json)) return 'body is not a JSON object';
  const keys = Object.keys(json);
  if (keys.length !== 1 || keys[0] !== 'error') return `top-level keys are [${keys.join(', ')}], expected exactly [error]`;
  const e = json.error;
  if (e === null || typeof e !== 'object' || Array.isArray(e)) return 'error is not an object';
  if (typeof e.code !== 'string' || !e.code) return 'error.code is not a non-empty string';
  const extra = Object.keys(e).filter((k) => !ENVELOPE_KEYS.has(k));
  if (extra.length) return `error carries unknown keys [${extra.join(', ')}]`;
  if ('message' in e && typeof e.message !== 'string') return 'error.message is not a string';
  return null;
}

/** @returns {string[]} problems of a 2xx JSON body */
function successProblems(json) {
  const out = [];
  if (json === null || typeof json !== 'object') return out;
  if (!Array.isArray(json) && 'result' in json) out.push('success body carries `result`');
  return out;
}

// ------------------------------------------------------------------------------------------------ owners

/** The unit that has to fix a failing route. */
function ownerOf(route) {
  const id = route.owner;
  if (id === 'core') {
    if (route.path.startsWith('/api/v1/panel')) return 'PF-xx (core panel endpoint; paging PF-10, auth PF-06/PF-08)';
    if (/^\/api\/v1\/(node|server)\b/.test(route.path)) return 'PF-xx (machine API; MC-01 / SM node units)';
    return 'PF-10 / PF-xx (core site endpoint)';
  }
  const map = {
    'pano-plugin-market': 'MK-03 / MK-12 (market)',
    'pano-plugin-announcement': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-auth-guard': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-avatar': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-bans': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-comments': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-cookies': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-countdown-timer': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-faq': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-link-redirects': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-media-page': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-pages': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-premium-login': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-slider': 'PL-01 / PL-02 (plugins)',
    'pano-plugin-staff-page': 'PL-01 / PL-02 (plugins)',
  };
  return map[id] || `${id} (plugin)`;
}

// ------------------------------------------------------------------------------------------------ old paths

/** 20 paths of the pre-cutover API; none may reach an endpoint (404 or the 405/401-free "no route" answer). */
const OLD_PATHS = [
  ['GET', '/api/posts'],
  ['GET', '/api/siteInfo'],
  ['GET', '/api/websiteLogo'],
  ['GET', '/api/favicon'],
  ['POST', '/api/visitorVisit'],
  ['GET', '/api/registerAgreement'],
  ['POST', '/api/auth/login'],
  ['POST', '/api/auth/renewPassword'],
  ['POST', '/api/server/connect'],
  ['GET', '/api/ticket/categories'],
  ['GET', '/api/post/thumbnail/x.png'],
  ['GET', '/api/profile/picture/admin'],
  ['GET', '/api/plugins/pano-plugin-market/resources/plugin-ui.zip'],
  ['GET', '/api/market/store/products'],
  ['GET', '/api/node/transfer/1'],
  ['GET', '/api/server/pano-plugin/jar'],
  ['GET', '/panel/api/panel/basicData'],
  ['GET', '/panel/api/panel/posts'],
  ['GET', '/panel/api/panel/plugins'],
  ['POST', '/panel/api/auth/login'],
];

// ------------------------------------------------------------------------------------------------ OpenAPI documents

const SPEC_METHODS = new Set(['GET', 'PUT', 'POST', 'DELETE', 'PATCH']);
const colon = (p) => p.replace(/\{([A-Za-z0-9_]+)\}/g, ':$1');

/** @returns {Map<string, any>} `METHOD /full/path` (parameters as `:name`) -> operation object */
export function specOperations(spec) {
  const server = String(spec?.servers?.[0]?.url || '').replace(/\/$/, '');
  const out = new Map();
  for (const [p, item] of Object.entries(spec?.paths || {}))
    for (const [m, op] of Object.entries(item || {})) {
      const method = m.toUpperCase();
      // decision 81: a plugin panel operation of the internal document carries an operation-level `servers` override
      const base = op?.servers?.[0]?.url !== undefined ? String(op.servers[0].url).replace(/\/$/, '') : server;
      if (SPEC_METHODS.has(method)) out.set(`${method} ${colon(base + p)}`, op);
    }
  return out;
}

const TRANSLATIONS_DOCUMENT_OP = '/locales/{code}/translations/types/{type}';
const LIST_ALWAYS_BAD = ['totalPage', 'totalCount'];
const LIST_BESIDE_BAD = ['meta', 'data'];

/**
 * ListShape on the documents themselves (doc 04 section 9 step 3): no schema has `totalPage` / `totalCount`, and none has
 * `meta` or `data` beside a list. There is no allow-list.
 * @returns {string[]} one line per offending schema, with its JSON pointer
 */
export function schemaListProblems(spec) {
  const comps = spec?.components?.schemas || {};
  const deref = (s) => (s && typeof s.$ref === 'string' ? comps[s.$ref.split('/').pop()] : s);
  const isArray = (s) => {
    const t = deref(s)?.type;
    return t === 'array' || (Array.isArray(t) && t.includes('array'));
  };
  const out = [];
  const visit = (node, where) => {
    if (Array.isArray(node)) {
      node.forEach((n, i) => visit(n, `${where}/${i}`));
      return;
    }
    if (!node || typeof node !== 'object') return;
    const props = node.properties;
    if (props && typeof props === 'object' && !Array.isArray(props)) {
      const keys = Object.keys(props);
      for (const k of LIST_ALWAYS_BAD) if (keys.includes(k)) out.push(`${where} has \`${k}\``);
      const arrays = keys.filter((k) => isArray(props[k]));
      for (const k of LIST_BESIDE_BAD) if (keys.includes(k) && arrays.some((a) => a !== k)) out.push(`${where} has \`${k}\` beside the list \`${arrays.find((a) => a !== k)}\``);
      if (keys.includes('data') && isArray(props.data) && keys.some((k) => ['page', 'meta', 'total', 'totalItems'].includes(k))) out.push(`${where} has a \`data\` list with paging companions`);
    }
    for (const [k, v] of Object.entries(node)) visit(v, `${where}/${k}`);
  };
  visit(spec?.paths, '#/paths');
  visit(comps, '#/components/schemas');
  return out;
}

/** Fetches a JSON document; `{ error }` when it is not a 200 JSON body. */
async function fetchSpec(o, p, cookie) {
  const res = await call(o.url, 'GET', p, { headers: { origin: o.url, ...(cookie ? { cookie } : {}) }, timeout: 30000 });
  if (res.status !== 200 || !res.jsonOk) return { error: `GET ${p} answered ${res.status || res.error}${res.status === 200 ? ' (not JSON)' : ''}: ${(res.text || '').slice(0, 120).replace(/\s+/g, ' ')}` };
  return { spec: res.json };
}

/**
 * `METHOD /path` (parameters as `:name`) -> declared query parameter names, read from the served OpenAPI documents. The walk uses
 * it to tell a paged endpoint from an unpaged one (decision 80, list-shape.mjs). Documents that cannot be fetched are skipped:
 * an operation missing from the index counts as paged (the stricter reading).
 * @returns {Promise<Map<string, Set<string>>>}
 */
async function loadQueryParams(o, routes, cookie) {
  const index = new Map();
  const urls = ['/api/v1/openapi.json'];
  if (cookie) urls.push('/api/v1/panel/openapi.json');
  for (const id of new Set(routes.filter((r) => r.owner !== 'core').map((r) => r.owner))) urls.push(`/api/v1/plugins/${id}/_/openapi.json`);
  for (const u of urls) {
    const { spec } = await fetchSpec(o, u, u.includes('/panel/') ? cookie : undefined);
    if (!spec) continue;
    for (const [key, op] of specOperations(spec)) index.set(key, new Set((op.parameters || []).filter((p) => p.in === 'query').map((p) => p.name)));
  }
  return index;
}

/** @returns {boolean} the endpoint is known to declare no paging parameter */
const isUnpaged = (index, route) => {
  const names = index.get(`${route.method === 'ANY' ? 'GET' : route.method} ${route.path}`);
  return names !== undefined && !declaresPaging(names);
};

async function sdkModule(name) {
  return import(pathToFileURL(path.join(sdkDir(), 'bin', 'api', name)).href);
}

/**
 * The OpenAPI checks of BOOT-02 (doc 04 section 9 step 3) and the snapshots (section 5).
 * @returns {Promise<{ text: string, problems: number, counts: object }>}
 */
async function checkSpecs(o, routes, session, fail, warnings) {
  let problems = 0;
  const bad = (route, kind, detail, extra) => {
    if (fail(route, kind, detail, extra)) problems++;
  };
  const coreRoute = (p) => ({ method: 'GET', path: p, owner: 'core' });
  const pluginIds = [...new Set(routes.filter((r) => r.owner !== 'core').map((r) => r.owner))].sort();

  /** @type {{ label: string, scope: 'core' | 'internal' | 'plugin', id?: string, file: string | null, spec: any }[]} */
  const docs = [];
  const core = await fetchSpec(o, '/api/v1/openapi.json');
  if (core.error) bad(coreRoute('/api/v1/openapi.json'), 'SPEC_NOT_SERVED', core.error, { unit: 'PF-23 (OpenAPI endpoints)' });
  else docs.push({ label: 'core', scope: 'core', file: path.join(o.coreDir, 'openapi-core.json'), spec: core.spec });

  if (!session) bad(coreRoute('/api/v1/panel/openapi.json'), 'SPEC_NOT_SERVED', 'the internal document needs a panel session: pass --admin-env', { unit: 'BOOT-02 (gate setup)' });
  else {
    const internal = await fetchSpec(o, '/api/v1/panel/openapi.json', session.cookie);
    if (internal.error) bad(coreRoute('/api/v1/panel/openapi.json'), 'SPEC_NOT_SERVED', internal.error, { unit: 'PF-23 (OpenAPI endpoints)' });
    else docs.push({ label: 'internal', scope: 'internal', file: path.join(o.coreDir, 'openapi-internal.json'), spec: internal.spec });
  }

  for (const id of pluginIds) {
    const p = `/api/v1/plugins/${id}/_/openapi.json`;
    const r = await fetchSpec(o, p);
    if (r.error) bad({ method: 'GET', path: p, owner: id }, 'SPEC_NOT_SERVED', r.error, { unit: 'PF-23 (OpenAPI endpoints) / plugin start' });
    else docs.push({ label: `plugin ${id}`, scope: 'plugin', id, file: path.join(o.snapshotsDir, id, 'openapi.json'), spec: r.spec });
  }

  // 1. the operations of the fetched specs equal the static extract
  const expected = new Map();
  for (const r of routes) if (r.mount === 'API' && SPEC_METHODS.has(r.method)) expected.set(`${r.method} ${r.path}`, r);
  const served = new Map(); // key -> [{ doc, op }]
  for (const d of docs) for (const [key, op] of specOperations(d.spec)) served.set(key, [...(served.get(key) || []), { doc: d, op }]);
  const anyRoutes = routes.filter((r) => r.mount === 'API' && r.method === 'ANY').length;
  if (anyRoutes) warnings.push(`${anyRoutes} extracted route(s) use method ANY; the generator documents GET / PUT / POST / DELETE / PATCH only, so they are outside the equality`);

  const allServed = docs.length === 2 + pluginIds.length; // an unserved document makes the equality meaningless; its own failure is already recorded
  let missing = 0;
  let extra = 0;
  if (allServed) {
    for (const [key, r] of expected)
      if (!served.has(key)) {
        missing++;
        bad(r, 'SPEC_MISSING_OP', `${key} is in the source (${r.class}) but in none of the fetched specs`, { unit: 'PF-23 (OpenAPI generator / route table)' });
      }
    for (const [key, list] of served) {
      if (!expected.has(key)) {
        extra++;
        const [method, p] = key.split(' ');
        const owner = /^\/api\/plugins\/([^/]+)\//.exec(p)?.[1] || /^\/api\/v1\/plugins\/([^/]+)\/_\//.exec(p)?.[1] || 'core';
        bad({ method, path: p, owner }, 'SPEC_EXTRA_OP', `${key} is served in ${list.map((x) => x.doc.label).join(', ')} but no source file declares it`, { unit: 'PF-23 (OpenAPI generator / route table)' });
      }
      if (list.length > 1) bad({ method: key.split(' ')[0], path: key.split(' ')[1], owner: 'core' }, 'SPEC_DUPLICATE_OP', `${key} is in more than one spec: ${list.map((x) => x.doc.label).join(', ')}`, { unit: 'PF-23 (OpenAPI generator)' });
    }
  }

  // 2. scope: a panel operation or a plugin operation is never in the public core spec
  for (const [key, list] of served) {
    const r = expected.get(key);
    if (!r) continue;
    for (const x of list) {
      if (x.doc.scope === 'core' && (r.namespace === 'PANEL' || r.pluginId)) bad(r, 'SPEC_SCOPE', `${key} (${r.class}) is in the public core spec`, { unit: 'PF-23 (OpenAPI generator scopes)' });
      if (x.doc.scope === 'plugin' && r.pluginId !== x.doc.id) bad(r, 'SPEC_SCOPE', `${key} is in the spec of ${x.doc.id} but belongs to ${r.pluginId || 'core'}`, { unit: 'PF-23 (OpenAPI generator scopes)' });
    }
  }

  const structural = problems; // not served / missing / extra / duplicate / wrong scope: the document set itself is inconsistent

  // 3. every public operation has doc
  let publicCore = 0;
  let publicPlugin = 0;
  for (const d of docs) {
    if (d.scope === 'internal') continue;
    for (const [key, op] of specOperations(d.spec)) {
      const [method, p] = key.split(' ');
      const owner = d.scope === 'core' ? 'core' : d.id;
      if (d.scope === 'core') publicCore++;
      else publicPlugin++;
      if (op['x-pano-undocumented'] === true)
        bad({ method, path: p, owner }, d.scope === 'core' ? 'UNDOCUMENTED_CORE_OP' : 'UNDOCUMENTED_PLUGIN_OP', `${key} is public and has no \`doc\` (x-pano-undocumented)`, { unit: d.scope === 'core' ? 'PF-23 (core docs)' : ownerOf({ owner, path: p }) });
    }
  }

  // 4. ListShape on the documents, no allow-list
  for (const d of docs)
    // GET /locales/{code}/translations/types/{type} answers the translations document { data, meta }: a document, not a list, so
    // `data` / `meta` there are not list companions. This one operation is exempt; nothing else is.
    for (const line of schemaListProblems(d.spec).filter((l) => !l.includes(TRANSLATIONS_DOCUMENT_OP))) bad({ method: 'GET', path: `${d.label}`, owner: d.scope === 'plugin' ? d.id : 'core' }, 'SCHEMA_LIST_SHAPE', `${d.label}: ${line}`, { unit: d.scope === 'plugin' ? ownerOf({ owner: d.id, path: '' }) : 'PF-10 / PF-23 (list shape)' });

  // 5. snapshots: written when asked (and only from a document set without problems), otherwise compared with the committed ones
  const snap = { written: 0, unchanged: 0, differs: 0, missing: 0 };
  if (docs.length) {
    const { stableJson } = await sdkModule('util.mjs');
    const { breaks } = await sdkModule('api-compat.mjs');
    const today = new Date().toISOString().slice(0, 10);
    // Snapshots are the baseline of the additive-only rule. They are written from a consistent document set (every operation of
    // the source served exactly once, in the right scope). Content defects found above (an undocumented operation, a legacy list
    // shape) do not stop the write: the unit that fixes one rewrites the snapshot with --allow-breaks, nothing is published yet.
    const specProblems = structural;
    const snapRoute = (d) => ({ method: 'GET', path: d.label, owner: d.scope === 'plugin' ? d.id : 'core' });
    if (o.writeSnapshots && specProblems) warnings.push(`snapshots not written: ${specProblems} structural problem(s) in the fetched documents`);
    for (const d of docs) {
      const next = stableJson(d.spec);
      const exists = fs.existsSync(d.file);
      const current = exists ? fs.readFileSync(d.file, 'utf8') : null;
      if (current === next) {
        snap.unchanged++;
        continue;
      }
      const list = exists ? breaks(JSON.parse(current), d.spec, today) : [];
      for (const b of list) bad(snapRoute(d), 'SNAPSHOT_BREAK', `${d.label}: ${b}`, { unit: 'the unit that changed the operation (additive-only, doc 04 section 6)' });
      if (o.writeSnapshots) {
        if (list.length || specProblems) continue;
        fs.mkdirSync(path.dirname(d.file), { recursive: true });
        fs.writeFileSync(d.file, next);
        snap.written++;
      } else if (exists) {
        snap.differs++;
        bad(snapRoute(d), 'SNAPSHOT_DIFFERS', `${d.label}: ${d.file} differs from the served document (run the gate with --write-snapshots)`, { unit: 'BOOT-02 (snapshots)' });
      } else {
        snap.missing++;
        warnings.push(`no committed snapshot yet for ${d.label} (${d.file})`);
      }
    }
    if (o.writeSnapshots && !specProblems) {
      // read back: a --check must now be clean
      for (const d of docs) {
        const again = fs.existsSync(d.file) ? fs.readFileSync(d.file, 'utf8') : null;
        if (again !== stableJson(d.spec)) bad(snapRoute(d), 'SNAPSHOT_DIFFERS', `${d.label}: ${d.file} was not written as served`, { unit: 'BOOT-02 (snapshots)' });
      }
    }
  }

  const counts = {
    documents: docs.length,
    operations: served.size,
    extracted: expected.size,
    missing,
    extra,
    publicCoreOperations: publicCore,
    publicPluginOperations: publicPlugin,
    snapshots: snap,
  };
  const text = `${docs.length} documents (core, internal, ${pluginIds.length} plugins), ${served.size} operations served / ${expected.size} extracted (${missing} missing, ${extra} extra), ${publicCore} public core operations, ${publicPlugin} public plugin operations; snapshots ${o.writeSnapshots ? `written ${snap.written}, unchanged ${snap.unchanged}` : `unchanged ${snap.unchanged}, differing ${snap.differs}, not committed ${snap.missing}`}; ${problems} problem(s)`;
  return { text, problems, counts };
}

// ------------------------------------------------------------------------------------------------ the upgrade check

/**
 * `--upgrade <fixture.json>`: asserts an upgraded fixture instance (doc 04 section 9 step 5, decision 36). The caller (boot-gate.sh
 * --full) built the pre-cutover fixture, started the store stub, swapped in the new jar and waited for it; this reads the result:
 * gate 0 installed the compatible versions from the stub, the same theme is the active one and in range, the same plugins run, the
 * linked server and the agent node are listed under `agents`. (The column scan is a separate command: column-scan.mjs.)
 */
async function upgradeCheck(o) {
  const fx = JSON.parse(fs.readFileSync(o.upgrade, 'utf8'));
  const failed = [];
  const check = (name, ok, detail = '', unit = 'PF-31 / D4 (gate 0)') => {
    console.log(`  [${ok ? 'ok' : 'FAIL'}] ${name}${detail ? `: ${detail}` : ''}`);
    if (!ok) failed.push({ name, detail, unit });
    return ok;
  };
  console.log(`route-walk: upgrade check of ${o.url} (fixture ${fx.database}, ${fx.pano?.copiedAs})`);

  const health = await call(o.url, 'GET', '/api/v1/health');
  if (!check('the upgraded Pano answers /api/v1/health', health.status === 200, String(health.status || health.error), 'PF-xx (boot after the upgrade)')) return 1;
  check('every /api/v1 answer carries Pano-Api-Level', Boolean(health.apiLevel), String(health.apiLevel), 'PF-xx (API level header)');

  const s = await login(o.url, fx.adminEnv);
  if (s.error) {
    check('the admin of the old install logs in on the new Pano (password hash and session survive the upgrade)', false, s.error, 'PF-xx (auth after upgrade)');
    return 1;
  }
  check('the admin of the old install logs in on the new Pano', true);
  const get = (p) => call(o.url, 'GET', p, { headers: { cookie: s.cookie, origin: o.url }, timeout: 30000 });

  if (o.reconcile) {
    const r = await call(o.url, 'POST', '/api/v1/panel/compatibility/reconcile', {
      headers: { cookie: s.cookie, origin: o.url, 'content-type': 'application/json', ...(s.csrf ? { 'x-csrf-token': s.csrf } : {}) },
      body: '{}',
      timeout: 240000,
    });
    check('POST /api/v1/panel/compatibility/reconcile answers', r.status === 200, `${r.status || r.error} ${(r.text || '').slice(0, 160).replace(/\s+/g, ' ')}`, 'D4 (reconcile endpoint)');
    for (let i = 0; i < 60; i++) {
      const c0 = (await get('/api/v1/panel/compatibility')).json;
      if (c0 && c0.reconcile && c0.reconcile.running === false) break;
      await new Promise((r2) => setTimeout(r2, 2000));
    }
  }

  // gate 0 report
  const compat = await get('/api/v1/panel/compatibility');
  const c = compat.json || {};
  check('GET /api/v1/panel/compatibility answers', compat.status === 200 && compat.jsonOk, String(compat.status), 'D4 (compatibility endpoint)');
  check('the api level range is 1..1 or wider', c.apiLevel && c.apiLevel.min <= 1 && c.apiLevel.current >= 1, JSON.stringify(c.apiLevel), 'D1 (ApiLevel)');
  if (!o.afterRestart) check('the store stub answered the reconcile', c.reconcile?.storeReachable === true, String(c.reconcile?.storeReachable), 'D4 (reconciler / store client)');
  const refused = (c.resources || []).map((r) => `${r.id} ${r.verdict}${r.lastError ? ` (${r.lastError})` : ''}`);
  check('nothing stays refused after gate 0', refused.length === 0, refused.join('; '), 'D4 (reconciler)');
  const installed = new Map((c.reconcile?.installed || []).map((i) => [i.id, i]));
  const ids = [...fx.plugins.map((p) => p.id), fx.theme.id];
  for (const id of o.afterRestart ? [] : ids) {
    const i = installed.get(id);
    // a synthetic old copy (the fixture's market is today's build with its api-level removed) keeps its version string
    const sameVersionOk = fx.plugins.some((p) => p.id === id && p.synthetic);
    check(`gate 0 installed a compatible ${id}`, Boolean(i) && i.version && (i.version !== i.fromVersion || sameVersionOk), i ? `${i.fromVersion} -> ${i.version}${i.restartRequired ? ' (restart required)' : ''}` : 'not in reconcile.installed', 'D4 (reconciler / install)');
  }

  // the same plugins run
  const addons = await get('/api/v1/panel/addons');
  const addonList = addons.json?.items || [];
  for (const p of fx.plugins) {
    const row = addonList.find((a) => a.id === p.id);
    check(`plugin ${p.id} is listed and STARTED`, row?.status === 'STARTED', row ? String(row.status) : `addons answered ${addons.status}, ${addonList.length} listed`, 'D1 / D4 (plugin gate)');
    if (row && 'verdict' in row) check(`plugin ${p.id} verdict OK`, row.verdict === 'OK', String(row.verdict), 'PN-08 / D1');
    const spec = await call(o.url, 'GET', `/api/v1/plugins/${p.id}/_/openapi.json`);
    check(`plugin ${p.id} serves its OpenAPI document`, spec.status === 200, String(spec.status), 'PF-23');
  }

  // the same theme runs
  const themes = await get('/api/v1/panel/themes');
  const themeRow = (themes.json?.items || []).find((t) => t.id === fx.theme.id);
  check(`theme ${fx.theme.id} is installed`, Boolean(themeRow), `themes answered ${themes.status}`, 'D4 (theme install)');
  if (themeRow) {
    const themeNeedsRestart = !o.afterRestart && installed.get(fx.theme.id)?.restartRequired === true;
    check(`theme ${fx.theme.id} is still the active theme (not rewritten by the gate)`, themeRow.active === true || themeNeedsRestart, `active=${themeRow.active}`, 'D1 (UIManager fallback must not write the config)');
    check(`theme ${fx.theme.id} verdict OK and level in range`, (themeRow.verdict === 'OK' && themeRow.apiLevel >= 1) || themeNeedsRestart, `verdict=${themeRow.verdict} apiLevel=${themeRow.apiLevel}`, 'D1 / D4');
  }
  const home = await call(o.url, 'GET', '/', { headers: { accept: 'text/html' }, timeout: 40000 });
  console.log(`  [info] GET / answered ${home.status || home.error} ${home.type} (the theme process is not asserted: UI engines may be unusable in this environment)`);

  // the linked server and the agent node
  const agents = c.agents || [];
  const server = agents.find((a) => a.type === 'SERVER' && a.id === fx.server.id);
  check('the linked server is listed under agents with MANUAL_JAR', Boolean(server) && server.action === 'MANUAL_JAR', server ? JSON.stringify(server) : `agents: ${JSON.stringify(agents)}`, 'D4 (agents) / PF-30 / MC-01');
  const node = agents.find((a) => (a.type === 'AGENT' || a.type === 'NODE') && a.id === fx.node.id);
  check('the agent node is listed under agents with MANUAL_JAR', Boolean(node) && node.action === 'MANUAL_JAR', node ? JSON.stringify(node) : `agents: ${JSON.stringify(agents)}`, 'D4 (agents) / PF-30');

  // what the stub was asked
  if (o.stubUrl) {
    const asked = await call(o.stubUrl, 'GET', '/__stub/requests');
    const text = asked.text || '';
    check('the store stub was asked for compatible versions', /compatible-versions/.test(text), `${(asked.json?.requests || asked.json || []).length ?? '?'} request(s)`, 'D4 (store client)');
    for (const id of ids) check(`the stub was asked about ${id}`, text.includes(id), '', 'D4 (store client)');
  }

  console.log(failed.length ? `route-walk: upgrade check FAILED (${failed.length})` : 'route-walk: upgrade check OK');
  const byUnit = new Map();
  for (const f of failed) byUnit.set(f.unit, [...(byUnit.get(f.unit) || []), f]);
  for (const [unit, list] of byUnit) {
    console.log(`\n  owner: ${unit} (${list.length})`);
    for (const f of list) console.log(`    ${f.name}: ${f.detail}`);
  }
  return failed.length ? 1 : 0;
}

// ------------------------------------------------------------------------------------------------ the walk

function loadAllow(file) {
  const base = { binary: [], redirect: [], emptyError: [], known: [] };
  if (!fs.existsSync(file)) return base;
  return { ...base, ...JSON.parse(fs.readFileSync(file, 'utf8')) };
}

const ruleMatches = (rule, route) =>
  new RegExp(rule.path).test(route.path) && (!rule.method || rule.method === route.method || route.method === 'ANY') && (!rule.owner || rule.owner === route.owner);
const matches = (list, route) => list.some((rule) => ruleMatches(rule, route));

async function login(base, adminEnv) {
  const env = {};
  for (const line of fs.readFileSync(adminEnv, 'utf8').split('\n')) {
    const at = line.indexOf('=');
    if (at > 0) env[line.slice(0, at)] = line.slice(at + 1);
  }
  const res = await call(base, 'POST', '/api/v1/auth/login', {
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ usernameOrEmail: env.SMOKE_ADMIN_USER || env.FIXTURE_ADMIN_USER || 'smokeadmin', password: env.SMOKE_ADMIN_PASSWORD || env.FIXTURE_ADMIN_PASSWORD, panel: true }),
  });
  if (res.status !== 200) return { error: `admin login answered ${res.status} ${(res.text || '').replace(/"(jwt|token|csrfToken)"\s*:\s*"[^"]*"/g, '"$1":"<redacted>"').slice(0, 160)}` };
  const cookie = res.setCookie.map((c) => c.split(';')[0]).join('; ');
  const csrfCookie = res.setCookie.map((c) => c.split(';')[0]).find((c) => /csrf_token=/.test(c));
  return { cookie, csrf: res.json?.csrfToken || (csrfCookie ? decodeURIComponent(csrfCookie.split('=').slice(1).join('=')) : undefined) };
}

async function main() {
  const o = parseArgs(process.argv.slice(2));
  if (o.upgrade) return upgradeCheck(o);
  const allow = loadAllow(o.allow);
  const { routes, skippedPlugins } = collectRoutes(o);
  const failures = [];
  const knownHits = [];
  const warnings = [];
  const usedKnown = new Set();
  // A failure that matches an entry of `known` in the allow-list is a recorded deviation of the owning unit: it is listed on
  // every run, it does not fail the gate, and the entry is deleted by the unit that fixes it (a stale entry is reported).
  const fail = (route, kind, detail, extra = {}) => {
    const f = { method: route.method, path: route.path, owner: route.owner, unit: ownerOf(route), kind, detail, ...extra };
    // --strict-lists: a list that is not { items, page } is a failure, whatever the `known` entries say (BOOT-02: empty allow-list)
    const i = o.strictLists && /LIST_SHAPE$/.test(kind) ? -1 : allow.known.findIndex((rule) => rule.kind === kind && ruleMatches(rule, route));
    if (i >= 0) {
      usedKnown.add(i);
      knownHits.push({ ...f, unit: allow.known[i].unit || f.unit, note: allow.known[i].reason || allow.known[i].note || '' });
      return false;
    }
    failures.push(f);
    return true;
  };

  const health = await call(o.url, 'GET', '/api/v1/health');
  if (health.status !== 200) {
    console.error(`route-walk: ${o.url}/api/v1/health answered ${health.status || health.error}; the instance is not up`);
    process.exit(2);
  }

  // ---- pass 1: no credentials, no body
  let queryParams; // loaded on the first 2xx JSON answer (decision 80: paged or unpaged)
  const results = [];
  const skippedRoutes = [];
  for (const r of o.specsOnly ? [] : routes) {
    if (r.mount === 'ROOT' || r.path === '/*' || r.path.includes('*')) {
      skippedRoutes.push(`${r.method} ${r.path} (${r.class}): UI/template catch-all, not an API route`);
      continue;
    }
    const method = r.method === 'ANY' ? 'GET' : r.method;
    const url = fill(r.path);
    const res = await call(o.url, method, url, { headers: { origin: o.url } });
    results.push({ route: r, method, url, status: res.status, type: res.type });

    if (res.status === 0) {
      fail(r, res.error === 'TIMEOUT' ? 'TIMEOUT' : 'NO_ANSWER', `${method} ${url}: ${res.error}`);
      continue;
    }
    if (res.status >= 500) {
      fail(r, `STATUS_${res.status}`, `${method} ${url} answered ${res.status}: ${res.text.slice(0, 160).replace(/\s+/g, ' ')}`);
      continue;
    }
    if (res.status >= 300 && res.status < 400) {
      if (!matches(allow.redirect, r)) fail(r, 'REDIRECT', `${method} ${url} answered ${res.status} to ${res.location}; not on the redirect allow-list`);
      continue;
    }
    if (res.status >= 200 && res.status < 300) {
      if (!res.jsonOk) {
        if (!matches(allow.binary, r)) fail(r, 'NOT_JSON', `${method} ${url} answered ${res.status} ${res.type || '(no content-type)'} with ${res.bytes} bytes; not on the binary allow-list`);
        continue;
      }
      for (const p of successProblems(res.json)) fail(r, 'SUCCESS_BODY', `${method} ${url}: ${p}`);
      const lp = pageProblems(res.json, !isUnpaged(queryParams ??= await loadQueryParams(o, routes, null), r));
      if (lp.length) fail(r, 'LIST_SHAPE', `${method} ${url}: ${lp.join('; ')}`);
      continue;
    }
    // 4xx
    if (!res.jsonOk) {
      const statusOnly = res.bytes === 0 && matches(allow.emptyError, r);
      if (!statusOnly) fail(r, 'ERROR_NOT_JSON', `${method} ${url} answered ${res.status} ${res.type || '(no content-type)'}: ${res.text.slice(0, 100).replace(/\s+/g, ' ')}`);
      continue;
    }
    const ep = envelopeProblem(res.json);
    if (ep) fail(r, 'ENVELOPE', `${method} ${url} answered ${res.status}: ${ep} (${JSON.stringify(res.json).slice(0, 140)})`);
  }

  // ---- pass 2: the 20 old paths
  const oldResults = [];
  for (const [method, p] of o.specsOnly ? [] : OLD_PATHS) {
    const opts = { headers: { origin: o.url, ...(method === 'POST' ? { 'content-type': 'application/json' } : {}) }, body: method === 'POST' ? '{}' : undefined, timeout: UI_TIMEOUT_MS };
    let res = await call(o.url, method, p, opts);
    if (res.status === 0) res = await call(o.url, method, p, opts); // one retry: a cold theme render
    oldResults.push({ method, path: p, status: res.status, type: res.type, location: res.location || undefined, error: res.error });
    const route = { method, path: p, owner: 'core' };
    // "reaches no endpoint": the router has no handler for it (404, or 405 when a catch-all owns the path for other methods),
    // or, below /panel/, the panel UI's own page handling redirects it away from /api. A JSON answer must still be the envelope.
    // The answer comes from the UI proxy behind the router (SvelteKit's own 404 / 405), so it is not held to the envelope; what
    // proves that no API handler ran is the absence of the Pano-Api-Level header every /api/v1 answer carries.
    const uiRedirect = res.status >= 300 && res.status < 400 && p.startsWith('/panel/') && !(res.location || '').includes('/api/');
    // /api/plugins/* is an API namespace too (decision 81): the router stamps the header on its 404 as well, so there the status alone decides.
    const stamped = res.apiLevel && !p.startsWith('/api/plugins/');
    if (!(res.status === 404 || res.status === 405 || uiRedirect) || stamped) fail(route, 'OLD_PATH_REACHES', `${method} ${p} answered ${res.status} ${res.type}${res.apiLevel ? ' with Pano-Api-Level ' + res.apiLevel : ''}: an old path must reach no endpoint (404 / 405)${res.error ? ' [' + res.error + ']' : ''}`, { unit: 'PF-04 / PF-05 (path scheme) or the owner of the route behind it' });
  }

  // an unknown path below /api/v1 is a 404 envelope from the API itself
  if (!o.specsOnly) {
    const route = { method: 'GET', path: '/api/v1/no-such-route-boot-gate', owner: 'core' };
    const res = await call(o.url, 'GET', route.path);
    if (res.status !== 404) fail(route, 'UNKNOWN_V1_PATH', `GET ${route.path} answered ${res.status} ${res.type}, expected 404`, { unit: 'PF-xx (router)' });
    else if (res.jsonOk && envelopeProblem(res.json)) fail(route, 'UNKNOWN_V1_PATH', `GET ${route.path}: 404 body is not the envelope`, { unit: 'PF-xx (error envelope)' });
  }

  // ---- pass 3: signed in, panel and site lists (GET without path parameters)
  let signed = { done: false, reason: 'no --admin-env given' };
  let session = null;
  if (o.adminEnv && fs.existsSync(o.adminEnv)) {
    const s = await login(o.url, o.adminEnv);
    if (s.error) signed = { done: false, reason: s.error };
    else {
      session = s;
      const deny = /\/(logout|restart|shutdown|stop|update|updates|download|stream|backup|backups|install|export|sse|events|log|logs|console|ws|connect|disconnect|nodes\/transfer)(\/|$)/i;
      let n = 0;
      const signedParams = o.specsOnly ? new Map() : await loadQueryParams(o, routes, s.cookie);
      for (const r of o.specsOnly ? [] : routes) {
        if (r.method !== 'GET' || r.mount === 'ROOT' || r.path.includes(':') || r.path.includes('*') || deny.test(r.path)) continue;
        const res = await call(o.url, 'GET', r.path, { headers: { cookie: s.cookie, origin: o.url } });
        n++;
        if (res.status === 0 || res.status >= 500) {
          fail(r, `SIGNED_STATUS_${res.status || res.error}`, `signed-in GET ${r.path} answered ${res.status || res.error}: ${(res.text || '').slice(0, 140).replace(/\s+/g, ' ')}`);
          continue;
        }
        if (res.status < 200 || res.status >= 300 || !res.jsonOk) continue;
        const lp = pageProblems(res.json, !isUnpaged(signedParams, r));
        if (!lp.length) continue;
        const panel = r.path.startsWith('/api/v1/panel') || /^\/api\/plugins\/[^/]+\/panel(\/|$)/.test(r.path);
        fail(r, panel ? 'PANEL_LIST_SHAPE' : 'LIST_SHAPE', `signed-in GET ${r.path}: ${lp.join('; ')}`);
      }
      signed = { done: true, reason: `${n} GET routes without path parameters read as admin` };
    }
  }

  // ---- pass 4: the OpenAPI documents (BOOT-02, doc 04 section 9 step 3)
  let specSummary = null;
  if (o.specs) specSummary = await checkSpecs(o, routes, session, fail, warnings);

  // ---- report
  const byKind = {};
  for (const f of failures) byKind[f.kind] = (byKind[f.kind] || 0) + 1;
  const summary = {
    routes: routes.length,
    walked: results.length,
    skippedRoutes: skippedRoutes.length,
    skippedPlugins,
    oldPaths: oldResults.length,
    signedInPass: signed,
    specs: specSummary,
    failures: failures.length,
    byKind,
    knownDeviations: knownHits.length,
    staleAllowEntries: allow.known.filter((k, i) => !usedKnown.has(i) && !(o.strictLists && /LIST_SHAPE$/.test(k.kind))).length,
    warnings: warnings.length,
  };
  if (o.report) fs.writeFileSync(o.report, JSON.stringify({ summary, failures, knownDeviations: knownHits, warnings, skippedRoutes, results: results.map((r) => ({ owner: r.route.owner, method: r.method, path: r.url, status: r.status, type: r.type })), oldResults }, null, 2));

  if (specSummary) console.log(`route-walk: specs: ${specSummary.text}`);
  console.log(`route-walk: ${summary.walked} routes walked (${summary.routes} extracted, ${summary.skippedRoutes} UI catch-alls skipped), ${summary.oldPaths} old paths, signed-in pass: ${signed.done ? signed.reason : `skipped (${signed.reason})`}`);
  const notLoaded = skippedPlugins.filter((x) => x.endsWith('(not loaded in the instance)')).map((x) => x.replace(' (not loaded in the instance)', ''));
  for (const s of skippedPlugins.filter((x) => !x.endsWith('(not loaded in the instance)'))) console.log(`route-walk: skipped plugin ${s}`);
  if (notLoaded.length) console.log(`route-walk: ${notLoaded.length} plugin source folders not in the gate (payment/shipping sub-plugins, whitelist): ${notLoaded.join(', ')}`);
  for (const w of warnings) console.log(`route-walk: note: ${w}`);
  const stale = allow.known.map((k, i) => [k, i]).filter(([k, i]) => !usedKnown.has(i) && !(o.strictLists && /LIST_SHAPE$/.test(k.kind))).map(([k]) => k);
  if (stale.length) console.log(`route-walk: ${stale.length} allow-list entr${stale.length === 1 ? 'y' : 'ies'} matched nothing this run (fixed? delete them): ${stale.map((k) => `${k.kind} ${k.path}`).join(' | ')}`);
  if (knownHits.length) {
    const byUnit = new Map();
    for (const k of knownHits) byUnit.set(k.unit, (byUnit.get(k.unit) || 0) + 1);
    console.log(`route-walk: ${knownHits.length} KNOWN deviation(s) waived by route-walk.allow.json (a unit that fixes one deletes its entry): ${[...byUnit].map(([u, n]) => `${u}: ${n}`).join('; ')}`);
    for (const k of knownHits) console.log(`    [known ${k.kind}] ${k.unit} :: ${k.method} ${k.path} - ${k.detail.slice(0, 200)}`);
  }
  if (!failures.length) {
    console.log(`route-walk: OK (${knownHits.length} known deviation(s) waived)`);
    return 0;
  }
  const grouped = new Map();
  for (const f of failures) {
    const k = `${f.unit}`;
    if (!grouped.has(k)) grouped.set(k, []);
    grouped.get(k).push(f);
  }
  console.log(`route-walk: ${failures.length} failure(s) ${JSON.stringify(byKind)}`);
  for (const [unit, list] of grouped) {
    console.log(`\n  owner: ${unit} (${list.length})`);
    for (const f of list) console.log(`    [${f.kind}] ${f.owner}: ${f.detail}`);
  }
  return 1;
}

main().then((code) => process.exit(code), (e) => {
  console.error(`route-walk: ${e.stack || e.message}`);
  process.exit(2);
});
