#!/usr/bin/env node
// Behaviour smoke (CX-05): what no Kotlin unit ever ran on a real instance. Runs against a booted test instance of a slot.
//
//   of-slot.sh bun /home/kahverengi/Projects/Pano/pano-showcase/scripts/shots.mjs --jars current --theme vanilla -- \
//     node scripts/api-v1/behaviour-smoke.mjs --out /home/kahverengi/Projects/Pano/.open-frontend-run/gate/behaviour.json
//
// Reads PANO_URL, PANO_ADMIN_USER, PANO_ADMIN_PASSWORD and the slot variables (PANO_OF_SLOT*). Prints one line per check
// (`ok | FAIL | skip <name>: <detail>`), writes a JSON report (--out), exits 1 on a FAIL. See behaviour-smoke.README.md.
//
// Only node built-ins. The script restarts the slot's instance once (prepare step) because three things can only be set while
// Pano is stopped: e-mail verification off, webhooks.allow-private-targets on, one plugin jar without `api-level`. The restart goes
// through e2e-instance.sh (stop, then start --keep); of-slot.sh stops the instance when the command ends.
import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import crypto from "node:crypto";

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(here, "..", "..");
const UMBRELLA = path.resolve(ROOT, "..");
const E2E = path.join(ROOT, "plugins/pano-plugin-market/scripts/e2e-instance.sh");
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ------------------------------------------------------------------------------------------------ arguments / environment
const argv = process.argv.slice(2);
const opt = (name, fallback = "") => {
  const i = argv.indexOf(`--${name}`);

  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback;
};
const OUT = opt("out", path.join(UMBRELLA, ".open-frontend-run/gate/behaviour.json"));
const ONLY = opt("only", "")
  .split(",")
  .filter(Boolean);
const BASE = (process.env.PANO_URL || process.env.PANO_OF_SLOT_URL || "").replace(/\/$/, "");
const ADMIN_USER = process.env.PANO_ADMIN_USER || "";
const ADMIN_PASSWORD = process.env.PANO_ADMIN_PASSWORD || "";
const SLOT = process.env.PANO_OF_SLOT || "";

if (!BASE || !SLOT) {
  console.error("behaviour-smoke: run inside a slot: pano-open-frontend-spec/tools/of-slot.sh bun pano-showcase/scripts/shots.mjs --jars current --theme vanilla -- node scripts/api-v1/behaviour-smoke.mjs --out <file>");
  process.exit(2);
}

if (/:(8088|3005|18087)$/.test(new URL(BASE).host)) {
  console.error("behaviour-smoke: refuses the owner's demo / dev ports");
  process.exit(2);
}

const HOST = new URL(BASE).hostname;
const SINK_PORT = Number(process.env.PANO_OF_SLOT_SINK_PORT || 0);
const STATIC_PORT = Number(process.env.PANO_OF_SLOT_STATIC_PORT || 0);
const INSTANCE_DIR = process.env.PANO_OF_SLOT_DIR || "";
const TAG = crypto.randomBytes(3).toString("hex");

// ------------------------------------------------------------------------------------------------ http client
class Client {
  constructor(label = "anon") {
    this.label = label;
    this.cookies = new Map();
    this.csrf = null;
  }

  cookieHeader() {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ");
  }

  /** @returns {Promise<{status:number, headers:Headers, text:string, json:any, error:any}>} */
  async req(method, urlPath, { body, headers = {}, form, csrf = true, bearer, cookies = true, timeout = 30_000 } = {}) {
    const init = { method, headers: { Accept: "application/json", ...headers }, redirect: "manual", signal: AbortSignal.timeout(timeout) };

    if (cookies && this.cookies.size) init.headers.Cookie = this.cookieHeader();
    if (csrf && this.csrf && !("X-CSRF-Token" in init.headers) && method !== "GET") init.headers["X-CSRF-Token"] = this.csrf;
    if (bearer) init.headers.Authorization = `Bearer ${bearer}`;

    if (form) {
      const data = new FormData();

      for (const [k, v] of Object.entries(form)) data.append(k, typeof v === "string" ? v : String(v));

      init.body = data;
    } else if (body !== undefined) {
      init.body = JSON.stringify(body);
      init.headers["Content-Type"] = "application/json";
    }

    const res = await fetch(BASE + urlPath, init);

    for (const raw of res.headers.getSetCookie?.() ?? []) {
      const pair = raw.split(";")[0];
      const at = pair.indexOf("=");
      const name = pair.slice(0, at).trim();
      const value = pair.slice(at + 1).trim();

      if (!name) continue;
      if (value) this.cookies.set(name, value);
      else this.cookies.delete(name);
    }

    const text = await res.text();
    let json = null;

    try {
      json = JSON.parse(text);
    } catch {
      /* not JSON */
    }

    return { status: res.status, headers: res.headers, text, json, error: json?.error ?? null };
  }

  async login(usernameOrEmail, password, panel = false) {
    const res = await this.req("POST", "/api/v1/auth/login", { body: { usernameOrEmail, password, ...(panel ? { panel: true } : {}) }, csrf: false });

    if (res.status === 200) await this.refreshCsrf(res.json?.csrfToken);

    return res;
  }

  async refreshCsrf(known) {
    if (known) {
      this.csrf = known;

      return;
    }

    const res = await this.req("GET", "/api/v1/auth/csrf");

    this.csrf = res.json?.csrfToken ?? null;
  }
}

const show = (res) => `HTTP ${res.status} ${(res.error ? JSON.stringify(res.error) : res.text).slice(0, 240).replace(/\s+/g, " ")}`;

class Skip extends Error {}
const skip = (why) => {
  throw new Skip(why);
};
const need = (cond, message) => {
  if (!cond) throw new Error(message);
};
const expect = (res, status, what) => need(res.status === status, `${what}: expected ${status}, got ${show(res)}`);
const expectOk = (res, what) => need(res.status >= 200 && res.status < 300, `${what}: expected 2xx, got ${show(res)}`);

// ------------------------------------------------------------------------------------------------ the runner
const results = [];
const cleanups = [];
const context = {};

async function check(id, name, owner, fn) {
  if (ONLY.length && !ONLY.includes(id)) return;

  const started = Date.now();
  let status = "ok";
  let detail = "";

  try {
    detail = (await fn()) ?? "";
  } catch (error) {
    if (error instanceof Skip) {
      status = "skip";
      detail = error.message;
    } else {
      status = "FAIL";
      detail = String(error?.message ?? error).slice(0, 600);
    }
  }

  // each check cleans up what it made, newest first, whatever the outcome
  for (const undo of cleanups.splice(0).reverse()) {
    try {
      await undo();
    } catch (error) {
      detail += ` [cleanup: ${String(error?.message ?? error).slice(0, 120)}]`;
    }
  }

  results.push({ id, name, status, detail, owner: status === "FAIL" ? owner : undefined, ms: Date.now() - started });
  console.log(`${status} ${id} ${name}: ${detail}`);
}

