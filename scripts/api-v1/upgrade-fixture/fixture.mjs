#!/usr/bin/env node
// Upgrade fixture (doc 04 section 9, verification 5; plan decision 36): an install made by the Pano BEFORE the open
// front-end plan, with a store-installed plugin, the market, the blaze theme, a linked server row, a node row and the
// stored `/api/...` URLs the stored-URL migration has to rewrite. The upgrade run itself (swap in the cutover jar, boot
// with the store stub, assert) is unit BOOT-02's; this script only builds the "before" and says what it contains.
//
//   scripts/api-v1/upgrade-fixture/build.sh [options]          (runs `fixture.mjs build`; needs a slot: tools/of-slot.sh)
//   node scripts/api-v1/upgrade-fixture/fixture.mjs inputs      resolve and print the input files, change nothing
//   node scripts/api-v1/upgrade-fixture/fixture.mjs build       build the instance + database (see below)
//   node scripts/api-v1/upgrade-fixture/fixture.mjs seed --db <d>  only the row seeding + column scan, into an installed database
//   node scripts/api-v1/upgrade-fixture/fixture.mjs stop        stop the fixture JVM by its recorded PID, if one runs
//   node scripts/api-v1/upgrade-fixture/fixture.mjs --self-test checks the pure parts (no database, no JVM)
//
// Inputs are RELEASED files already on disk; nothing is downloaded and nothing of the owner's is written to:
//   Pano jar      newest `Pano-1.0.0-alpha.<n>.jar` in <pwp>/build/libs, <umbrella>/test-pano, ~/Downloads, ~/Desktop that has no
//                 `route/ApiPaths.class` (= built before the cutover). --pano-jar overrides.
//   plugin        `pano-plugin-comments` from <umbrella>/test-pano/plugins or ~/Downloads, manifest without `api-level` (level 0).
//   market        a market jar without `api-level` from the same places or <pwp>/plugins/pano-plugin-market/build/libs. When none
//                 exists on disk (the case today), the CURRENT local market build is copied with `api-level` removed from its
//                 manifest and marked `synthetic` in fixture.json: the gate reads it as level 0 exactly like a real old jar. The
//                 old Pano never loads it (it is placed after the install, while no JVM runs). --market-jar overrides.
//   blaze         newest `blaze-v*.zip` in ~/Downloads whose manifest.json has no `apiLevel`. --blaze-zip overrides.
//
// What `build` does (it runs inside a test-instance slot, tools/of-slot.sh, and takes its database and ports from the slot):
//   1. recreates database pano_upgrade_fixture_slot<x> (--db; only pano_upgrade_fixture[_x] is ever dropped) and the folder <out> (default <pwp>/build/upgrade-fixture-slot<x>);
//   2. starts the OLD jar (JRE 11) in <out>/instance on --http-port (default the slot's second Pano port, PANO_OF_SLOT_HTTP2_PORT) with the database from the environment,
//      runs the pre-cutover setup wizard (/api/setup/..., usage mode BOTH, table prefix pano_) and stops it by its recorded PID;
//   3. while no JVM runs: places the plugin jars in plugins/, extracts blaze into themes/blaze-theme (manifest as the installer
//      writes it) and makes it the current theme, points `pano-api-url` at the store stub (--stub-port, default the slot's stub port);
//   4. seeds rows through information_schema (every NOT NULL column gets a default): a LINKED server, an agent node, a post
//      with a thumbnail and an inline image, a server-connect notification, a panel notification and the blaze theme
//      settings, all holding old `/api/...` file URLs;
//   5. runs column-scan.mjs on the result (it must find the seeded columns: that is the "before" evidence) and writes
//      <out>/fixture.json: jars, ports, the seeded ids, the scan result and the exact store-stub command BOOT-02 starts.
// Secrets: the database password is only read from PANO_IT_MARIADB_PASSWORD; the admin password goes to <out>/admin.env (0600).
// Exit codes: 0 ok, 1 a check of the built fixture failed, 2 it could not be built (input missing, port, database, JVM).
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeRunner, pickClient, quoteIdent, quoteString, scan, loadAllow } from '../column-scan.mjs';
import { describeArtifact, listZip, readZipEntry, replaceEntries } from './zip.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const PWP = path.resolve(here, '..', '..', '..');
const UMBRELLA = path.resolve(PWP, '..');

const DB_NAME_RE = /^pano_upgrade_fixture(_[a-z0-9]{1,12})?$/;
// Never touched: the running demo (8088, 3005), the dev servers (3000 to 3003) and the local platform (18087, 18110). Inside a slot the
// ports must also lie in the slot's block (PANO_OF_SLOT_BASE .. +19).
const PROTECTED_PORTS = new Set([8088, 3005, 3000, 3001, 3002, 3003, 18087, 18110]);
const SLOT = process.env.PANO_OF_SLOT || '';
const SLOT_BASE = Number(process.env.PANO_OF_SLOT_BASE || 0);
const PREFIX = 'pano_';

