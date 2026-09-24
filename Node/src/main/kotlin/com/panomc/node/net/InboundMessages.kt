package com.panomc.node.net

/**
 * The messages Pano pushes, in the shapes Gson reads them back into.
 *
 * Everything is nullable with a default for the same reason Pano's own request classes are: Gson
 * allocates these without running a constructor, so an absent field and an explicit JSON null both
 * land here regardless of what the Kotlin type promises. Every handler therefore validates rather
 * than trusts.
 */
data class InstallServerMessage(
    val serverUuid: String? = null,
    val taskId: String? = null,
    val spec: InstallServerSpec? = null
)

data class InstallServerSpec(
    val name: String? = null,
    val software: String? = null,
    val version: String? = null,
    val javaMajor: Int? = null,
    val memoryMb: Int? = null,
    val jvmArgs: List<String>? = null,
    val port: Int? = null,
    val acceptEula: Boolean? = null,
    val properties: Map<String, String>? = null,
    val downloadUrl: String? = null,
    val installerUrl: String? = null,
    /** MD5 of the download, when the upstream published one; verified before the jar is used. */
    val md5: String? = null,
    /** Set instead of [downloadUrl] when this server's jar has to be compiled here. */
    val build: BuildSpec? = null,
    val panoPlugin: PanoPluginSpec? = null,
    /**
     * What a reinstall carries into the fresh directory (SM-66, §2.4.31). Absent is what a
     * reinstall always did: the `world*` folders and nothing else. Ignored on a fresh install.
     */
    val keep: ReinstallKeep? = null
)

/**
 * `spec.keep` of a reinstall. [worlds] = every top-level directory with a `level.dat` plus
 * `world*`; [plugins] = the old family's `plugins/` (or `mods/` + `config/`), without the Pano
 * plugin's own jar; [configs] = the config files of `ReinstallCarryOver`, only those that exist.
 * A missing [worlds] counts as true, a missing [plugins] or [configs] as false.
 */
data class ReinstallKeep(
    val worlds: Boolean? = null,
    val plugins: Boolean? = null,
    val configs: Boolean? = null
)

/**
 * An install that compiles its own jar instead of downloading one (Spigot via BuildTools).
 *
 * SpigotMC may not redistribute the server jar, so there is no URL to hand this daemon: the only
 * lawful install is to run their tool on this host. Which revision, which tool and which Java are
 * all decided by Pano, exactly like a download URL is, so the node still never picks an address
 * for itself.
 */
data class BuildSpec(
    /** Only `buildtools` exists; anything else fails the task rather than being guessed at. */
    val tool: String? = null,
    /** What the tool is given as its revision, which for BuildTools is the Minecraft version. */
    val rev: String? = null,
    val toolUrl: String? = null,
    /** The Java the *build* needs, which BuildTools is stricter about than the finished server. */
    val javaMajor: Int? = null
) {
    companion object {
        const val BUILDTOOLS = "buildtools"
    }
}

/**
 * The Pano plugin to put inside a managed server, with the credentials it should use.
 *
 * Absent means this server gets none: vanilla, Forge and NeoForge have no Pano plugin, and a
 * release Pano could not reach is reported on the task rather than failing the install.
 */
data class PanoPluginSpec(
    val jarUrl: String? = null,
    /** `plugins` or, for mod loaders, `mods`. */
    val targetDir: String? = null,
    /** Where that platform's plugin reads config.conf, relative to the server directory. */
    val configPath: String? = null,
    val config: PanoPluginConfig? = null,
    /**
     * Mods the plugin cannot start without (Fabric API for the Fabric build), put next to it
     * first. One that is already installed is skipped; one that is missing and has no
     * [PanoPluginDependency.downloadUrl] keeps the plugin out, so the server still starts.
     */
    val dependencies: List<PanoPluginDependency>? = null
)

