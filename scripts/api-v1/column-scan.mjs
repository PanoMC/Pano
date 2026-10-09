#!/usr/bin/env node
// Column scan (doc 04 section 9, verification 5): no text column of a Pano database may still hold `/api/` that is not
// followed by `v1/`. This is the proof for the stored-URL migration (DatabaseMigration58to59 and the plugin migrations):
// it looks at every text column of every table, so a table nobody thought of is found too.
//
//   node scripts/api-v1/column-scan.mjs --db <database> [options]
//   node scripts/api-v1/column-scan.mjs --self-test [--live]
//
// Options
//   --db <name>          the database to scan (required; read only, nothing is ever written to it)
//   --container <name>   run the mariadb client in this docker container (default pano-web-platform-db-1 when docker has it)
//   --host/--port <h/p>  use a host `mariadb` / `mysql` client over TCP instead (default 127.0.0.1:3306)
//   --user <name>        database user (default root)
//   --allow <file>       allow list JSON (default: upgrade-fixture/column-scan.allow.json next to this script when it exists):
//                          { "columns": ["pano_table.column", ...],      columns that may hold such text by design
//                            "patterns": ["regex", ...] }                 a hit whose snippet matches one is by design
//   --limit <n>          candidate rows read per column (default 500)
//   --json               print the result as JSON
//   --self-test          check the scanner itself against canned data and exit; with --live also against a scratch
//                        database on the docker MariaDB (needs PANO_IT_MARIADB_PASSWORD; the database is dropped again)
//
// The password is only read from PANO_IT_MARIADB_PASSWORD (or MYSQL_PWD) and never printed.
// Exit codes: 0 no hit, 1 at least one hit, 2 the scan could not run (usage, no client, query failed).
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

/** Databases this script refuses even to read: the owner's demo and dev databases. */
const REFUSED_DATABASES = new Set(['pano_open_frontend_demo', 'pano']);

const TEXT_TYPES = ['char', 'varchar', 'tinytext', 'text', 'mediumtext', 'longtext', 'json'];

/** `/api/` not followed by `v1/` or `plugins/` (the unversioned plugin namespace, decision 81). */
const OLD_API_SQL = String.raw`/api/(?!v1/|plugins/)`;
const OLD_API_JS = /\/api\/(?!v1\/|plugins\/)/g;

// ------------------------------------------------------------------------------------------------ pure parts

export function quoteIdent(name) {
  return '`' + String(name).replace(/`/g, '``') + '`';
}

export function quoteString(value) {
  return "'" + String(value).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'";
}

export function columnsQuery(database) {
  const types = TEXT_TYPES.map(quoteString).join(', ');
  return (
    'SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS ' +
    `WHERE TABLE_SCHEMA = ${quoteString(database)} AND DATA_TYPE IN (${types}) ` +
    'AND TABLE_NAME IN (SELECT TABLE_NAME FROM information_schema.TABLES ' +
    `WHERE TABLE_SCHEMA = ${quoteString(database)} AND TABLE_TYPE = 'BASE TABLE') ` +
    'ORDER BY TABLE_NAME, ORDINAL_POSITION'
  );
}

/** Candidate rows: every value of the column that has an old `/api/`, hex encoded so the output needs no escaping. */
export function candidateQuery(table, column, limit) {
  const c = quoteIdent(column);
  return (
    `SELECT HEX(LEFT(${c}, 8000)) FROM ${quoteIdent(table)} ` +
    `WHERE ${c} REGEXP ${quoteString(OLD_API_SQL)} LIMIT ${Number(limit)}`
  );
}

export function countQuery(table, column) {
  const c = quoteIdent(column);
  return `SELECT COUNT(*) FROM ${quoteIdent(table)} WHERE ${c} REGEXP ${quoteString(OLD_API_SQL)}`;
}

/** Every old `/api/` in [value], as short snippets around the match. */
export function findHits(value, radius = 50) {
  const hits = [];
  for (const m of value.matchAll(OLD_API_JS)) {
    const from = Math.max(0, m.index - radius);
    hits.push(value.slice(from, m.index + radius + 5).replace(/\s+/g, ' '));
  }
  return hits;
}

export function loadAllow(file) {
  const raw = file && fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};
  return {
    columns: new Set(raw.columns ?? []),
    patterns: (raw.patterns ?? []).map((p) => new RegExp(p)),
  };
}

function decodeHex(line) {
  const hex = line.trim();
  if (!hex || hex.length % 2) return '';
  return Buffer.from(hex, 'hex').toString('utf8');
}

