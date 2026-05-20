# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Pano is the backend platform powering Minecraft server websites. It is a Kotlin + Vert.x 5
application (HTTP server, coroutine handlers, MySQL/MariaDB) with a PF4J plugin system and a
Spring `AnnotationConfigApplicationContext` for dependency injection. The front-end UIs
(`panel-ui`, `setup-ui`, themes) and most user-facing features (announcements, bans, FAQ, etc.)
live in **separate repositories** — Pano downloads/reverse-proxies them at runtime. Licensed GPLv3.

## Build / run / test

Gradle (Kotlin DSL), multi-module: `:Pano` (the platform), `:Updater` (self-update helper jar),
and `:plugins:*` (each plugin under `plugins/` is auto-included as a subproject by
`settings.gradle.kts`). JDK notes that bite in practice:
- **Run Gradle itself on JDK 17+** (CI uses 17). The system default here is JDK 11, which fails
  immediately with *"Gradle requires JVM 17 or later"* — set `JAVA_HOME` to a 17/21 JDK first.
- Compile **toolchain targets Java 11**; the **test task forces JDK 21** (see `Pano/build.gradle.kts`).
  Gradle auto-detects installed JDKs for toolchains, so both 11 and 21 must be installed.
- The produced jar runs on **JRE 11+**.

```bash
# Run for local development (root `run` cascades to :Pano:run; :Updater:run is disabled)
./gradlew run
./gradlew run -Pnogui          # headless
./gradlew run -Pdev            # --dev flag
./gradlew run -Pdemo           # --demo: mutating endpoints return DisabledForDemo

# Before the first run, fetch the front-end bundles into resources/UIFiles:
./gradlew downloadUIReleases   # versions pinned in ui-releases.yml; GITHUB_TOKEN/TOKEN_GITHUB
                               # is optional (only used to dodge GitHub's anonymous rate limit)
./gradlew downloadMailTemplates                     # fetches + unzips email templates

# Build a release fat jar (build/libs/Pano-<version>.jar via shadowJar)
./gradlew build                # 'release' style; runs generateLicenses (hits Maven Central)
./gradlew buildDev             # dev build; sets MODE=DEVELOPMENT in the manifest

# Tests (JUnit 5 / Jupiter)
./gradlew :Pano:test
./gradlew :Pano:test --tests "com.panomc.platform.util.HashUtilTest"
./gradlew :Pano:test --tests "*HashUtilTest.computeStableFileFingerprint*"

# Run the jar (Docker brings up MariaDB only; see docker-compose.yml)
java -jar build/libs/Pano-<version>.jar [-nogui] [-bg]
```

Releases are automated by **semantic-release** on push to `alpha`/`beta`/`main` (prerelease
channels). Commit messages must be **conventional commits** (`feat:`, `fix:`, `chore:`, …) —
they drive both versioning and the generated changelog.

## Architecture

### Boot sequence
`com.panomc.platform.Main` is a Vert.x `CoroutineVerticle` annotated `@Boot`. `main()` handles
CLI flags (`-nogui`, `-bg` background respawn, `--dev`, `--demo`) and deploys the verticle.
`Main.init()` is the canonical startup order: DI → config → setup check → MariaDB → plugin
manager + plugins → (if installed) database/i18n/server/update/license managers → UI manager →
routes → ACME → console commands. Global state (`VERSION`, `STAGE`, `ENVIRONMENT`, `IS_DEMO`,
`applicationContext`) lives in `Main.Companion`.

### Dependency injection
Spring component-scans `com.panomc.platform` (`SpringConfig`). Singletons like `Vertx`, `Gson`,
the Vert.x `Router`, `WebClient`, and `HttpClient` are declared as `@Bean`s in `SpringConfig`.
Get beans anywhere via `Main.applicationContext.getBean(...)`. Note the heavily-tuned
`provideHttpClient` (keep-alive/pool sizing) exists specifically to make the reverse-proxy to the
Bun/SvelteKit UIs reliable — read its comments before changing it.

### Routing — annotation-driven endpoints
Add an HTTP endpoint by creating a `@Endpoint` class that extends one of the `Route` subclasses:
- `Api` — JSON API base; coroutine `handle()` returns a `Result`. Calls `checkSetup()` +
  `checkDemoMode()` in `onBeforeHandle`.
- `LoggedInApi`, `PanelApi`, `SetupApi` — auth/permission-gated variants.
- `Template` — server-rendered (Handlebars) pages.