/** One entry of [PanoPluginSpec.dependencies]. */
data class PanoPluginDependency(
    /** The mod id it provides (`fabric-api`), matched against `fabric.mod.json` / `quilt.mod.json`. */
    val modId: String? = null,
    val name: String? = null,
    val downloadUrl: String? = null,
    val filename: String? = null,
    val sha512: String? = null,
    val sha1: String? = null
)

data class PanoPluginConfig(
    val host: String? = null,
    val port: Int? = null,
    val ssl: Boolean? = null,
    val token: String? = null,
    val encryptionKey: String? = null
)

/**
 * Take an existing server and make it a managed one (`IMPORT_SERVER`).
 *
 * [mode] decides which of [folderPath], [ticket] and [downloadUrl] carries the source; the others
 * are null. [spec] is deliberately thinner than an install's: an import has no software or version
 * to be told, because working those out from what arrives is the whole job.
 */
data class ImportServerMessage(
    val serverUuid: String? = null,
    val taskId: String? = null,
    /** `FOLDER`, `UPLOAD`, `MODPACK` or `IN_PLACE`. */
    val mode: String? = null,
    /**
     * An absolute path on this host, for `FOLDER` (copied from) and `IN_PLACE` (adopted where it
     * is: this very directory becomes the server's, and is never deleted by the node).
     */
    val folderPath: String? = null,
    /** A transfer ticket holding an uploaded archive, for `UPLOAD`. */
    val ticket: String? = null,
    /** Where the `.mrpack` is, resolved by Pano, for `MODPACK`. */
    val downloadUrl: String? = null,
    val filename: String? = null,
    val spec: ImportServerSpec? = null
)

data class ImportServerSpec(
    val name: String? = null,
    val memoryMb: Int? = null,
    val port: Int? = null,
    val javaMajor: Int? = null,
    val jvmArgs: List<String>? = null,
    val acceptEula: Boolean? = null,
    /**
     * Whether the adopted server starts with its node, for `IN_PLACE` (SM-74): Pano's row decides.
     * Null from an older Pano, and for every copy mode, which keeps what they always did.
     */
    val autoStart: Boolean? = null
)

/**
 * Put the Pano plugin into a server that already exists (`INSTALL_PANO_PLUGIN`).
 *
 * A fresh install carries its plugin inside `INSTALL_SERVER`, because Pano picked the software and
 * therefore knew which plugin build and config path to send. An import cannot: the software is
 * only known once this node has inspected what it unpacked, so the plugin is a second step Pano
 * takes after `IMPORT_RESULT` told it what it is dealing with.
 */
data class InstallPanoPluginMessage(
    val serverUuid: String? = null,
    val taskId: String? = null,
    val spec: PanoPluginSpec? = null,
    /**
     * Whether the server is started once this install has ended, done or failed.
     *
     * Pano sets it on the install that follows an import, in place of the START it used to send
     * when the import finished: that start raced this very download, and a server that booted
     * first found a half-written jar and ran without the plugin. Null from an older Pano, which
     * still sends its own START, and on every install that is not an import's.
     */
    val startAfter: Boolean? = null
)

data class PowerMessage(
    val serverUuid: String? = null,
    val action: String? = null,
    val requestId: String? = null,
    val issuedBy: String? = null
)

data class SendCommandMessage(
    val serverUuid: String? = null,
    val command: String? = null,
    val issuedBy: String? = null
)

data class ConsoleStreamMessage(
    val serverUuid: String? = null,
    val enabled: Boolean? = null
)

/**
 * Pano wants up to [limit] lines of this server's history, answered on [eventId].
 *
 * [skip] is how many of the newest lines to step over first, which is what the panel's "load
 * older" button counts: without it every page would be the same page.
 */
/**
 * `SET_NODE_METRICS_INTERVAL`: report the host's `NODE_METRICS` every [intervalMs] for a while
 * (SM-65, §2.4.30). The same lease as a server's: ninety seconds without a renewal and the node is
 * back to every ten seconds.
 */
data class SetNodeMetricsIntervalMessage(
    val intervalMs: Long? = null
)