/**
 * Scans [database] through [run] (an async function (sql) -> stdout lines of a `mariadb -N -B` call).
 * Returns { columnsScanned, hits: [{ table, column, rows, snippets }], allowed: [...same] }.
 */
export async function scan(run, database, { allow = loadAllow(null), limit = 500 } = {}) {
  const columns = (await run(columnsQuery(database)))
    .split('\n')
    .filter(Boolean)
    .map((line) => line.split('\t'))
    .filter((p) => p.length === 2);
  const hits = [];
  const allowed = [];

  for (const [table, column] of columns) {
    const id = `${table}.${column}`;
    const count = Number((await run(countQuery(table, column))).trim() || '0');
    if (!count) continue;

    const values = (await run(candidateQuery(table, column, limit))).split('\n').filter(Boolean).map(decodeHex);
    const snippets = [];
    let allowedSnippets = 0;
    for (const value of values) {
      for (const snippet of findHits(value)) {
        if (allow.patterns.some((re) => re.test(snippet))) allowedSnippets++;
        else snippets.push(snippet);
      }
    }

    const entry = { table, column, rows: count, snippets: snippets.slice(0, 5), snippetCount: snippets.length };
    if (allow.columns.has(id)) allowed.push(entry);
    else if (snippets.length === 0) {
      // Every hit of the column matched an allowed pattern (or the read value was cut before the hit): no finding.
      if (allowedSnippets > 0) allowed.push(entry);
    } else hits.push(entry);
  }

  return { columnsScanned: columns.length, hits, allowed };
}

// ------------------------------------------------------------------------------------------------ database client

function dockerHas(container) {
  const r = spawnSync('docker', ['inspect', container], { stdio: 'ignore' });
  return r.status === 0;
}

/** An async (sql) -> stdout runner over `docker exec` or a host client. SQL goes over stdin, the password over the environment. */
export function makeRunner({ database, container, host, port, user, password, client }) {
  let argv;
  if (container) {
    argv = ['docker', ['exec', '-i', '-e', 'MYSQL_PWD', container, 'mariadb', `-u${user}`, '-N', '-B', '--default-character-set=utf8mb4', ...(database ? [database] : [])]];
  } else {
    argv = [client, ['-h', host, '-P', String(port), `-u${user}`, '-N', '-B', '--default-character-set=utf8mb4', ...(database ? [database] : [])]];
  }
  return (sql) =>
    new Promise((resolve, reject) => {
      const child = spawn(argv[0], argv[1], { env: { ...process.env, MYSQL_PWD: password }, stdio: ['pipe', 'pipe', 'pipe'] });
      let out = '';
      let err = '';
      child.stdout.on('data', (d) => (out += d));
      child.stderr.on('data', (d) => (err += d));
      child.on('error', reject);
      child.on('close', (code) => {
        if (code === 0) resolve(out);
        else reject(new Error(`database client exited ${code}: ${err.replace(/password[^\n]*/gi, '<redacted>').slice(0, 300)}`));
      });
      child.stdin.end(sql + ';\n');
    });
}

export function pickClient(opts) {
  if (opts.container) return { container: opts.container };
  if (!opts.host && dockerHas('pano-web-platform-db-1')) return { container: 'pano-web-platform-db-1' };
  for (const client of ['mariadb', 'mysql']) {
    if (spawnSync('sh', ['-c', `command -v ${client}`], { stdio: 'ignore' }).status === 0) return { client };
  }
  throw new Error('no database client: neither docker with the MariaDB container nor a host mariadb / mysql client');
}

// ------------------------------------------------------------------------------------------------ self test

function assert(cond, message) {
  if (!cond) throw new Error(`self-test: ${message}`);
}

/** A fake client that serves `data[table][column] = [values]` through the three query kinds the scanner uses. */
function fakeRunner(data) {
  const re = new RegExp(OLD_API_SQL.replace('($|', '(?:$|'));
  return async (sql) => {
    if (sql.includes('information_schema.COLUMNS')) {
      return Object.entries(data)
        .flatMap(([t, cols]) => Object.keys(cols).map((c) => `${t}\t${c}`))
        .join('\n');
    }
    const m = sql.match(/FROM `([^`]+)` WHERE `([^`]+)` REGEXP/);
    assert(m, `unexpected query ${sql}`);
    const rows = (data[m[1]]?.[m[2]] ?? []).filter((v) => re.test(v));
    if (sql.startsWith('SELECT COUNT(*)')) return `${rows.length}\n`;
    assert(sql.startsWith('SELECT HEX(LEFT('), `unexpected query ${sql}`);
    return rows.map((v) => Buffer.from(v, 'utf8').toString('hex')).join('\n') + '\n';
  };
}