// ------------------------------------------------------------------------------------------------ pure parts

/** Numbers of a version string (`v1.1.0-dev.1` -> [1,1,0,1]) for ordering. */
export function versionKey(v) {
  return (String(v).match(/\d+/g) ?? []).map(Number);
}

export function compareVersions(a, b) {
  const x = versionKey(a);
  const y = versionKey(b);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] ?? -1) - (y[i] ?? -1);
    if (d) return d;
  }
  return 0;
}

/** Whether a Pano jar's entry list is from before the cutover (no ApiPaths class) and its highest DB migration target. */
export function inspectPanoJar(buf) {
  const names = listZip(buf).map((e) => e.name);
  let scheme = 0;
  for (const n of names) {
    const m = n.match(/^com\/panomc\/platform\/db\/migration\/DatabaseMigration(\d+)to(\d+)\.class$/);
    if (m) scheme = Math.max(scheme, Number(m[2]));
  }
  return { preCutover: !names.includes('com/panomc/platform/route/ApiPaths.class'), scheme };
}

/** The same jar with `api-level` taken out of its manifest: how the gate sees a plugin built before the cutover. */
export function stripApiLevel(buf) {
  const manifest = readZipEntry(buf, 'META-INF/MANIFEST.MF');
  if (!manifest) throw new Error('no manifest in the jar');
  const text = manifest.toString('utf8').replace(/^api-level:.*(\r?\n)/m, '');
  return replaceEntries(buf, { 'META-INF/MANIFEST.MF': text });
}

/** `INSERT` for [table] given its information_schema columns: overrides win, other NOT NULL columns without default get a zero value. */
export function buildInsert(table, columns, overrides) {
  const names = [];
  const values = [];
  for (const c of columns) {
    const name = c.name;
    if (name in overrides) {
      names.push(name);
      values.push(sqlValue(overrides[name]));
    } else if (/auto_increment|generated/i.test(c.extra)) {
      continue;
    } else if (c.nullable === 'NO' && c.defaultValue === null) {
      names.push(name);
      values.push(/int|decimal|float|double|bit|bool/i.test(c.dataType) ? '0' : "''");
    }
  }
  for (const key of Object.keys(overrides)) {
    if (!columns.some((c) => c.name === key)) throw new Error(`${table} has no column ${key}`);
  }
  return `INSERT INTO ${quoteIdent(table)} (${names.map(quoteIdent).join(', ')}) VALUES (${values.join(', ')})`;
}

function sqlValue(v) {
  if (v === null) return 'NULL';
  if (typeof v === 'number') return String(v);
  if (typeof v === 'boolean') return v ? '1' : '0';
  return quoteString(v);
}

/** Replaces the value of a top-level `key = value` line of a HOCON config, or adds the line when it is missing. */
export function setConfigKey(text, key, rawValue) {
  const re = new RegExp(`^([ \\t]*${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[ \\t]*=[ \\t]*).*$`, 'm');
  if (re.test(text)) return text.replace(re, (_, head) => `${head}${rawValue}`);
  return text.replace(/\s*$/, '\n') + `${key} = ${rawValue}\n`;
}

// ------------------------------------------------------------------------------------------------ inputs

const home = os.homedir();

function files(dir, re) {
  try {
    return fs.readdirSync(dir).filter((f) => re.test(f)).map((f) => path.join(dir, f));
  } catch {
    return [];
  }
}