/** `SET_METRICS_INTERVAL`: sample [serverUuid]'s process every [intervalMs] for a while (§2.4.23 A). */
data class SetMetricsIntervalMessage(
    val serverUuid: String? = null,
    val intervalMs: Long? = null
)

data class ConsoleHistoryMessage(
    val eventId: String? = null,
    val serverUuid: String? = null,
    val limit: Int? = null,
    val skip: Int? = null,
    /**
     * Find text from the panel's console (§2.4.20). Blank or missing is the plain page; anything
     * else searches the window "Load older" could reach, and [skip]/[limit] count matches.
     */
    val query: String? = null
)

/**
 * `CONSOLE_SEARCH`: one page of a search through all of [serverUuid]'s log files, answered on
 * [eventId].
 *
 * [cursor] is the one the previous page ended on, or null to start at the newest file; [limit] is
 * how many matches this page may hold and [budgetMs] how long it may spend reading. All three are
 * clamped by [com.panomc.node.console.ServerLogSearch], whatever Pano sent.
 */
data class ConsoleSearchMessage(
    val eventId: String? = null,
    val serverUuid: String? = null,
    val query: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
    val budgetMs: Int? = null
)

data class UpdateStartupMessage(
    val serverUuid: String? = null,
    val spec: StartupSpec? = null
)

data class StartupSpec(
    val javaMajor: Int? = null,
    val memoryMb: Int? = null,
    val jvmArgs: List<String>? = null,
    val port: Int? = null,
    val autoStart: Boolean? = null,
    val crashRestart: Boolean? = null,
    val properties: Map<String, String>? = null
)

data class DeleteServerMessage(
    val serverUuid: String? = null,
    val taskId: String? = null
)

/**
 * Pano deleted this node and asks it to remove itself from the host (SM-64, §2.4.29 B); reported as
 * a `NODE_UNINSTALL` task under [taskId]. Only sent to nodes that announced protocol 4.
 */
data class NodeUninstallMessage(
    val taskId: String? = null
)

data class SelfUpdateMessage(
    val version: String? = null,
    val url: String? = null,
    val sha256: String? = null
)

/**
 * Every `FILE_*` request in one shape.
 *
 * A class per operation would be eight classes differing by one field each, and Gson reads an
 * absent field as null regardless — so the operation is the message name and the fields it does
 * not use simply stay null.
 */
data class FileRequestMessage(
    val eventId: String? = null,
    val serverUuid: String? = null,
    val path: String? = null,
    val paths: List<String>? = null,
    val from: String? = null,
    val to: String? = null,
    val target: String? = null,
    val content: String? = null,
    val maxBytes: Int? = null,
    val mode: String? = null,
    /** `FILE_HASHES` only: the file names inside [path] to hash. */
    val names: List<String>? = null
)

/**
 * Pano wants this file streamed to it under [ticket].
 *
 * With [paths] set it wants several of them instead, as one zip built on the fly: [path] is then
 * the directory the entry names are relative to, and every entry of [paths] is a full
 * server-relative path to a file or directory inside it.
 */
data class TransferPullMessage(
    val eventId: String? = null,
    val ticket: String? = null,
    val serverUuid: String? = null,
    val path: String? = null,
    val paths: List<String>? = null
)

/** Pano has an uploaded file waiting under [ticket]; fetch it and write it to [path]. */
data class TransferPushMessage(
    val eventId: String? = null,
    val ticket: String? = null,
    val serverUuid: String? = null,
    val path: String? = null,
    val size: Long? = null
)

/**
 * Take a backup of this server under [backupId], reporting progress as [taskId].
 *
 * [mode] (`FULL` / `SNAPSHOT`), [scope] (`ALL` / `WORLDS` / `CUSTOM`) and [include] (CUSTOM only)
 * are backups v2; absent, they mean a FULL archive of everything, which is what a Pano that does
 * not send them expects. [exclude], when present and non-empty, is the whole exclude list.
 */