`RouterProvider.initialize()` collects every `@Endpoint` bean (host + each plugin's bean context)
and registers Vert.x routes. Each route declares `paths: List<Path>` (URL + `RouteType`
GET/POST/…), an optional `getValidationHandler()` (vertx-web-validation JSON schema), and CORS/body
handlers. Return success with `Successful(map)`; signal errors by **throwing** `Error` subclasses
from `com.panomc.platform.error` (e.g. `BadRequest`, `InstallationRequired`) — the base `Api`
failure handler serializes them. `/panel/api/*` is rerouted to `/api/*`; `/api/*` is rate-limited.

### Database
Vert.x MySQL client against MariaDB. `DatabaseManager` holds every DAO (constructor-injected) and
owns the shared `SqlClient` pool. To add a table:
- Create a `@Dao` class extending `Dao<YourEntity>` (entity extends `DBEntity`). Entities are
  (de)serialized from row JSON via Gson; **field names map to `snake_case` columns** and the table
  name is the entity class name snake-cased + the configured prefix. Use `@Ignore` to skip a field.
  DAOs are auto-discovered via `getBeansWithAnnotation(Dao::class)`.
- Schema changes go through `@Migration` classes extending `DatabaseMigration(from, to, info)`,
  applied in order and tracked in the `scheme_version` table. Bump the latest `from→to`.

### Plugins (PF4J)
`PanoPlugin` is the plugin base (`onCreate/onEnable/onStart/onStop/onDisable/onUninstall`). Each
plugin under `plugins/` is its **own Git repo and Gradle build** with a Svelte UI (built with Bun
via rollup, zipped into resources). Key points:
- Manifest attributes (`pluginId`, `pluginClass`, `pano-version`, dependencies, license…) come from
  the plugin's `gradle.properties` and are written by its `shadowJar` task.
- Each plugin gets its **own Spring bean context** (child of the global context), so a plugin's
  `@Endpoint`/`@Dao`/`@EventListener` beans are scanned independently; routes are added/removed live
  on plugin load/unload (`RouterProvider` implements `PluginLifecycleListener`).
- The `bootstrap` property selects what a plugin compiles against: built from the **Pano root**,
  the root `gradle.properties` `bootstrap=true` applies, so plugins `compileOnly project(":Pano")`
  (develop platform + plugin together); built **standalone** in its own repo, a plugin's own
  `gradle.properties` has no `bootstrap` so it defaults to `false` → the published
  `com.github.panomc:pano` artifact. (A plugin's own `gradle.properties` — `pluginId`, pinned
  `pf4jVersion`/`vertxVersion`, etc. — overrides the root's values for that subproject.)
- Plain `./gradlew run` does **not** build plugins. Run `./gradlew :plugins:build` (or root `build`)
  to compile each plugin and copy its jar into `build/plugins`, which the dev `run` task loads via
  the `pf4j.pluginsDir` system property. Plugin DB tables go through `PluginDatabaseManager`
  (per-plugin migrations + orphan cleanup), not `DatabaseManager`.
- Premium plugins/themes use the **license system** (`com.panomc.platform.license`): host fetches an
  RS256 JWT from panomc.com; the plugin verifies it with an embedded public key. See `PanoPlugin`'s
  `getLicenseManager()`/`verifyLicense()` docs.

### Events
`PluginEventManager` dispatches to `PanoEventListener` interfaces (`RouterEventListener`,
`AuthEventListener`, `PlayerEventListener`, `SetupEventListener`, …). Implement one and annotate
with `@EventListener` (which is `@Conditional(CheckClassesExists)` so it only registers when its
referenced classes are present). Server-side events (`@Event`) live under `server/event`.

### Front-end UIs & themes
There are **no front-end sources in this repo**. `UIManager` reverse-proxies requests to the
SvelteKit apps (`panel-ui`, `setup-ui`, active theme) running as Bun/adapter-node upstreams.
Production bundles are pinned in `ui-releases.yml` and pulled by `downloadUIReleases` into
`Pano/src/main/resources/UIFiles`. `scripts/aggregate-ui-changelog.js` builds the UI changelog
section appended to GitHub releases.

### Other subsystems
- **Config**: HOCON (`config.conf`) via Typesafe Config → `ConfigManager` / `PanoConfig`; versioned
  `ConfigMigration` classes under `config/migration`. Custom `HoconWriter` preserves comments.
- **Setup**: `SetupManager.isSetupDone()` gates DB-dependent init and most APIs (`checkSetup()`).
- **Auth/tokens**: JWT cookies (`AppConstants`), CSRF tokens, Argon2/bcrypt password hashing;
  permissions are `@PermissionDefinition` classes registered by `PermissionRegistry`.
- **SSL**: `AcmeManager` (Let's Encrypt/ACME via acme4j) or manual certs; HTTP→HTTPS redirect.
- **Console**: JLine REPL + optional Swing GUI; commands are `CommandExecutor` beans registered by
  `CommandManager` (`command/impl`). `-bg` respawns detached with no terminal.
- **Updater**: `:Updater` builds a tiny dependency-free jar, zipped and embedded into Pano's
  resources; `UpdateManager` drives self-update.