export function resolveInputs(o) {
  const out = { problems: [] };

  // Pano jar
  const panoCandidates = o.panoJar
    ? [path.resolve(o.panoJar)]
    : [path.join(PWP, 'build', 'libs'), path.join(UMBRELLA, 'test-pano'), path.join(home, 'Downloads'), path.join(home, 'Desktop')].flatMap((d) =>
        files(d, /^Pano-1\.0\.0-alpha\.\d+\.jar$/)
      );
  const panos = [];
  for (const f of panoCandidates) {
    try {
      const info = inspectPanoJar(fs.readFileSync(f));
      if (info.preCutover) panos.push({ file: f, ...info, n: Number(path.basename(f).match(/alpha\.(\d+)/)?.[1] ?? 0) });
    } catch {
      /* not a readable jar: skipped */
    }
  }
  panos.sort((a, b) => b.n - a.n);
  if (panos[0]) out.pano = panos[0];
  else out.problems.push('no pre-cutover Pano-1.0.0-alpha.<n>.jar found (give --pano-jar)');

  // plugins
  const pluginDirs = [path.join(UMBRELLA, 'test-pano', 'plugins'), path.join(home, 'Downloads')];
  const level0Jar = (id) => {
    const found = [];
    const list = o[id === 'pano-plugin-comments' ? 'commentsJar' : 'marketJar']
      ? [path.resolve(o[id === 'pano-plugin-comments' ? 'commentsJar' : 'marketJar'])]
      : [...pluginDirs.flatMap((d) => files(d, new RegExp(`^${id}.*\\.jar$`))), ...(id === 'pano-plugin-market' ? files(path.join(PWP, 'plugins', 'pano-plugin-market', 'build', 'libs'), /^pano-plugin-market-[^/]*\.jar$/) : [])];
    for (const f of list) {
      try {
        const info = describeArtifact(fs.readFileSync(f), f);
        if (info.type === 'PLUGIN' && info.id === id && info.apiLevel === 0) found.push({ file: f, ...info });
      } catch {
        /* skipped */
      }
    }
    return found.sort((a, b) => fs.statSync(b.file).mtimeMs - fs.statSync(a.file).mtimeMs)[0] ?? null;
  };

  out.comments = level0Jar('pano-plugin-comments');
  if (!out.comments) out.problems.push('no level-0 pano-plugin-comments jar found in test-pano/plugins or ~/Downloads (give --comments-jar)');

  out.market = level0Jar('pano-plugin-market');
  if (!out.market) {
    const current = [path.join(PWP, 'build', 'plugins', 'pano-plugin-market-local-build.jar'), path.join(PWP, 'plugins', 'pano-plugin-market', 'build', 'libs', 'pano-plugin-market-local-build.jar')].find((f) => fs.existsSync(f));
    if (current) {
      const info = describeArtifact(fs.readFileSync(current), current);
      out.market = { file: current, ...info, apiLevel: 0, synthetic: true };
    } else out.problems.push('no market jar on disk, not even a current local build (give --market-jar)');
  }

  // theme
  const zips = (o.blazeZip ? [path.resolve(o.blazeZip)] : files(path.join(home, 'Downloads'), /^blaze-v[\d.]+(-dev\.\d+)?\.zip$/)).flatMap((f) => {
    try {
      const info = describeArtifact(fs.readFileSync(f), f);
      return info.type === 'THEME' && info.id === 'blaze-theme' && info.apiLevel === 0 ? [{ file: f, ...info }] : [];
    } catch {
      return [];
    }
  });
  zips.sort((a, b) => compareVersions(b.version, a.version));
  if (zips[0]) out.blaze = zips[0];
  else out.problems.push('no level-0 blaze-v*.zip found in ~/Downloads (give --blaze-zip)');

  return out;
}

// ------------------------------------------------------------------------------------------------ process helpers

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

/** A recorded PID is only trusted while /proc says it is a java process running in the instance directory. */
function isInstanceJava(pid, instanceDir) {
  if (!pid || !alive(pid)) return false;
  try {
    if (fs.readlinkSync(`/proc/${pid}/cwd`) !== instanceDir) return false;
    return fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').includes('-jar');
  } catch {
    return false;
  }
}

async function stopRecorded(instanceDir, seconds = 90) {
  const pidFile = path.join(instanceDir, 'pano.pid');
  if (!fs.existsSync(pidFile)) return true;
  const pid = Number(fs.readFileSync(pidFile, 'utf8').trim());
  if (!isInstanceJava(pid, instanceDir)) {
    fs.rmSync(pidFile, { force: true });
    return true;
  }
  process.kill(pid, 'SIGTERM');
  for (let i = 0; i < seconds * 2 && alive(pid); i++) await sleep(500);
  if (alive(pid)) {
    process.kill(pid, 'SIGKILL');
    await sleep(1000);
  }
  fs.rmSync(pidFile, { force: true });
  return !alive(pid);
}

async function portFree(port) {
  const { createServer } = await import('node:net');
  return new Promise((resolve) => {
    const s = createServer();
    s.once('error', () => resolve(false));
    s.listen(port, '127.0.0.1', () => s.close(() => resolve(true)));
  });
}

// ------------------------------------------------------------------------------------------------ legacy install

async function api(base, method, route, body) {
  const res = await fetch(base + route, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* not json */
  }
  return { status: res.status, json, text };
}

async function waitFor(what, fn, seconds) {
  for (let i = 0; i < seconds; i += 2) {
    try {
      const v = await fn();
      if (v) return v;
    } catch {
      /* not yet */
    }
    await sleep(2000);
  }
  throw new Error(`timed out after ${seconds}s waiting for ${what}`);
}