async function selfTestPure() {
  // The decision rule on its own.
  const old = ['/api/posts', 'x /api/', '/api/v10/x', '/api/v', '/api/vx', '/api/post/thumbnail/a.png', 'https://s/api/favicon'];
  const fine = ['/api/plugins/p/x', '/api/v1/posts', '/api/v1/', 'no api here', '/apix', 'https://example.com/docs/api', 'api/'];
  const sqlRe = new RegExp(OLD_API_SQL.replace('($|', '(?:$|'));
  for (const v of old) {
    assert(sqlRe.test(v), `SQL pattern should match ${JSON.stringify(v)}`);
    assert(findHits(v).length > 0, `JS rule should hit ${JSON.stringify(v)}`);
  }
  for (const v of fine) {
    assert(!sqlRe.test(v), `SQL pattern should not match ${JSON.stringify(v)}`);
    assert(findHits(v).length === 0, `JS rule should not hit ${JSON.stringify(v)}`);
  }
  assert(findHits('a /api/v1/x and /api/old b').length === 1, 'a value with one good and one old url is one hit');
  assert(quoteIdent('a`b') === '`a``b`', 'identifier quoting');
  assert(quoteString("it's \\") === "'it''s \\\\'", 'string quoting');

  // The scanner over canned data.
  const data = {
    pano_post: { text: ['<img src="/api/post/thumbnail/a.png">', 'clean /api/v1/x'], thumbnailUrl: ['/api/v1/posts/thumbnails/a.png'] },
    pano_notification: { details: ['{"favicon":"/api/v1/server/icon/default"}'] },
    pano_system_property: { value: ['{"hero":"/api/theme/file/h.webp"}', '{"doc":"see /api/old-docs"}'] },
    pano_plugin_x: { blob: ['https://example.com/api/', 'ünïcode /api/ü'] },
  };

  const none = await scan(fakeRunner({ pano_post: { text: ['/api/v1/a'] } }), 'db');
  assert(none.hits.length === 0 && none.columnsScanned === 1, 'a clean database has no hit');

  const found = await scan(fakeRunner(data), 'db');
  const ids = found.hits.map((h) => `${h.table}.${h.column}`).sort();
  assert(JSON.stringify(ids) === JSON.stringify(['pano_plugin_x.blob', 'pano_post.text', 'pano_system_property.value']), `hit columns: ${ids}`);
  assert(found.hits.find((h) => h.table === 'pano_system_property').rows === 2, 'two rows in the property column');
  assert(found.hits.find((h) => h.table === 'pano_plugin_x').snippets.some((s) => s.includes('ünïcode')), 'utf-8 survives the hex round trip');
  assert(found.columnsScanned === 5, 'five columns scanned');

  const allow = { columns: new Set(['pano_plugin_x.blob']), patterns: [/old-docs/] };
  const filtered = await scan(fakeRunner(data), 'db', { allow });
  const left = filtered.hits.map((h) => `${h.table}.${h.column}`).sort();
  assert(JSON.stringify(left) === JSON.stringify(['pano_post.text', 'pano_system_property.value']), `after the allow list: ${left}`);
  assert(filtered.hits.find((h) => h.table === 'pano_system_property').snippetCount === 1, 'the allowed pattern drops one snippet');
  assert(filtered.allowed.length === 1 && filtered.allowed[0].table === 'pano_plugin_x', 'the allowed column is reported as allowed');

  const onlyAllowedPattern = await scan(fakeRunner({ t: { c: ['see /api/old-docs'] } }), 'db', { allow });
  assert(onlyAllowedPattern.hits.length === 0 && onlyAllowedPattern.allowed.length === 1, 'a column whose only hit is allowed is no finding');

  assert(REFUSED_DATABASES.has('pano_open_frontend_demo'), 'the demo database is refused');
}