// ------------------------------------------------------------------------------------------------ instance control (prepare step)
function databasePassword() {
  if (process.env.PANO_IT_MARIADB_PASSWORD) return process.env.PANO_IT_MARIADB_PASSWORD;

  const compose = fs.readFileSync(path.join(ROOT, "docker-compose.yml"), "utf8");

  return (compose.match(/^\s*MYSQL_ROOT_PASSWORD:\s*(.+)$/m)?.[1] ?? "").replace(/["'\r\s]/g, "");
}

function sameFile(a, b) {
  try {
    const sa = fs.statSync(a);
    const sb = fs.statSync(b);

    return sa.size === sb.size && spawnSync("cmp", ["-s", a, b]).status === 0;
  } catch {
    return false;
  }
}

function e2e(args) {
  const work = path.join(UMBRELLA, ".open-frontend-run/work");
  const instanceJar = path.join(INSTANCE_DIR, "pano.jar");
  const runJar = fs.existsSync(work) ? fs.readdirSync(work).filter((f) => /^runjar-.*\.jar$/.test(f)).map((f) => path.join(work, f)).find((f) => sameFile(f, instanceJar)) : null;
  const env = {
    ...process.env,
    PANO_IT_MARIADB_PASSWORD: databasePassword(),
    MARKET_E2E_PANO_JAR: runJar || path.join(ROOT, "build/libs/Pano-local-build.jar"),
    MARKET_E2E_PLUGIN_JAR: path.join(ROOT, "build/plugins/pano-plugin-market-local-build.jar"),
    MARKET_E2E_JAVA: "/usr/lib/jvm/java-21-openjdk/bin/java",
    MARKET_E2E_JAVA_OPTS: process.env.MARKET_E2E_JAVA_OPTS || "-Xmx3g",
  };
  const run = spawnSync(E2E, args, { env, encoding: "utf8", maxBuffer: 64 << 20 });

  if (run.status !== 0) throw new Error(`e2e-instance.sh ${args.join(" ")} exited ${run.status}: ${(run.stdout + run.stderr).slice(-400)}`);
}

/** SIGTERM to the recorded JVM, then wait for it: closing 16 plugins and the UI processes takes longer than the 90 s of `e2e-instance.sh stop`. */
function stopJvm() {
  let pid = "";

  try {
    pid = fs.readFileSync(path.join(INSTANCE_DIR, "pano.pid"), "utf8").trim();
  } catch {
    throw new Error("no pano.pid in the instance directory");
  }

  const alive = () => {
    try {
      return /^\d+$/.test(pid) && fs.readlinkSync(`/proc/${pid}/cwd`) === INSTANCE_DIR;
    } catch {
      return false;
    }
  };

  if (!alive()) return;

  process.kill(Number(pid), "SIGTERM");

  const end = Date.now() + 300_000;

  while (alive() && Date.now() < end) spawnSync("sleep", ["1"]);

  if (alive()) throw new Error(`the instance JVM ${pid} did not stop within 300 s`);
}

/** Starts the JVM the way shots.mjs does (not e2e-instance.sh start --keep, which deletes every jar of plugins/ first), waits for health and the market. */
async function launchJvm() {
  const jar = path.join(INSTANCE_DIR, "pano.jar");
  const log = path.join(INSTANCE_DIR, "pano.log");

  try {
    fs.renameSync(log, path.join(INSTANCE_DIR, "pano-before-restart.log"));
  } catch {
    /* no log */
  }

  const out = fs.openSync(log, "w");
  const child = spawn(
    "/usr/lib/jvm/java-21-openjdk/bin/java",
    [...(process.env.MARKET_E2E_JAVA_OPTS || "-Xmx3g").split(/\s+/).filter(Boolean), "-Dpano.market.fakeProvider=true", "-Dpano.market.jobScale=5", "-Dpf4j.pluginsDir=plugins", "-jar", jar, "-nogui"],
    {
      cwd: INSTANCE_DIR,
      detached: true,
      stdio: ["ignore", out, out],
      env: { ...process.env, PANO_DB_HOST: "127.0.0.1", PANO_DB_PORT: "3306", PANO_DB_NAME: process.env.PANO_OF_SLOT_DB, PANO_DB_USER: "root", PANO_DB_PASSWORD: databasePassword(), PANO_HTTP_PORT: new URL(BASE).port },
    },
  );

  child.unref();
  fs.writeFileSync(path.join(INSTANCE_DIR, "pano.pid"), `${child.pid}\n`);

  const code = async (p) => (await fetch(BASE + p, { signal: AbortSignal.timeout(5000), redirect: "manual" }).catch(() => ({ status: 0 }))).status;

  await waitFor(async () => (await code("/api/v1/health")) === 200 && (await code("/api/plugins/pano-plugin-market/store")) === 200, 360, "the restarted instance (health and the market store)");
}

/** Stops Pano, lets `edit` change the files of the stopped instance, starts it again with its data. The sessions are gone afterwards. */
async function restartInstance(edit) {
  stopJvm();
  edit?.();
  await launchJvm();
}

function setConfig(key, value) {
  const file = path.join(INSTANCE_DIR, "config.conf");
  const text = fs.readFileSync(file, "utf8");
  const re = new RegExp(`^(\\s*${key.replace(/[-.]/g, "\\$&")}\\s*=\\s*).*$`, "m");

  need(re.test(text), `config.conf has no '${key}'`);
  fs.writeFileSync(file, text.replace(re, `$1${value}`));
}

// a plugin the instance does not load (shots.mjs loads the gate plugins), so its copy cannot clash with a running one
const BROKEN_ID = "pano-plugin-market-example";

/** A copy of a small plugin jar whose manifest has no `api-level` (level 0: refused by the version gate). */
function brokenPluginJar(dest) {
  const source = path.join(ROOT, `build/plugins/${BROKEN_ID}-local-build.jar`);

  if (!fs.existsSync(source)) return false;

  const dir = fs.mkdtempSync(path.join(path.dirname(dest), ".nolevel-"));

  try {
    if (spawnSync("unzip", ["-q", "-o", source, "-d", dir]).status !== 0) return false;

    const manifest = path.join(dir, "META-INF/MANIFEST.MF");

    fs.writeFileSync(manifest, fs.readFileSync(manifest, "utf8").replace(/^api-level:.*\r?\n/m, ""));

    const made = spawnSync("/usr/lib/jvm/java-21-openjdk/bin/jar", ["--create", "--no-manifest", "--file", dest, "-C", dir, "."]);

    return made.status === 0 && spawnSync("unzip", ["-tq", dest]).status === 0;
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}


// ------------------------------------------------------------------------------------------------ small servers
/** Collects every request it gets; `handler(req, body, res)` may answer, the default answer is 200 `ok`. */
function listen(port, handler) {
  const seen = [];
  const server = http.createServer((req, res) => {
    const chunks = [];

    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const record = { method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks).toString("utf8"), at: Date.now() };

      seen.push(record);

      if (handler) handler(req, record, res);
      else {
        res.writeHead(200, { "content-type": "text/plain" });
        res.end("ok");
      }
    });
  });

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve({ server, seen, close: () => new Promise((r) => server.close(() => r())) }));
  });
}