/** The pre-cutover setup wizard (/api/setup/..., result:"ok" bodies), the same steps scripts/smoke-install.sh took before the plan. */
async function legacyInstall(base, db, adminPassword, say) {
  const step = async () => (await api(base, 'GET', '/api/setup/step')).json;
  const first = await waitFor('the setup API', step, 240);
  if (first.step !== 0) throw new Error(`expected a fresh install at step 0, got ${JSON.stringify(first).slice(0, 200)}`);

  const put = (body) => api(base, 'PUT', '/api/setup/step', body);
  await put({ clientStep: 0, locale: 'en-US', usageMode: 'BOTH' });
  await put({ clientStep: 1, websiteName: 'Upgrade fixture', websiteDescription: 'Built before the open front-end plan', websiteUrl: base });

  let current = (await step()).step;
  if (current === 2) {
    const dbBody = { host: `${db.host}:${db.port}`, dbName: db.name, username: db.user, password: db.password };
    const verify = await api(base, 'POST', '/api/setup/steps/2/verify', dbBody);
    if (verify.json?.result !== 'ok') throw new Error(`the database did not verify: ${verify.text.slice(0, 200).replace(db.password, '<redacted>')}`);
    await put({ ...dbBody, clientStep: 2, dbType: 'mariadb', prefix: PREFIX });
    current = (await step()).step;
  }
  if (current === 3) {
    await put({ clientStep: 3, hostname: 'smtp.invalid', port: 587, ssl: false, starttls: 'REQUIRED', username: 'fixture', password: 'fixture', sender: 'fixture@example.com', authMethods: '' });
    current = (await step()).step;
  }
  if (current !== 4) throw new Error(`expected step 4 before finishing, got ${current}`);

  say('finishing the install');
  const finish = await api(base, 'POST', '/api/setup/finish', {
    username: 'fixtureadmin',
    email: 'fixture@example.com',
    password: adminPassword,
    setupLocale: 'en-US',
    telemetryEnabled: false,
  });
  if (finish.json?.result !== 'ok') throw new Error(`finish failed: ${finish.text.slice(0, 300).replace(adminPassword, '<redacted>')}`);

  await waitFor('the setup API to report the install', async () => (await step()).error === 'PLATFORM_ALREADY_INSTALLED', 120);
}

// ------------------------------------------------------------------------------------------------ database

async function tableColumns(run, database, table) {
  const out = await run(
    'SELECT COLUMN_NAME, IS_NULLABLE, IFNULL(COLUMN_DEFAULT, "<<NULL>>"), EXTRA, DATA_TYPE FROM information_schema.COLUMNS ' +
      `WHERE TABLE_SCHEMA = ${quoteString(database)} AND TABLE_NAME = ${quoteString(table)} ORDER BY ORDINAL_POSITION`
  );
  return out
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      const [name, nullable, def, extra, dataType] = line.split('\t');
      return { name, nullable, defaultValue: def === '<<NULL>>' ? null : def, extra: extra ?? '', dataType: dataType ?? '' };
    });
}

async function insertRow(run, database, table, overrides) {
  const columns = await tableColumns(run, database, table);
  if (!columns.length) throw new Error(`table ${table} does not exist in ${database}`);
  await run(buildInsert(table, columns, overrides));
  return Number((await run(`SELECT MAX(id) FROM ${quoteIdent(table)}`)).trim());
}

// ------------------------------------------------------------------------------------------------ build

/** Seeds the rows of the fixture into an installed database (tables exist) and runs the column scan on them. */
export async function seedRows(run, database, say = () => {}) {
  say('seeding rows');
  const t = (name) => PREFIX + name;
  const o = { db: database };
  const adminId = Number((await run(`SELECT id FROM ${t('user')} ORDER BY id LIMIT 1`)).trim());
  if (!adminId) throw new Error('no admin user row after the install');
  const nowMs = Date.now();
  const aes = () => crypto.randomBytes(32).toString('base64');

  const nodeUuid = crypto.randomUUID();
  const nodeId = await insertRow(run, o.db, t('node'), { uuid: nodeUuid, name: 'Fixture agent', kind: 'REMOTE', status: 'OFFLINE', approved: 1, aesKey: aes(), addedTime: nowMs, agent: 1, hostname: 'fixture-host' });

  const serverUuid = crypto.randomUUID();
  const serverId = await insertRow(run, o.db, t('server'), {
    name: 'Fixture survival',
    motd: 'A linked server of the fixture',
    host: '127.0.0.1',
    port: 25565,
    playerCount: 0,
    maxPlayerCount: 20,
    type: 'PAPER',
    version: '1.21',
    favicon: '',
    permissionGranted: 1,
    status: 'OFFLINE',
    addedTime: nowMs,
    acceptedTime: nowMs,
    startTime: 0,
    stopTime: 0,
    aesKey: aes(),
    settings: '{}',
    kind: 'LINKED',
    uuid: serverUuid,
  });

  let categoryId = Number((await run(`SELECT IFNULL(MIN(id), 0) FROM ${t('post_category')}`)).trim());
  if (!categoryId) categoryId = await insertRow(run, o.db, t('post_category'), { title: 'News', description: 'Fixture', url: 'news', color: 'ff0000' });
  const thumbnail = '/api/post/thumbnail/fixture-thumb.png';
  const postId = await insertRow(run, o.db, t('post'), {
    title: 'Fixture post',
    categoryId,
    writerUserId: adminId,
    text: `<p>Hello <img src="/api/post/thumbnail/fixture-inline.png" alt="inline">.</p>`,
    date: nowMs,
    moveDate: 0,
    status: 'PUBLISHED',
    thumbnailUrl: thumbnail,
    views: 0,
    url: 'fixture-post',
  });

  await insertRow(run, o.db, t('panel_notification'), {
    userId: adminId,
    type: 'SERVER_CONNECT_REQUEST',
    details: JSON.stringify({ serverId, favicon: '/api/server/icon/default' }),
    status: 'NOT_READ',
    createdAt: nowMs,
    updatedAt: nowMs,
  });
  await insertRow(run, o.db, t('notification'), {
    userId: adminId,
    type: 'AN_ADMIN_REPLIED_TICKET',
    details: JSON.stringify({ ticketId: 1, avatar: '/api/profile/picture/fixtureadmin' }),
    status: 'NOT_READ',
    createdAt: nowMs,
    updatedAt: nowMs,
  });

  const themeSettings = JSON.stringify({ 'blaze-theme': { heroImage: '/api/theme/file/fixture-hero.webp', logo: '/api/websiteLogo?hash=0' } });
  const hasSettings = Number((await run(`SELECT COUNT(*) FROM ${t('system_property')} WHERE \`option\` = 'theme_settings'`)).trim());
  if (hasSettings) await run(`UPDATE ${t('system_property')} SET \`value\` = ${quoteString(themeSettings)} WHERE \`option\` = 'theme_settings'`);
  else await insertRow(run, o.db, t('system_property'), { option: 'theme_settings', value: themeSettings, createdAt: nowMs, updatedAt: nowMs });

  // evidence
  say('column scan of the "before" state');
  const before = await scan(run, o.db, { allow: loadAllow(path.join(here, 'column-scan.allow.json')) });
  const expectedColumns = ['post.text', 'post.thumbnailUrl', 'panel_notification.details', 'notification.details', 'system_property.value'].map((c) => PREFIX + c);
  const found = new Set(before.hits.map((h) => `${h.table}.${h.column}`));
  const missing = expectedColumns.filter((c) => !found.has(c));
  const schemeVersion = Number((await run(`SELECT IFNULL(MAX(CAST(\`key\` AS UNSIGNED)), 0) FROM ${t('scheme_version')}`).catch(() => '0')).trim());


  return { adminId, nodeId, nodeUuid, serverId, serverUuid, postId, thumbnail, before, missing, schemeVersion };
}