async function selfTestLive() {
  const password = process.env.PANO_IT_MARIADB_PASSWORD || process.env.MYSQL_PWD;
  if (!password) throw new Error('--live needs PANO_IT_MARIADB_PASSWORD');
  const pick = pickClient({});
  const base = { ...pick, host: '127.0.0.1', port: 3306, user: 'root', password };
  const database = `pano_pf31_scan_${Math.random().toString(36).slice(2, 10)}`;
  const admin = makeRunner({ ...base, database: null });
  await admin(`CREATE DATABASE ${quoteIdent(database)} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`);
  try {
    const db = makeRunner({ ...base, database });
    await db('CREATE TABLE pano_post (id bigint PRIMARY KEY AUTO_INCREMENT, `text` LONGTEXT, n bigint)');
    await db('CREATE TABLE pano_notification (id bigint PRIMARY KEY AUTO_INCREMENT, details MEDIUMTEXT)');
    await db(`INSERT INTO pano_post (\`text\`, n) VALUES (${quoteString('<img src="/api/post/thumbnail/a.png"> ünï\n/api/v1/ok')}, 1), (${quoteString('clean /api/v1/x')}, 2)`);
    await db(`INSERT INTO pano_notification (details) VALUES (${quoteString('{"a":"/api/v1/server/icon/default"}')})`);

    const dirty = await scan(db, database);
    const ids = dirty.hits.map((h) => `${h.table}.${h.column}`);
    assert(JSON.stringify(ids) === JSON.stringify(['pano_post.text']), `live hit columns: ${ids}`);
    assert(dirty.hits[0].rows === 1, 'live: one row holds an old url');
    assert(dirty.hits[0].snippets[0].includes('/api/post/thumbnail/'), 'live: the snippet shows the url');

    await db(`UPDATE pano_post SET \`text\` = REPLACE(\`text\`, '/api/post/thumbnail/', '/api/v1/posts/thumbnails/')`);
    const clean = await scan(db, database);
    assert(clean.hits.length === 0, 'live: clean after the rewrite');
  } finally {
    await admin(`DROP DATABASE IF EXISTS ${quoteIdent(database)}`);
  }
}

// ------------------------------------------------------------------------------------------------ main

function parseArgs(argv) {
  const o = { db: '', container: '', host: '', port: '3306', user: 'root', allow: '', limit: 500, json: false, selfTest: false, live: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const v = () => argv[++i] ?? '';
    if (a === '--db') o.db = v();
    else if (a === '--container') o.container = v();
    else if (a === '--host') o.host = v();
    else if (a === '--port') o.port = v();
    else if (a === '--user') o.user = v();
    else if (a === '--allow') o.allow = path.resolve(v());
    else if (a === '--limit') o.limit = Number(v()) || 500;
    else if (a === '--json') o.json = true;
    else if (a === '--self-test') o.selfTest = true;
    else if (a === '--live') o.live = true;
    else throw new Error(`unknown option ${a}`);
  }
  return o;
}

async function main() {
  const o = parseArgs(process.argv.slice(2));

  if (o.selfTest) {
    await selfTestPure();
    console.log('column-scan: self-test ok (rule, hex round trip, allow list, scanner)');
    if (o.live) {
      await selfTestLive();
      console.log('column-scan: live self-test ok (real MariaDB, scratch database dropped)');
    }
    return 0;
  }

  if (!o.db) throw new Error('--db <database> is required');
  if (!/^[A-Za-z0-9_$-]{1,64}$/.test(o.db)) throw new Error('--db must be a plain database name');
  if (REFUSED_DATABASES.has(o.db)) throw new Error(`refusing to scan ${o.db}: it is not a fixture or test database`);

  const password = process.env.PANO_IT_MARIADB_PASSWORD || process.env.MYSQL_PWD;
  if (!password) throw new Error('PANO_IT_MARIADB_PASSWORD is not set');

  const defaultAllow = path.join(here, 'upgrade-fixture', 'column-scan.allow.json');
  const allow = loadAllow(o.allow || defaultAllow);
  const run = makeRunner({ ...pickClient(o), host: o.host || '127.0.0.1', port: o.port, user: o.user, password, database: o.db });
  const result = await scan(run, o.db, { allow, limit: o.limit });

  if (o.json) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.log(`column-scan: ${result.columnsScanned} text column(s) of ${o.db} scanned`);
    for (const h of result.allowed) console.log(`  allowed  ${h.table}.${h.column} (${h.rows} row(s))`);
    for (const h of result.hits) {
      console.log(`  OLD URL  ${h.table}.${h.column}: ${h.rows} row(s)`);
      for (const s of h.snippets) console.log(`           ...${s}...`);
    }
    console.log(result.hits.length ? `column-scan: FAIL ${result.hits.length} column(s) still hold /api/ without v1/` : 'column-scan: ok, no /api/ without v1/');
  }

  return result.hits.length ? 1 : 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(
    (code) => process.exit(code),
    (e) => {
      console.error(`column-scan: ${e.message}`);
      process.exit(2);
    }
  );
}