function rawGet(urlPath) {
  return new Promise((resolve, reject) => {
    const url = new URL(BASE);
    const req = http.request({ host: url.hostname, port: url.port, path: urlPath, method: "GET", headers: { Accept: "*/*" } }, (res) => {
      const chunks = [];

      res.on("data", (c) => chunks.push(c));
      res.on("end", () => resolve({ status: res.statusCode, text: Buffer.concat(chunks).toString("utf8"), headers: res.headers }));
    });

    req.on("error", reject);
    req.setTimeout(20_000, () => req.destroy(new Error("timeout")));
    req.end();
  });
}

async function waitFor(fn, seconds, what) {
  for (let i = 0; i < seconds; i++) {
    const value = await fn();

    if (value) return value;

    await sleep(1000);
  }

  throw new Error(`${what} did not happen within ${seconds}s`);
}

// ------------------------------------------------------------------------------------------------ the admin session
let admin = null;

async function adminLogin() {
  const client = new Client("admin");
  const res = await client.login(ADMIN_USER, ADMIN_PASSWORD, true);

  need(res.status === 200, `admin sign-in: ${show(res)}`);

  return client;
}

const PANEL = "/api/v1/panel";
const deletePlayer = async (client, username) => {
  const del = await client.req("POST", `${PANEL}/players/${username}/delete`, { body: { currentPassword: ADMIN_PASSWORD } });

  if (del.status >= 300) throw new Error(`delete ${username}: ${show(del)}`);
};
const MARKET = "/api/plugins/pano-plugin-market/panel";