function parseArgs(argv) {
  const o = {
    command: '',
    out: path.join(PWP, 'build', SLOT ? `upgrade-fixture-slot${SLOT}` : 'upgrade-fixture'),
    db: process.env.PANO_OF_SLOT_DB2 || '',
    httpPort: Number(process.env.PANO_OF_SLOT_HTTP2_PORT || 0),
    stubPort: Number(process.env.PANO_OF_SLOT_STUB_PORT || 0),
    java: process.env.FIXTURE_JAVA || '/usr/lib/jvm/java-11-openjdk/bin/java',
    panoJar: '',
    commentsJar: '',
    marketJar: '',
    blazeZip: '',
    selfTest: false,
  };
  const args = [...argv];
  if (args[0] && !args[0].startsWith('--')) o.command = args.shift();
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    const v = () => args[++i] ?? '';
    if (a === '--out') o.out = path.resolve(v());
    else if (a === '--db') o.db = v();
    else if (a === '--http-port') o.httpPort = Number(v());
    else if (a === '--stub-port') o.stubPort = Number(v());
    else if (a === '--java') o.java = v();
    else if (a === '--pano-jar') o.panoJar = v();
    else if (a === '--comments-jar') o.commentsJar = v();
    else if (a === '--market-jar') o.marketJar = v();
    else if (a === '--blaze-zip') o.blazeZip = v();
    else if (a === '--self-test') o.selfTest = true;
    else throw new Error(`unknown option ${a}`);
  }
  return o;
}