data class BackupCreateMessage(
    val serverUuid: String? = null,
    val taskId: String? = null,
    val backupId: String? = null,
    val name: String? = null,
    val exclude: List<String>? = null,
    val mode: String? = null,
    val scope: String? = null,
    val include: List<String>? = null
)

/** What backups this node holds for a server. A request; the reply carries them. */
data class BackupListMessage(
    val eventId: String? = null,
    val serverUuid: String? = null
)

/** Put a backup back over a stopped server. */
data class BackupRestoreMessage(
    val serverUuid: String? = null,
    val backupId: String? = null,
    val taskId: String? = null
)

/** Remove one backup from this node. A request, so retention can tell whether it worked. */
data class BackupDeleteMessage(
    val eventId: String? = null,
    val serverUuid: String? = null,
    val backupId: String? = null
)

/**
 * Put one plugin or mod jar into a server's `plugins` (or `mods`) directory.
 *
 * Pano resolved the download from Modrinth, Hangar or CurseForge and passes on whatever checksum
 * that source published; the node re-checks it, because Pano never sees the bytes. [replaceFilename]
 * is the jar an update supersedes, deleted only once the new one is safely in place.
 */
data class InstallPluginMessage(
    val serverUuid: String? = null,
    val taskId: String? = null,
    val downloadUrl: String? = null,
    val filename: String? = null,
    val targetDir: String? = null,
    val sha512: String? = null,
    val sha1: String? = null,
    val sha256: String? = null,
    val replaceFilename: String? = null
)

/**
 * The complete schedule set for one server, replacing whatever this node was holding.
 *
 * Always the whole set. Pano sends it on every change and again after every `NODE_HELLO`, so the
 * node never has to reconcile anything: what arrives is the truth, and what it had is discarded.
 */
data class SyncSchedulesMessage(
    val serverUuid: String? = null,
    val schedules: List<SyncScheduleEntry>? = null
)

data class SyncScheduleEntry(
    val uuid: String? = null,
    val name: String? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val enabled: Boolean? = null,
    val warnMinutes: Int? = null,
    val tasks: List<SyncScheduleTaskEntry>? = null
)

data class SyncScheduleTaskEntry(
    val kind: String? = null,
    val payload: Map<String, Any?>? = null
)

/**
 * What is sitting in one server's `plugins` (or `mods`) directory (`PLUGIN_SCAN`).
 *
 * A request, so [eventId] is what the reply is paired with, exactly as for a `FILE_LIST`.
 */
data class PluginScanMessage(
    val eventId: String? = null,
    val serverUuid: String? = null
)

/**
 * Turn one jar off or on by renaming it (`PLUGIN_TOGGLE`).
 *
 * [file] is a single name inside the plugins directory and is never a path; it may be given with
 * or without the `.disabled` suffix, because the panel lists both and either is the same jar.
 */
data class PluginToggleMessage(
    val eventId: String? = null,
    val serverUuid: String? = null,
    val file: String? = null,
    val enabled: Boolean? = null
)

/**
 * Whether the Pano plugin inside a server is connected right now (`SERVER_PLUGIN_STATE`).
 *
 * The node cannot work this out for itself -- the plugin's socket terminates at Pano -- and it
 * only needs it for one decision: a server whose plugin is connected already reports its players,
 * so pinging it would be a second, worse answer to a question already answered.
 */
data class ServerPluginStateMessage(
    val serverUuid: String? = null,
    val connected: Boolean? = null
)

/** `JAVA_CATALOG`: what Java this node has and could download (SM-63). A request. */
data class JavaCatalogMessage(
    val eventId: String? = null
)

/** `JAVA_INSTALL`: install, or update to the newest build of, Java [major] (SM-63). */
data class JavaInstallMessage(
    val taskId: String? = null,
    val major: Int? = null
)

/**
 * `JAVA_REMOVE`: remove the managed Java [major] runtime(s), or only [version] of it when given
 * (SM-63). Never touches a runtime the node did not install.
 */
data class JavaRemoveMessage(
    val taskId: String? = null,
    val major: Int? = null,
    val version: String? = null
)