// ================================================================================================ checks
async function main() {
  // ---- prepare: three settings that only exist while Pano is stopped --------------------------------------------------------------
  let broken = false;
  let prepared = "";

  try {
    await restartInstance(() => {
      setConfig("require-email-verification", "false");
      setConfig("allow-private-targets", "true");
      broken = INSTANCE_DIR ? brokenPluginJar(path.join(INSTANCE_DIR, "plugins", `${BROKEN_ID}-nolevel.jar`)) : false;
    });
    prepared = `restarted with e-mail verification off, webhook private targets on${broken ? `, ${BROKEN_ID} copy without api-level in plugins/` : ", no broken plugin jar (jar missing in build/plugins)"}`;
  } catch (error) {
    prepared = `PREPARE FAILED: ${String(error.message).slice(0, 300)}`;
  }

  console.log(`prepare ${prepared}`);
  results.push({ id: "prepare", name: "prepare", status: prepared.startsWith("PREPARE FAILED") ? "FAIL" : "ok", detail: prepared, owner: "pano-showcase / e2e-instance.sh" });

  if (prepared.startsWith("PREPARE FAILED")) return;

  await waitFor(async () => (await fetch(`${BASE}/api/v1/health`).catch(() => ({ status: 0 }))).status === 200, 120, "health");
  admin = await adminLogin();

  // 1 -------------------------------------------------------------------------------------------------------------------------------
  await check("01", "register without e-mail verification, then sign in (FX-09)", "Pano/.../route/api/auth/RegisterAPI.kt, AuthProvider.kt", async () => {
    const username = `bsmoke${TAG}`;
    const email = `${username}@example.test`;
    const password = `Sm0ke-${TAG}-Passw0rd`;
    const anon = new Client("anon");
    const reg = await anon.req("POST", "/api/v1/auth/register", { body: { username, email, password, passwordRepeat: password, agreement: true }, csrf: false });

    cleanups.push(async () => {
      await deletePlayer(admin, username);
    });
    expectOk(reg, "register");
    need(reg.json?.emailVerificationRequired !== true, `register still asks for e-mail verification: ${show(reg)}`);

    const fresh = new Client("fresh");
    const login = await fresh.login(email, password);

    expect(login, 200, "sign-in with the new account (by e-mail)");
    need(login.json?.sessionToken || login.json?.csrfToken || fresh.cookies.size, `sign-in gave neither a cookie nor a token: ${show(login)}`);

    const byName = await new Client("by-name").login(username, password);

    expect(byName, 200, "sign-in with the new account (by user name)");

    return `registered ${username}, signed in by e-mail and by name`;
  });

  // 2 -------------------------------------------------------------------------------------------------------------------------------
  await check("02", "cookie session: mutation needs X-CSRF-Token (PF-17)", "Pano/.../access/AccessPlaneHandler.kt, route/api/auth/GetCsrfAPI.kt", async () => {
    const session = await adminLogin();
    const without = await session.req("POST", `${PANEL}/frontend/origins`.replace("/origins", "/keys"), { body: { name: "csrf-probe" }, csrf: false });

    expect(without, 403, "mutation without X-CSRF-Token");
    need(without.error?.code === "INVALID_CSRF_TOKEN", `expected INVALID_CSRF_TOKEN, got ${show(without)}`);

    const token = await session.req("GET", "/api/v1/auth/csrf");

    expect(token, 200, "GET /auth/csrf");
    need(typeof token.json?.csrfToken === "string" && token.json.csrfToken.length > 8, `no csrfToken in ${show(token)}`);

    const withToken = await session.req("POST", `${PANEL}/frontend/keys`, { body: { name: `csrf-probe-${TAG}` }, headers: { "X-CSRF-Token": token.json.csrfToken }, csrf: false });

    expectOk(withToken, "mutation with the token of GET /auth/csrf");
    cleanups.push(async () => {
      await session.req("DELETE", `${PANEL}/frontend/keys/${withToken.json.id}`, { headers: { "X-CSRF-Token": token.json.csrfToken }, csrf: false });
    });

    return "403 INVALID_CSRF_TOKEN without, 2xx with the token";
  });

  // 3 -------------------------------------------------------------------------------------------------------------------------------
  await check("03", "origins: preflight, foreign origin, foreign POST without key (PF-16, PF-17)", "Pano/.../access/OriginPolicy.kt, AccessPlaneHandler.kt", async () => {
    const before = await admin.req("GET", `${PANEL}/frontend/origins`);

    expect(before, 200, "GET /panel/frontend/origins");

    const kept = (before.json?.origins ?? []).map((o) => (typeof o === "string" ? o : o.origin ?? o.url));
    // app.<site host>; the policy accepts only origins on the site's registrable domain, and an IP address has no subdomains,
    // so a test instance on 127.0.0.1 gets the same host on another port (the slot's front-end app port)
    const allowed = /^[\d.]+$|:/.test(HOST) ? `http://${HOST}:${process.env.PANO_OF_SLOT_APP_PORT || "18404"}` : `https://app.${HOST}`;
    const put = await admin.req("PUT", `${PANEL}/frontend/origins`, { body: { origins: [...new Set([...kept, allowed])] } });

    cleanups.push(async () => {
      await admin.req("PUT", `${PANEL}/frontend/origins`, { body: { origins: kept } });
    });
    expect(put, 200, `add ${allowed}`);

    // The policy compares hosts only: an Origin whose host equals the request's Host header is SAME (no CORS headers, a
    // preflight is refused). On an IP / localhost instance the allowed origin shares the Host header's host, so the preflight
    // goes to the same server under the other loopback name (Host: localhost, Origin: http://127.0.0.1:<app port>).
    const loopback = /^[\d.]+$|:/.test(HOST) || HOST === "localhost";
    const preUrl = loopback ? new URL(BASE) : null;

    if (preUrl) preUrl.hostname = HOST === "localhost" ? "127.0.0.1" : "localhost";

    let pre;

    if (preUrl) {
      const res = await fetch(preUrl.origin + "/api/v1/auth/login", { method: "OPTIONS", redirect: "manual", signal: AbortSignal.timeout(30_000), headers: { Origin: allowed, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type,x-csrf-token" } });

      pre = { status: res.status, headers: res.headers, text: await res.text() };
    } else {
      pre = await new Client().req("OPTIONS", "/api/v1/auth/login", { headers: { Origin: allowed, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type,x-csrf-token" } });
    }

    // On an IP / localhost instance the only origin the list accepts (OriginPolicy.validate: same site, and an IP has no subdomains) has
    // the site's own host, and the policy classifies that as SAME (doc 05 section 5), whose preflight is refused by design: the
    // credentialed-CORS answer cannot be reached here. The handler is right (OriginPolicyTest covers ALLOWED); only a named host can show it.
    const sameByDesign = loopback && pre.status === 403;

    if (!sameByDesign) {
      need(pre.status >= 200 && pre.status < 300, `preflight of the allowed origin: ${show(pre)}`);
      need(pre.headers.get("access-control-allow-origin") === allowed, `Access-Control-Allow-Origin is '${pre.headers.get("access-control-allow-origin")}', not ${allowed}`);
      need(pre.headers.get("access-control-allow-credentials") === "true", `Access-Control-Allow-Credentials is '${pre.headers.get("access-control-allow-credentials")}'`);
    }

    const foreign = await new Client().req("OPTIONS", "/api/v1/auth/login", { headers: { Origin: "https://evil.example", "Access-Control-Request-Method": "POST" } });

    expect(foreign, 403, "preflight of a foreign origin");
    need(foreign.error?.code === "ORIGIN_NOT_ALLOWED", `expected ORIGIN_NOT_ALLOWED, got ${show(foreign)}`);

    const post = await new Client().req("POST", "/api/v1/auth/login", { body: { usernameOrEmail: "nobody", password: "nothing" }, headers: { Origin: "https://evil.example" }, csrf: false });

    expect(post, 403, "foreign-origin POST without a key");

    return `${sameByDesign ? `${allowed} is the site's own host here (SAME, preflight 403 by design; ALLOWED needs a named host)` : `${allowed} gets credentialed CORS`}, evil.example gets 403 ORIGIN_NOT_ALLOWED (${foreign.error?.code}), foreign POST ${post.error?.code ?? post.status}`;
  });

  // 4 -------------------------------------------------------------------------------------------------------------------------------
  await check("04", "front-end key: session token, panel refusal, client address (PF-06, PF-08, FX-03)", "Pano/.../access/AccessPlaneHandler.kt, AuthProvider.kt (requireNoSiteToken), panel-ui hooks", async () => {
    const made = await admin.req("POST", `${PANEL}/frontend/keys`, { body: { name: `smoke-${TAG}` } });

    expectOk(made, "create a front-end key");
    need(made.json?.key, `no key in the answer: ${show(made)}`);
    cleanups.push(async () => {
      await admin.req("DELETE", `${PANEL}/frontend/keys/${made.json.id}`);
    });

    const key = made.json.key;
    const username = `bkey${TAG}`;
    const password = `Sm0ke-${TAG}-Passw0rd`;
    const reg = await new Client().req("POST", "/api/v1/auth/register", { body: { username, email: `${username}@example.test`, password, passwordRepeat: password, agreement: true }, csrf: false, headers: { "X-Pano-Frontend-Key": key } });

    cleanups.push(async () => {
      await deletePlayer(admin, username);
    });
    expectOk(reg, "register through the key");

    const ip = "203.0.113.77";
    const login = await new Client().req("POST", "/api/v1/auth/login", { body: { usernameOrEmail: username, password }, csrf: false, cookies: false, headers: { "X-Pano-Frontend-Key": key, "X-Pano-Client-Ip": ip } });

    expect(login, 200, "sign-in with the key");
    need(typeof login.json?.sessionToken === "string" && login.json.sessionToken.length > 10, `no sessionToken in ${show(login)}`);

    const token = login.json.sessionToken;
    const withKey = { "X-Pano-Frontend-Key": key };
    const panelApi = await new Client().req("GET", `${PANEL}/basicData`, { bearer: token, cookies: false, headers: withKey });

    need(panelApi.status === 403 && panelApi.error?.code === "SITE_TOKEN_NOT_ALLOWED", `the site token on /panel/...: expected 403 SITE_TOKEN_NOT_ALLOWED, got ${show(panelApi)}`);

    const panelPage = await new Client().req("GET", "/panel/", { bearer: token, cookies: false, headers: { ...withKey, Accept: "text/html" } });

    need(panelPage.status === 403 || (panelPage.status >= 300 && panelPage.status < 400), `the site token on the panel page: expected a refusal, got HTTP ${panelPage.status}`);

    const sessions = await new Client().req("GET", "/api/v1/profile/sessions", { bearer: token, cookies: false, headers: withKey });

    expect(sessions, 200, "GET /profile/sessions with the token");
    need(JSON.stringify(sessions.json).includes(ip), `the session list does not show the forwarded address ${ip}: ${show(sessions)}`);

    const plain = await new Client().req("POST", "/api/v1/auth/login", { body: { usernameOrEmail: username, password }, csrf: false, cookies: false, headers: { "X-Pano-Client-Ip": "203.0.113.88" } });

    expectOk(plain, "sign-in without the key");

    const plainSession = new Client("plain");

    await plainSession.login(username, password);

    const list = await plainSession.req("GET", "/api/v1/profile/sessions");

    expect(list, 200, "GET /profile/sessions of the key-less session");

    need(!JSON.stringify(list.json).includes("203.0.113.88"), "X-Pano-Client-Ip was honoured without a key");

    return `sessionToken issued; panel API ${panelApi.error.code}; panel page HTTP ${panelPage.status}; ${ip} shown with the key, ignored without`;
  });

  // 5 -------------------------------------------------------------------------------------------------------------------------------
  await check("05", "WebSocket: ticket opens /api/v1/ws once (PF-17)", "Pano/.../access/WsTicketStore.kt, route/api/WebsiteWebSocketAPI.kt", async () => {
    if (typeof WebSocket === "undefined") skip("no global WebSocket in this Node");

    const session = await adminLogin();
    const ticket = await session.req("POST", "/api/v1/auth/ws-ticket", { body: {} });

    expectOk(ticket, "POST /auth/ws-ticket");
    need(ticket.json?.ticket, `no ticket in ${show(ticket)}`);

    const wsBase = BASE.replace(/^http/, "ws");
    const open = (t) =>
      new Promise((resolve) => {
        const ws = new WebSocket(`${wsBase}/api/v1/ws?ticket=${encodeURIComponent(t)}`);
        const timer = setTimeout(() => {
          try {
            ws.close();
          } catch {
            /* closed */
          }
          resolve({ opened: false, why: "timeout" });
        }, 10_000);

        ws.onopen = () => {
          clearTimeout(timer);
          ws.close();
          resolve({ opened: true });
        };
        ws.onerror = (e) => {
          clearTimeout(timer);
          resolve({ opened: false, why: e?.message || "error" });
        };
      });
    const first = await open(ticket.json.ticket);

    need(first.opened, `the first use of the ticket did not open the socket (${first.why})`);

    const second = await open(ticket.json.ticket);

    need(!second.opened, "the second use of the same ticket opened the socket");

    return `first use opened, second refused (${second.why})`;
  });

  // 6 + 7 need the NONE mode (no front-end takes the pages over) ----------------------------------------------------------------------
  const frontend = await admin.req("GET", `${PANEL}/frontend`);
  const originalMode = frontend.json?.mode ?? "THEME";

  // 6 -------------------------------------------------------------------------------------------------------------------------------
  await check("06", "fallback pages: login / activate / renew-password (PF-19)", "Pano/.../route/FallbackPageRoute.kt, FallbackPageRenderer.kt, resources/fallback/fallback.js", async () => {
    const set = await admin.req("PUT", `${PANEL}/frontend`, { body: { mode: "NONE" } });

    cleanups.push(async () => {
      const back = await admin.req("PUT", `${PANEL}/frontend`, { body: { mode: originalMode } });

      if (back.status >= 300) throw new Error(`restore mode ${originalMode}: ${show(back)}`);
    });
    expect(set, 200, "PUT /panel/frontend mode NONE (so that no front-end takes the pages over)");

    const notes = [];

    for (const id of ["auth.login", "auth.activate", "auth.renew-password"]) {
      const res = await new Client().req("GET", `/_pano/${id}`, { headers: { Accept: "text/html" } });

      expect(res, 200, `/_pano/${id}`);

      const csp = res.headers.get("content-security-policy") ?? "";

      need(/default-src 'self'/.test(csp) && /frame-ancestors 'none'/.test(csp), `/_pano/${id}: CSP header missing or weak: '${csp}'`);

      const inline = [...res.text.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].filter((m) => !/\bsrc\s*=/.test(m[1]) && m[2].trim() && !/type\s*=\s*["']application\/json/i.test(m[1]));

      need(inline.length === 0, `/_pano/${id}: ${inline.length} inline script(s)`);
      notes.push(`${id} 200`);
    }

    const js = await new Client().req("GET", "/_pano/assets/fallback.js");

    expect(js, 200, "fallback.js");

    // the sign-in page's own POSTs (fallback.js): GET /auth/csrf, then POST /auth/login with the token
    const username = `bfb${TAG}`;
    const password = `Sm0ke-${TAG}-Passw0rd`;
    const reg = await new Client().req("POST", "/api/v1/auth/register", { body: { username, email: `${username}@example.test`, password, passwordRepeat: password, agreement: true }, csrf: false });

    cleanups.push(async () => {
      await deletePlayer(admin, username);
    });
    expectOk(reg, "register for the fallback sign-in");

    const visitor = new Client("fallback");

    const csrf = await visitor.req("GET", "/api/v1/auth/csrf");

    need(csrf.status === 200 || csrf.status === 401, `fallback sign-in: GET /auth/csrf answered ${show(csrf)}`); // an anonymous visitor gets 401: fallback.js then posts without a token
    visitor.csrf = csrf.status === 200 ? csrf.json?.csrfToken ?? null : null;

    const login = await visitor.req("POST", "/api/v1/auth/login", { body: { usernameOrEmail: username, password } });

    expect(login, 200, "fallback sign-in POST");

    const who = await visitor.req("GET", "/api/v1/profile");

    expect(who, 200, "GET /profile with the fallback session");
    notes.push("sign-in by csrf + POST works");

    return notes.join(", ");
  });

  // 7 -------------------------------------------------------------------------------------------------------------------------------
  await check("07", "URL map override shows in GET /frontend/urls (PF-15)", "Pano/.../frontend/FrontendUrlMap.kt, PanelUpdateFrontendUrlsAPI.kt", async () => {
    const before = await admin.req("GET", `${PANEL}/frontend/urls`);

    expect(before, 200, "GET /panel/frontend/urls");

    const old = before.json?.overrides ?? {};

    cleanups.push(async () => {
      await admin.req("PUT", `${PANEL}/frontend/urls`, { body: { overrides: old } });
    });

    const target = `/my-activate-${TAG}`;
    const put = await admin.req("PUT", `${PANEL}/frontend/urls`, { body: { overrides: { ...old, "auth.activate": target } } });

    expect(put, 200, "PUT /panel/frontend/urls");

    const pub = await new Client().req("GET", "/api/v1/frontend/urls");

    expect(pub, 200, "GET /frontend/urls");

    const text = JSON.stringify(pub.json);

    need(text.includes(target), `the override ${target} is not in the public map: ${text.slice(0, 300)}`);

    return `auth.activate -> ${target} visible in GET /frontend/urls`;
  });

  // 8 -------------------------------------------------------------------------------------------------------------------------------
  await check("08", "front-end settings: active theme answers; descriptor URL in EXTERNAL mode loads without a refresh (PF-20)", "Pano/.../frontend/FrontendDescriptor.kt, FrontendSettings.kt, PanelUpdateFrontendAPI.kt (CX-06)", async () => {
    const own = await new Client().req("GET", "/api/v1/frontend/settings");

    expect(own, 200, "GET /frontend/settings for the active front-end");

    const notes = [`active front-end answers (${Object.keys(own.json ?? {}).slice(0, 6).join(",")})`];

    if (!STATIC_PORT) skip(`${notes[0]}; no static port in the slot, EXTERNAL part not run`);

    const descriptor = { id: `external-${TAG}`, title: "External smoke", urls: {}, settingsSchema: { fields: { accent: { type: "text", label: "Accent", default: "blue" } } } };
    const upstream = await listen(STATIC_PORT, (req, rec, res) => {
      if (req.url === "/pano.json" || req.url === "/descriptor.json") {
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify(descriptor));
      } else {
        res.writeHead(200, { "content-type": "text/html" });
        res.end("<!doctype html><title>external</title>");
      }
    });

    cleanups.push(async () => {
      await upstream.close();
    });

    const origin = `http://127.0.0.1:${STATIC_PORT}`;

    cleanups.push(async () => {
      const back = await admin.req("PUT", `${PANEL}/frontend`, { body: { mode: originalMode, descriptorUrl: "", upstreamUrl: "" } });

      if (back.status >= 300) throw new Error(`restore mode ${originalMode}: ${show(back)}`);
    });

    const set = await admin.req("PUT", `${PANEL}/frontend`, { body: { mode: "EXTERNAL", upstreamUrl: origin, descriptorUrl: `${origin}/pano.json`, force: true } });

    expect(set, 200, "PUT /panel/frontend EXTERNAL with a descriptor URL");

    const asked = upstream.seen.some((r) => r.url === "/pano.json");
    const settings = await new Client().req("GET", "/api/v1/frontend/settings");

    need(settings.status === 200, `GET /frontend/settings after the switch: ${show(settings)}`);

    const text = JSON.stringify(settings.json);

    need(asked, "the descriptor URL was saved but Pano never fetched it without a manual refresh");
    need(text.includes(descriptor.id) || text.includes("accent"), `the descriptor was fetched but GET /frontend/settings does not show it: ${text.slice(0, 300)}`);

    return `${notes[0]}; EXTERNAL descriptor fetched on save and shown`;
  });

  // 9 -------------------------------------------------------------------------------------------------------------------------------
  await check("09", "webhooks: signed ping to a sink; a delivery survives a restart (PF-26, PF-27)", "Pano/.../webhook/WebhookService.kt, WebhookDispatcher.kt, WebhookSender.kt, OutboundHttp.kt (CX-06)", async () => {
    if (!SINK_PORT) skip("no sink port in the slot");

    admin = await adminLogin();

    const sink = await listen(SINK_PORT);
    let sinkOpen = true;

    cleanups.push(async () => {
      if (sinkOpen) await sink.close();
    });

    const sinkUrl = `http://127.0.0.1:${SINK_PORT}/hook`;
    const created = await admin.req("POST", `${PANEL}/webhooks`, { body: { name: `smoke ${TAG}`, url: sinkUrl, signing: "HMAC_SHA256", format: "JSON", events: ["core.*"], maxAttempts: 5 } });

    expectOk(created, "create the webhook endpoint (webhooks.allow-private-targets is on)");
    need(created.json?.secret, `no secret in ${show(created)}`);

    const id = created.json.id;
    const secret = created.json.secret;

    cleanups.push(async () => {
      await admin.req("DELETE", `${PANEL}/webhooks/${id}`);
    });

    const test = await admin.req("POST", `${PANEL}/webhooks/${id}/test`, { body: {} });

    expectOk(test, "POST /webhooks/:id/test");

    const hit = await waitFor(async () => sink.seen.find((r) => r.method === "POST" && r.url === "/hook"), 20, "the ping at the sink");
    const header = hit.headers["x-pano-signature"] ?? "";
    const t = /t=(\d+)/.exec(header)?.[1];
    const v1 = /v1=([0-9a-f]+)/.exec(header)?.[1];

    need(t && v1, `no X-Pano-Signature (t=,v1=) in '${header}'`);

    const expected = crypto.createHmac("sha256", secret).update(`${t}.${hit.body}`).digest("hex");

    need(expected === v1, "the signature does not verify with the secret shown at creation");
    need(/core\.test\.ping/.test(hit.body + JSON.stringify(hit.headers)), `the ping body/headers do not name core.test.ping: ${hit.body.slice(0, 200)}`);

    // a delivery that cannot go out now: the sink is closed, a real event is made, then Pano restarts and the sink comes back
    await sink.close();
    sinkOpen = false;

    const username = `bwh${TAG}`;
    const password = `Sm0ke-${TAG}-Passw0rd`;
    const reg = await new Client().req("POST", "/api/v1/auth/register", { body: { username, email: `${username}@example.test`, password, passwordRepeat: password, agreement: true }, csrf: false });

    cleanups.push(async () => {
      const again = await adminLogin();

      await deletePlayer(again, username);
    });
    expectOk(reg, "register (a core event for the endpoint)");

    const pending = await waitFor(async () => {
      const list = await admin.req("GET", `${PANEL}/webhooks/${id}/deliveries`);

      return (list.json?.items ?? []).find((d) => d.event !== "core.test.ping" && d.status !== "SUCCEEDED");
    }, 20, "a not yet delivered row for the new event");
    const eventName = pending.event;

    await restartInstance();

    const after = await listen(SINK_PORT);

    sink.seen.length = 0;
    sinkOpen = false;
    cleanups.push(async () => {
      await after.close();
    });
    admin = await adminLogin();

    let arrived = null;

    try {
      arrived = await waitFor(async () => after.seen.find((r) => r.method === "POST" && r.url === "/hook" && !/core\.test\.ping/.test(r.body)), 180, "the pending delivery after the restart");
    } catch (error) {
      const list = await admin.req("GET", `${PANEL}/webhooks/${id}/deliveries`);

      throw new Error(`${error.message}; row was ${pending.status} (attempts ${pending.attempts ?? "?"}); rows now: ${JSON.stringify((list.json?.items ?? []).map((d) => [d.event, d.status, d.attempts])).slice(0, 300)}`);
    }

    return `signed ping verified (t=${t}); '${eventName}' left ${pending.status} before the restart went out after it (${arrived.body.slice(0, 40).replace(/\s+/g, " ")}...)`;
  });

  // 10 ------------------------------------------------------------------------------------------------------------------------------
  await check("10", "version gate: a plugin jar without api-level is refused and listed (PF-24, PF-25)", "Pano/.../PluginManager.kt, compatibility/CompatibilityReconciler.kt, PanelGetCompatibilityAPI.kt", async () => {
    if (!broken) skip("no broken jar was made (source jar missing in build/plugins)");

    admin = await adminLogin();

    const log = fs.readFileSync(path.join(INSTANCE_DIR, "pano.log"), "utf8");

    need(new RegExp(`Plugin '${BROKEN_ID}'[^\\n]*(needs API level|TOO_OLD)`).test(log), `the boot log has no refusal line for ${BROKEN_ID}`);

    const compat = await admin.req("GET", `${PANEL}/compatibility`);

    expect(compat, 200, "GET /panel/compatibility");
    need(compat.text.includes(BROKEN_ID), `GET /panel/compatibility does not name ${BROKEN_ID}: ${compat.text.slice(0, 300)}`);

    const started = await admin.req("GET", `${PANEL}/plugins`).catch(() => null);
    const running = started && started.status === 200 ? JSON.stringify(started.json) : "";
    const startedLine = new RegExp(`Start plugin '${BROKEN_ID}`).test(log);

    need(!startedLine, `${BROKEN_ID} was started although its jar has no api-level`);
    context.compat = compat.json;

    return `${BROKEN_ID} refused at boot, listed in /panel/compatibility${running ? "" : ""}, no 'Start plugin' line`;
  });

  // 11 ------------------------------------------------------------------------------------------------------------------------------
  await check("11", "package files: views.json, fallback.css, no path traversal (PF-14, PF-28)", "Pano/.../PluginUiManager.kt, route for /plugins/:id/_/ui/* (CX-06)", async () => {
    const own = new Client();
    const views = await own.req("GET", "/api/v1/plugins/pano-plugin-market/_/ui/contract/views.json");
    const problems = [];

    if (views.status !== 200) problems.push(`views.json: ${show(views)}`);
    else if (!views.json) problems.push("views.json does not parse");

    const css = await own.req("GET", "/api/v1/plugins/pano-plugin-market/_/ui/client/fallback.css");

    if (css.status !== 200) problems.push(`client/fallback.css: ${show(css)}`);

    const traversal = [];

    for (const p of ["/api/v1/plugins/pano-plugin-market/_/ui/../../config.conf", "/api/v1/plugins/pano-plugin-market/_/ui/%2e%2e/%2e%2e/config.conf", "/api/v1/plugins/pano-plugin-market/_/ui/contract/..%2f..%2f..%2fconfig.conf", "/api/v1/plugins/pano-plugin-market/_/ui/contract/../../../../config.conf"]) {
      const res = await rawGet(p).catch((e) => ({ status: 0, text: String(e) }));

      traversal.push(res.status);

      if (res.status === 200 && /website-url|server\s*\{/.test(res.text)) problems.push(`traversal ${p} served config.conf`);
      else if (![404, 400, 403].includes(res.status) && res.status !== 308 && res.status !== 301 && res.status !== 302) problems.push(`traversal ${p}: HTTP ${res.status}`);
    }

    need(problems.length === 0, problems.join(" | "));

    return `views.json 200, fallback.css 200, traversal answers ${traversal.join("/")}`;
  });

  // 12 ------------------------------------------------------------------------------------------------------------------------------
  await check("12", "widgets: loader.js and index.json when the instance has a widget runtime", "Pano/.../route/api/widgets/*", async () => {
    const loader = await new Client().req("GET", "/api/v1/widgets/loader.js");
    const index = await new Client().req("GET", "/api/v1/widgets/index.json");

    if (loader.status === 404 && index.status === 404) skip("no widget runtime on this instance (both answer 404)");

    expect(loader, 200, "GET /widgets/loader.js");
    expect(index, 200, "GET /widgets/index.json");
    need(index.json && typeof index.json === "object", "index.json does not parse");
    need(/javascript/.test(loader.headers.get("content-type") ?? ""), `loader.js content type is ${loader.headers.get("content-type")}`);

    return `loader.js ${loader.text.length} bytes, index.json keys ${Object.keys(index.json).slice(0, 5).join(",")}`;
  });

  // 13 ------------------------------------------------------------------------------------------------------------------------------
  await check("13", "OpenAPI: core and market documents parse and name operations (PF-12)", "Pano/.../route/api/GetOpenApiAPI.kt, openapi generator", async () => {
    const core = await new Client().req("GET", "/api/v1/openapi.json");

    expect(core, 200, "GET /openapi.json");
    need(core.json?.openapi || core.json?.swagger, `/openapi.json is not an OpenAPI document: ${core.text.slice(0, 120)}`);

    const operations = (doc) => Object.entries(doc.paths ?? {}).flatMap(([p, item]) => Object.keys(item).filter((m) => /^(get|post|put|delete|patch)$/.test(m)).map((m) => `${m.toUpperCase()} ${p}`));
    const coreOps = operations(core.json);

    need(coreOps.length > 10, `only ${coreOps.length} operations in the core document`);

    const missing = ["POST /auth/login", "GET /site-info", "GET /auth/csrf"].filter((op) => !coreOps.some((o) => o === op || o.endsWith(op.slice(op.indexOf(" ")))));

    need(missing.length === 0, `the core document does not name ${missing.join(", ")}`);

    const market = await new Client().req("GET", "/api/v1/plugins/pano-plugin-market/_/openapi.json");

    expect(market, 200, "GET the market's openapi.json");

    const marketOps = operations(market.json ?? {});

    need(marketOps.length > 0, "the market document names no operation");

    return `core ${coreOps.length} operations, market ${marketOps.length}`;
  });

  // 14 ------------------------------------------------------------------------------------------------------------------------------
  await check("14", "POST /panel/compatibility/reconcile answers with a readable report (PF-25)", "Pano/.../compatibility/CompatibilityReconciler.kt, PanelReconcileCompatibilityAPI.kt", async () => {
    admin = await adminLogin();

    const res = await admin.req("POST", `${PANEL}/compatibility/reconcile`, { body: {}, timeout: 120_000 });

    expect(res, 200, "POST /panel/compatibility/reconcile");
    need(res.json && typeof res.json === "object", `the report is not JSON: ${res.text.slice(0, 200)}`);

    return `report keys: ${Object.keys(res.json).slice(0, 8).join(",")}`;
  });

  // 15 ------------------------------------------------------------------------------------------------------------------------------
  await check("15", "/sitemap lists a post and a market product (PF-10, MK-13)", "Pano/.../route/api/GetSitemapAPI.kt, market/frontend/MarketSitemapProvider.kt", async () => {
    admin = await adminLogin();

    const cat = await admin.req("POST", `${PANEL}/post/categories`, { body: { title: `Smoke ${TAG}`, description: "smoke", url: `smoke-${TAG}`, color: "#ff6a2b" } });

    expectOk(cat, "post category");

    const categoryId = cat.json?.id ?? cat.json?.category?.id ?? (await admin.req("GET", `${PANEL}/post/categories`)).json?.categories?.find((c) => c.url === `smoke-${TAG}`)?.id;

    cleanups.push(async () => {
      await admin.req("DELETE", `${PANEL}/post/categories/${categoryId}`);
    });

    const post = await admin.req("POST", `${PANEL}/post`, { form: { title: `Smoke post ${TAG}`, category: categoryId, text: "<p>smoke</p>", publish: "true" } });

    expectOk(post, "create a post");

    const postId = post.json?.id;

    cleanups.push(async () => {
      if (postId) await admin.req("DELETE", `${PANEL}/posts/${postId}`);
    });

    const shelf = await admin.req("POST", `${MARKET}/categories`, { form: { name: `Smoke ${TAG}`, status: "ACTIVE" } });
    const slug = `smoke-product-${TAG}`;
    let productId = null;

    if (shelf.status >= 200 && shelf.status < 300) {
      const product = await admin.req("POST", `${MARKET}/products`, { form: { name: `Smoke product ${TAG}`, slug, price: "10.00", status: "ACTIVE", categoryId: shelf.json.id } });

      if (product.status >= 200 && product.status < 300) productId = product.json?.id;
      else context.productError = show(product);

      cleanups.push(async () => {
        if (productId) await admin.req("DELETE", `${MARKET}/products/${productId}`);
        await admin.req("DELETE", `${MARKET}/categories/${shelf.json.id}`);
      });
    } else context.productError = show(shelf);

    const map = await new Client().req("GET", "/api/v1/sitemap");

    expect(map, 200, "GET /sitemap");

    const text = map.text;
    const postSlug = post.json?.url ?? `smoke-post-${TAG}`;

    need(text.includes(postSlug) || text.toLowerCase().includes(`smoke-post-${TAG}`) || (postId && text.includes(`/${postId}`)), `the sitemap does not list the new post: ${text.slice(0, 300)}`);
    need(productId, `the market product could not be made (${context.productError})`);
    need(text.includes(slug), `the sitemap does not list the product '${slug}': ${text.slice(0, 300)}`);

    return `post and product '${slug}' are in the sitemap`;
  });
  // 16 ------------------------------------------------------------------------------------------------------------------------------
  await check("16", "plugin paths (decision 81): old paths 404, site and panel routes at /api/plugins/<id>, panel route refuses (no session, no permission, no CSRF)", "Pano/.../route/ApiPaths.kt, RouterProvider, PanelAuthProvider, AccessPlaneHandler.kt", async () => {
    const problems = [];
    const anon = new Client("anon");

    for (const old of ["/api/v1/plugins/pano-plugin-market/store/products", "/api/v1/panel/plugins/pano-plugin-market/products", "/api/panel/plugins/pano-plugin-market/products"]) {
      const res = await anon.req("GET", old);

      if (res.status !== 404) problems.push(`old path ${old}: expected 404, got ${show(res)}`);
    }

    const site = await anon.req("GET", "/api/plugins/pano-plugin-market/store/products");

    if (site.status !== 200) problems.push(`plugin site route: expected 200, got ${show(site)}`);

    const noSession = await anon.req("GET", `${MARKET}/products`);

    if (noSession.status !== 401 && noSession.status !== 403) problems.push(`plugin panel route without a session: expected 401/403, got ${show(noSession)}`);

    // a registered player has a site session but no panel permission
    const username = `psmoke${TAG}`;
    const password = `Sm0ke-${TAG}-Passw0rd`;
    const reg = await new Client("reg").req("POST", "/api/v1/auth/register", { body: { username, email: `${username}@example.test`, password, passwordRepeat: password, agreement: true }, csrf: false });

    cleanups.push(async () => {
      await deletePlayer(admin, username);
    });
    expectOk(reg, "register a player without permissions");

    const player = new Client("player");
    const login = await player.login(username, password, true);
    const noPermission = login.status === 200 ? await player.req("GET", `${MARKET}/products`) : login;

    if (noPermission.status !== 401 && noPermission.status !== 403) problems.push(`plugin panel route without permission: expected 401/403, got ${show(noPermission)}`);

    const session = await adminLogin();
    const allowed = await session.req("GET", `${MARKET}/products`);

    if (allowed.status !== 200) problems.push(`plugin panel route as admin: expected 200, got ${show(allowed)}`);

    const noCsrf = await session.req("POST", `${MARKET}/products`, { form: { name: `csrf ${TAG}` }, csrf: false });

    if (noCsrf.status !== 403 || noCsrf.error?.code !== "INVALID_CSRF_TOKEN") problems.push(`plugin panel mutation without CSRF token: expected 403 INVALID_CSRF_TOKEN, got ${show(noCsrf)}`);

    need(problems.length === 0, problems.join(" | "));

    return `old paths 404, site 200, panel ${noSession.status} without session, ${noPermission.status} without permission, 200 as admin, 403 INVALID_CSRF_TOKEN without token`;
  });
}

// ================================================================================================ run
const startedAt = new Date().toISOString();
let crashed = null;

try {
  await main();
} catch (error) {
  crashed = String(error?.stack ?? error);
  console.log(`FAIL crash: ${crashed.split("\n")[0]}`);
}

for (const undo of cleanups.splice(0).reverse()) {
  try {
    await undo();
  } catch {
    /* best effort */
  }
}

const failures = results.filter((r) => r.status === "FAIL");
const report = {
  unit: "CX-05",
  startedAt,
  finishedAt: new Date().toISOString(),
  url: BASE,
  slot: SLOT,
  counts: { ok: results.filter((r) => r.status === "ok").length, FAIL: failures.length, skip: results.filter((r) => r.status === "skip").length },
  notBuilt: [],
  crashed,
  results,
  failures: failures.map((r) => `${r.id} ${r.name} | ${r.detail} | owner: ${r.owner}`),
};

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, `${JSON.stringify(report, null, 2)}\n`);
console.log(`behaviour-smoke: ${report.counts.ok} ok, ${report.counts.FAIL} FAIL, ${report.counts.skip} skip -> ${OUT}`);
process.exit(failures.length || crashed ? 1 : 0);