async function build(o) {
  const say = (m) => console.error(`upgrade-fixture: ${m}`);
  if (!DB_NAME_RE.test(o.db)) throw new Error(`refusing database name ${o.db} (only pano_upgrade_fixture[_x] is ever created or dropped)`);
  for (const p of [o.httpPort, o.stubPort]) {
    if (!Number.isInteger(p) || p < 1024 || p > 65535 || PROTECTED_PORTS.has(p)) throw new Error(`port ${p} is not usable (protected or out of range)`);
    if (SLOT_BASE && (p < SLOT_BASE || p > SLOT_BASE + 19)) throw new Error(`port ${p} is outside the block of slot ${SLOT} (${SLOT_BASE} to ${SLOT_BASE + 19})`);
  }
  if (o.httpPort === o.stubPort) throw new Error('--http-port and --stub-port must differ');
  const password = process.env.PANO_IT_MARIADB_PASSWORD;
  if (!password) throw new Error('PANO_IT_MARIADB_PASSWORD is not set');
  if (!fs.existsSync(o.java)) throw new Error(`JRE not found at ${o.java} (set --java or FIXTURE_JAVA)`);
  if (!(await portFree(o.httpPort))) throw new Error(`port ${o.httpPort} is in use`);

  const inputs = resolveInputs(o);
  if (inputs.problems.length) throw new Error(inputs.problems.join('; '));

  const [dbHost, dbPort] = (process.env.PANO_IT_MARIADB || '127.0.0.1:3306').split(':');
  const client = pickClient({});
  const base = { ...client, host: dbHost, port: dbPort || '3306', user: 'root', password };
  const admin = makeRunner({ ...base, database: null });
  const run = makeRunner({ ...base, database: o.db });

  const instance = path.join(o.out, 'instance');
  if (fs.existsSync(path.join(instance, 'pano.pid'))) await stopRecorded(instance);
  fs.rmSync(o.out, { recursive: true, force: true });
  fs.mkdirSync(path.join(instance, 'plugins'), { recursive: true });
  fs.mkdirSync(path.join(instance, 'themes'), { recursive: true });

  say(`database ${o.db}`);
  await admin(`DROP DATABASE IF EXISTS ${quoteIdent(o.db)}`);
  await admin(`CREATE DATABASE ${quoteIdent(o.db)} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`);

  const panoName = path.basename(inputs.pano.file);
  fs.copyFileSync(inputs.pano.file, path.join(instance, panoName));
  const adminPassword = 'Aa1-' + crypto.randomBytes(12).toString('hex');
  fs.writeFileSync(path.join(o.out, 'admin.env'), `FIXTURE_ADMIN_USER=fixtureadmin\nFIXTURE_ADMIN_PASSWORD=${adminPassword}\n`, { mode: 0o600 });

  // 2. the old Pano installs itself
  say(`starting ${panoName} on ${o.httpPort}`);
  const log = fs.openSync(path.join(instance, 'pano.log'), 'a');
  const child = spawn(o.java, ['-XX:MaxRAMPercentage=40', '-Dpf4j.pluginsDir=plugins', '-jar', panoName, '-nogui'], {
    cwd: instance,
    detached: true,
    stdio: ['ignore', log, log],
    env: {
      ...process.env,
      PANO_DB_HOST: dbHost,
      PANO_DB_PORT: dbPort || '3306',
      PANO_DB_NAME: o.db,
      PANO_DB_USER: 'root',
      PANO_DB_PASSWORD: password,
      PANO_HTTP_PORT: String(o.httpPort),
    },
  });
  child.unref();
  fs.writeFileSync(path.join(instance, 'pano.pid'), String(child.pid));
  await sleep(1000);
  if (!isInstanceJava(child.pid, instance)) throw new Error('the recorded PID is not the fixture JVM (see instance/pano.log)');

  try {
    await legacyInstall(`http://127.0.0.1:${o.httpPort}`, { host: dbHost, port: dbPort || '3306', name: o.db, user: 'root', password }, adminPassword, say);
  } catch (e) {
    await stopRecorded(instance, 30);
    throw new Error(`${e.message} (log: ${path.join(instance, 'pano.log')})`);
  }

  say('stopping the old Pano by its recorded PID');
  if (!(await stopRecorded(instance))) throw new Error('the fixture JVM did not exit');
  await sleep(1000);

  // 3. plugins, theme, config
  const pluginFiles = [];
  const comments = inputs.comments;
  const commentsName = `${comments.id}-${comments.version}.jar`;
  fs.copyFileSync(comments.file, path.join(instance, 'plugins', commentsName));
  pluginFiles.push(commentsName);

  const market = inputs.market;
  const marketName = market.synthetic ? 'pano-plugin-market-local-build.jar' : path.basename(market.file);
  const marketBuf = fs.readFileSync(market.file);
  fs.writeFileSync(path.join(instance, 'plugins', marketName), market.synthetic ? stripApiLevel(marketBuf) : marketBuf);
  pluginFiles.push(marketName);

  const blazeBuf = fs.readFileSync(inputs.blaze.file);
  const themeDir = path.join(instance, 'themes', 'blaze-theme');
  fs.mkdirSync(themeDir, { recursive: true });
  for (const e of listZip(blazeBuf)) {
    if (e.name.endsWith('/')) continue;
    const target = path.join(themeDir, e.name);
    if (!path.resolve(target).startsWith(themeDir + path.sep)) throw new Error(`unsafe zip entry ${e.name}`);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, readZipEntry(blazeBuf, e.name));
  }
  const manifestFile = path.join(themeDir, 'manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
  const now = Date.now();
  Object.assign(manifest, { hash: crypto.createHash('sha256').update(blazeBuf).digest('hex'), createdAt: now, updatedAt: now, installedBy: 'USER' });
  fs.writeFileSync(manifestFile, JSON.stringify(manifest, null, 2));

  const configFile = path.join(instance, 'config.conf');
  if (!fs.existsSync(configFile)) throw new Error('the old Pano wrote no config.conf');
  let config = fs.readFileSync(configFile, 'utf8');
  config = setConfigKey(config, 'current-theme', '"blaze-theme"');
  config = setConfigKey(config, 'pano-api-url', `"http://127.0.0.1:${o.stubPort}"`);
  fs.writeFileSync(configFile, config);

  // 4. + 5. rows and the "before" evidence
  const seeded = await seedRows(run, o.db, say);
  const { adminId, nodeId, nodeUuid, serverId, serverUuid, postId, thumbnail, before, missing, schemeVersion } = seeded;

  const catalogue = [
    path.join(PWP, 'build', 'plugins', 'pano-plugin-comments-local-build.jar'),
    path.join(PWP, 'build', 'plugins', 'pano-plugin-market-local-build.jar'),
    path.join(UMBRELLA, 'themes', 'blaze-theme', 'build'),
  ];
  const summary = {
    builtAt: new Date().toISOString(),
    instanceDir: instance,
    database: o.db,
    tablePrefix: PREFIX,
    httpPort: o.httpPort,
    stubPort: o.stubPort,
    pano: { file: inputs.pano.file, copiedAs: panoName, schemeVersionInJar: inputs.pano.scheme, schemeVersionInDatabase: schemeVersion },
    plugins: [
      { id: comments.id, version: comments.version, apiLevel: comments.apiLevel, source: comments.file, installedAs: commentsName, storeInstalled: true, synthetic: false },
      { id: market.id, version: market.version, apiLevel: 0, source: market.file, installedAs: marketName, synthetic: Boolean(market.synthetic) },
    ],
    theme: { id: inputs.blaze.id, version: inputs.blaze.version, source: inputs.blaze.file, current: true },
    server: { id: serverId, uuid: serverUuid, kind: 'LINKED', name: 'Fixture survival' },
    node: { id: nodeId, uuid: nodeUuid, agent: true },
    post: { id: postId, thumbnailUrl: thumbnail },
    oldUrlColumns: before.hits.map((h) => ({ table: h.table, column: h.column, rows: h.rows })),
    oldUrlColumnsMissing: missing,
    adminEnv: path.join(o.out, 'admin.env'),
    storeStub: {
      artifacts: catalogue,
      command: ['node', path.join(PWP, 'scripts', 'api-v1', 'store-stub.mjs'), ...catalogue.flatMap((a) => ['--artifact', a]), '--port', String(o.stubPort)],
      note: 'The artifacts are the current local builds (api-level 1). Build them first: gradlew :plugins:build, and the blaze theme build folder.',
    },
    afterUpgrade: {
      expect: [
        'the same theme (blaze-theme) and both plugins run after gate 0 installed the compatible versions from the stub',
        'the linked server is listed (id and uuid above) and the node row (agent) is listed under agents',
        'column-scan.mjs --db <database> finds no /api/ without v1/',
      ],
    },
  };
  fs.writeFileSync(path.join(o.out, 'fixture.json'), JSON.stringify(summary, null, 2));

  if (missing.length) {
    console.error(`upgrade-fixture: FAIL the seeded old URLs are not all visible to the column scan; missing: ${missing.join(', ')}`);
    return 1;
  }
  say(`ok: ${path.join(o.out, 'fixture.json')} (scheme ${schemeVersion}, ${before.hits.length} column(s) with old URLs, market ${market.synthetic ? 'synthetic level-0 copy' : 'real old jar'})`);
  return 0;
}

// ------------------------------------------------------------------------------------------------ self test

function assert(cond, message) {
  if (!cond) throw new Error(`self-test: ${message}`);
}

function selfTest() {
  assert(compareVersions('v1.1.0-dev.1', 'v1.0.2') > 0, 'v1.1.0-dev.1 is newer than v1.0.2');
  assert(compareVersions('v1.0.1', 'v1.0.1') === 0, 'equal versions');

  const cols = [
    { name: 'id', nullable: 'NO', defaultValue: null, extra: 'auto_increment', dataType: 'bigint' },
    { name: 'title', nullable: 'NO', defaultValue: null, extra: '', dataType: 'mediumtext' },
    { name: 'views', nullable: 'NO', defaultValue: null, extra: '', dataType: 'bigint' },
    { name: 'note', nullable: 'YES', defaultValue: null, extra: '', dataType: 'text' },
    { name: 'flag', nullable: 'NO', defaultValue: '0', extra: '', dataType: 'tinyint' },
  ];
  const sql = buildInsert('t', cols, { title: "it's" });
  assert(sql === "INSERT INTO `t` (`title`, `views`) VALUES ('it''s', 0)", sql);
  let threw = false;
  try {
    buildInsert('t', cols, { nope: 1 });
  } catch {
    threw = true;
  }
  assert(threw, 'an override of an unknown column is an error');

  assert(setConfigKey('a = 1\ncurrent-theme = "vanilla-theme"\nb = 2\n', 'current-theme', '"blaze-theme"') === 'a = 1\ncurrent-theme = "blaze-theme"\nb = 2\n', 'a key is replaced');
  assert(setConfigKey('current-theme=vanilla-theme\n', 'current-theme', '"x"') === 'current-theme="x"\n', 'the key=value form keeps its shape');
  assert(setConfigKey('a = 1\n', 'pano-api-url', '"u"') === 'a = 1\npano-api-url = "u"\n', 'a missing key is added');
  assert(DB_NAME_RE.test('pano_upgrade_fixture') && DB_NAME_RE.test('pano_upgrade_fixture_b2') && !DB_NAME_RE.test('pano') && !DB_NAME_RE.test('pano_open_frontend_demo'), 'database name guard');
}

async function selfTestJar() {
  const { writeZip } = await import('./zip.mjs');
  const jar = writeZip([
    { name: 'META-INF/MANIFEST.MF', data: 'Manifest-Version: 1.0\nid: pano-plugin-x\napi-level: 1\nversion: 2\n' },
    { name: 'a/B.class', data: Buffer.from([1, 2, 3, 4]) },
  ]);
  const stripped = stripApiLevel(jar);
  const info = describeArtifact(stripped, 'x.jar');
  assert(info.id === 'pano-plugin-x' && info.apiLevel === 0 && info.version === '2', `stripped manifest: ${JSON.stringify(info)}`);
  assert(readZipEntry(stripped, 'a/B.class').equals(Buffer.from([1, 2, 3, 4])), 'other entries are kept');
  assert(describeArtifact(jar, 'x.jar').apiLevel === 1, 'the original still says level 1');
  assert(inspectPanoJar(writeZip([{ name: 'com/panomc/platform/db/migration/DatabaseMigration55to56.class', data: 'x' }])).scheme === 56, 'scheme version from the migration classes');
  assert(inspectPanoJar(writeZip([{ name: 'com/panomc/platform/route/ApiPaths.class', data: 'x' }])).preCutover === false, 'ApiPaths.class marks a cutover jar');
}

// ------------------------------------------------------------------------------------------------ main

async function main() {
  const o = parseArgs(process.argv.slice(2));

  if (o.selfTest) {
    selfTest();
    await selfTestJar();
    console.log('upgrade-fixture: self-test ok (versions, insert builder, config edit, manifest strip, jar inspection)');
    return 0;
  }

  if (o.command === 'inputs') {
    const inputs = resolveInputs(o);
    const show = (x) => (x ? { file: x.file, id: x.id, version: x.version, apiLevel: x.apiLevel, synthetic: x.synthetic ?? false } : null);
    console.log(JSON.stringify({ pano: inputs.pano ? { file: inputs.pano.file, schemeVersion: inputs.pano.scheme } : null, comments: show(inputs.comments), market: show(inputs.market), blaze: show(inputs.blaze), problems: inputs.problems }, null, 2));
    return inputs.problems.length ? 2 : 0;
  }

  if (o.command === 'stop') {
    const ok = await stopRecorded(path.join(o.out, 'instance'));
    console.error(ok ? 'upgrade-fixture: no fixture JVM left' : 'upgrade-fixture: the fixture JVM did not exit');
    return ok ? 0 : 2;
  }

  if (o.command === 'seed') {
    // Seeds rows into a database whose Pano tables already exist (a development aid: no JVM, no install, no files).
    if (!DB_NAME_RE.test(o.db)) throw new Error(`refusing database name ${o.db}`);
    const password = process.env.PANO_IT_MARIADB_PASSWORD;
    if (!password) throw new Error('PANO_IT_MARIADB_PASSWORD is not set');
    const [dbHost, dbPort] = (process.env.PANO_IT_MARIADB || '127.0.0.1:3306').split(':');
    const run = makeRunner({ ...pickClient({}), host: dbHost, port: dbPort || '3306', user: 'root', password, database: o.db });
    const seeded = await seedRows(run, o.db, (m) => console.error(`upgrade-fixture: ${m}`));
    console.log(JSON.stringify({ ...seeded, before: { columnsScanned: seeded.before.columnsScanned, hits: seeded.before.hits.map((h) => `${h.table}.${h.column} (${h.rows})`) } }, null, 2));
    return seeded.missing.length ? 1 : 0;
  }

  if (o.command === 'build') {
    if (!SLOT) {
      console.error('upgrade-fixture: no test-instance slot; run it as  /home/kahverengi/Projects/Pano/pano-open-frontend-spec/tools/of-slot.sh scripts/api-v1/upgrade-fixture/build.sh [options]');
      return 2;
    }
    if (!o.db || !o.httpPort || !o.stubPort) throw new Error('the slot variables PANO_OF_SLOT_DB2, _HTTP2_PORT and _STUB_PORT are missing (use of-slot.sh)');
    return build(o);
  }

  throw new Error('usage: fixture.mjs inputs|build|stop [options] | --self-test (see the header)');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(
    (code) => process.exit(code),
    (e) => {
      console.error(`upgrade-fixture: ${e.message}`);
      process.exit(2);
    }
  );
}
