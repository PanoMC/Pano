package com.panomc.platform.gate

import com.panomc.platform.ApiLevel
import com.panomc.platform.AppConstants
import com.panomc.platform.AppConstants.DEFAULT_THEME_ID
import com.panomc.platform.InstallManager
import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.PanoManifestPluginDescriptorFinder
import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PluginManager
import com.panomc.platform.model.Error as DomainError
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.type.panel.IncompatibleResourcesNotification
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.util.HashUtil
import com.panomc.platform.update.CompatibilityQueryUnsupported
import com.panomc.platform.update.CompatibilityStore
import com.panomc.platform.update.CompatibleHit
import com.panomc.platform.update.CompatibleQuery
import com.panomc.platform.update.PanoCompatibilityStore
import com.panomc.platform.update.QueriedResource
import com.panomc.platform.update.ReleaseApiLevel
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.util.zip.ZipFile

// Gate 0 of the compatibility gate (doc 04 section 7): the reconcile at boot, the report the panel shows and the
// small interfaces it is built from. Everything that touches Pano's own managers lives in the Pano* classes at the
// bottom; the reconciler itself only knows the interfaces, which is what the tests replace.

/**
 * One installed plugin or theme as the gate sees it.
 *
 * @property apiLevel the level it declares (0 = nothing declared, a resource built before the cutover).
 * @property running a plugin: `STARTED`; a theme: always true (it is served or not, it has no process of its own).
 * @property disabledByAdmin a plugin the admin switched off (`disabled.txt`): the reconcile leaves it alone.
 */
data class InstalledResource(
    val id: String,
    val type: ResourceType,
    val title: String?,
    val version: String,
    val apiLevel: Int,
    val verdict: Verdict,
    val running: Boolean = true,
    val disabledByAdmin: Boolean = false,
    /** A compatible plugin held back only because a plugin it requires is refused (`pluginId` is the root cause). */
    val heldBy: HeldDependency? = null
)

/** The refused plugin that holds a dependent back: [pluginId] (root cause), its [verdict], and the direct dependency [via]. */
data class HeldDependency(val pluginId: String, val verdict: Verdict, val via: String, val name: String? = null)

/** What a downloaded file says about itself, read before it is installed. */
data class InspectedFile(val id: String, val version: String, val apiLevel: Int)

/** What is installed, and what a downloaded file is. */
interface ResourceCatalog {
    /** Every installed plugin (loaded or held disabled by the gate) and every installed theme. */
    fun installed(): List<InstalledResource>

    /** The id, version and level a plugin jar or theme zip declares; null when [file] is not a resource of [type]. */
    fun inspect(file: File, type: ResourceType): InspectedFile?
}

/** Puts a downloaded file in place through the existing install code. */
fun interface ResourceInstaller {
    /**
     * Installs [file] as a [type] (replacing the installed version, starting a plugin) and returns null on success
     * or a reason. The caller does not trust either: it looks at the catalog afterwards.
     */
    suspend fun install(file: File, type: ResourceType, hash: String?): String?
}

/** Tells the admins that [count] resources stay refused. */
fun interface RefusalNotifier {
    suspend fun refused(count: Int)

    /** [refused] with the ids of the refused resources; the real notifier uses them to not repeat an unread notice. */
    suspend fun refused(count: Int, ids: List<String>) = refused(count)
}

enum class ReconcileOutcome { INSTALLED, REFUSED }

/**
 * What one run did for one resource that was refused when it started.
 *
 * @property version the installed version at that time; [installedVersion] the one the store gave (when installed).
 * @property hasCompatibleUpdate the store knew a compatible version (it may still have failed to install).
 * @property lastError why it stays refused: `STORE_TIMEOUT`, `STORE_UNREACHABLE: ...`, `STORE_QUERY_UNSUPPORTED: HTTP 404` (the store has no compatibility query), `DOWNLOAD_TIMEOUT`,
 *   `DOWNLOAD_FAILED: ...`, `DOWNLOAD_MISMATCH: ...`, `INSTALL_FAILED: ...`; null when the store simply has nothing.
 * @property restartRequired a theme installed while Pano runs is served after the next restart.
 */
data class ReconcileEntry(
    val id: String,
    val type: ResourceType,
    val version: String,
    val apiLevel: Int,
    val verdict: Verdict,
    val outcome: ReconcileOutcome,
    val installedVersion: String? = null,
    val hasCompatibleUpdate: Boolean = false,
    val lastError: String? = null,
    val restartRequired: Boolean = false,
    /** Set on an entry for a plugin that is compatible itself but held back by a refused required dependency. */
    val heldBy: HeldDependency? = null
)

/** The result of the last run, kept in memory only. [ranAt] is null before the first run. */
data class ReconcileReport(
    val ranAt: Long?,
    val entries: List<ReconcileEntry>,
    /** Whether the store answered; null when it was not asked (nothing was refused). */
    val storeReachable: Boolean? = null,
    /** Whether the admins were told about the refused ones of this run. */
    val notified: Boolean = false
) {
    val refused: List<ReconcileEntry> get() = entries.filter { it.outcome == ReconcileOutcome.REFUSED }

    val installed: List<ReconcileEntry> get() = entries.filter { it.outcome == ReconcileOutcome.INSTALLED }

    fun toJson(): JsonObject = JsonObject()
        .put("ranAt", ranAt)
        .put("storeReachable", storeReachable)
        .put(
            "installed",
            JsonArray(installed.map {
                JsonObject()
                    .put("id", it.id)
                    .put("type", it.type.name)
                    .put("fromVersion", it.version)
                    .put("version", it.installedVersion)
                    .put("restartRequired", it.restartRequired)
            })
        )

    companion object {
        val EMPTY = ReconcileReport(null, emptyList())
    }
}

/**
 * Gate 0: replaces plugins and themes the API level gate refused with the newest compatible version of the store,
 * once at boot between `loadPlugins()` and `startPlugins()` (so a replaced plugin is started with the others), and
 * again whenever the panel asks.
 *
 * 1. Collect installed plugins and themes with a non-OK verdict (not the ones an admin disabled). None: return, a
 *    normal boot pays nothing.
 * 2. Ask the [store] for the newest compatible version of each, within [queryBudgetMs]. A failure is logged and the
 *    resources stay refused.
 * 3. Download and install each hit through the [installer]; one failure does not stop the rest. A result is only
 *    believed after a look at the [catalog]: the plugin has to be compatible and running.
 * 4. Keep the [report] in memory. Nothing is ever written as disabled; a refused resource simply stays refused
 *    until a compatible version is installed.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class CompatibilityReconciler internal constructor(
    private val catalog: ResourceCatalog,
    private val store: CompatibilityStore,
    private val installer: ResourceInstaller,
    private val notifier: RefusalNotifier,
    private val downloadFolder: () -> File = { File(AppConstants.TEMP_FOLDER, "compatibility") },
    private val queryBudgetMs: Long = QUERY_BUDGET_MS,
    private val downloadBudgetMs: Long = DOWNLOAD_BUDGET_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether [File] has the SHA-256 the store gave; a test replaces it. */
    private val hashMatches: (File, String) -> Boolean = HashUtil::verifyFileHash
) {
    @Autowired
    constructor(
        pluginManager: PluginManager,
        applicationContext: ApplicationContext,
        store: PanoCompatibilityStore
    ) : this(
        PanoResourceCatalog(pluginManager),
        store,
        PanoResourceInstaller(applicationContext),
        PanoRefusalNotifier(applicationContext)
    )

    companion object {
        /** Doc 04 section 7: the whole store query gets 20 seconds. */
        const val QUERY_BUDGET_MS = 20_000L

        /** One file, over the Pano API; generous, a plugin jar can be tens of megabytes. */
        const val DOWNLOAD_BUDGET_MS = 300_000L
    }

    private val logger = LoggerFactory.getLogger(CompatibilityReconciler::class.java)
    private val mutex = Mutex()

    @Volatile
    private var current: ReconcileReport = ReconcileReport.EMPTY

    @Volatile
    private var runningNow = false

    /** The last run's report (empty before the first run). */
    val report: ReconcileReport get() = current

    /** True while a run is in progress; the panel shows "Checking" and does not offer a second run. */
    val running: Boolean get() = runningNow

    /** The installed plugins and themes as the gate sees them now. */
    fun installed(): List<InstalledResource> = catalog.installed()

    /**
     * Runs gate 0. [atBoot] false (the panel's "Retry") marks a theme it installs as needing a restart, because the
     * theme being served was already chosen at boot.
     */
    suspend fun reconcile(atBoot: Boolean = false): ReconcileReport = mutex.withLock {
        runningNow = true

        try {
            run(atBoot).also { current = it }
        } finally {
            runningNow = false
        }
    }

    /**
     * Tells the admins about the refused ones of the last run, once per run. At boot the database is not up yet, so
     * [reconcile] only notes the report and `Main` calls this when the notification manager can work.
     */
    suspend fun announceRefusals() {
        val report = current
        val count = report.refused.size

        if (count == 0 || report.notified) {
            return
        }

        try {
            notifier.refused(count, report.refused.map { it.id }.sorted())

            // A run that started meanwhile has replaced the report; only mark the one that was announced.
            if (current === report) {
                current = report.copy(notified = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Could not notify the admins about {} refused resources", count, e)
        }
    }

    private suspend fun run(atBoot: Boolean): ReconcileReport {
        val candidates = catalog.installed().filter { it.verdict != Verdict.OK }

        if (candidates.isEmpty()) {
            return ReconcileReport(clock(), heldEntries())
        }

        val actionable = candidates.filter { !it.disabledByAdmin }

        if (actionable.isEmpty()) {
            return ReconcileReport(clock(), heldEntries())
        }

        logger.warn(
            "{} installed resource(s) are outside API level {} to {}: {}. Looking for compatible versions.",
            actionable.size,
            ApiLevel.MIN_SUPPORTED,
            ApiLevel.CURRENT,
            actionable.joinToString { "${it.id} ${it.version} (level ${it.apiLevel})" }
        )

        val hits = HashMap<String, CompatibleHit>()
        var storeError: String? = null

        try {
            val query = CompatibleQuery(
                ApiLevel.CURRENT,
                ApiLevel.MIN_SUPPORTED,
                actionable.map { QueriedResource(it.id, it.type, it.version) }
            )

            withTimeout(queryBudgetMs) { store.newestCompatible(query) }.forEach { hits[it.id] = it }
        } catch (_: TimeoutCancellationException) {
            storeError = "STORE_TIMEOUT"
            logger.warn("The store did not answer within {} ms; the resources stay refused.", queryBudgetMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompatibilityQueryUnsupported) {
            storeError = "STORE_QUERY_UNSUPPORTED: HTTP ${e.status}"
            logger.warn("The store does not offer the compatibility query yet (HTTP {}); the resources stay refused.", e.status)
        } catch (e: Exception) {
            storeError = "STORE_UNREACHABLE: ${e.message ?: e.javaClass.simpleName}"
            logger.warn("The store could not be asked for compatible versions ({}); the resources stay refused.", e.message)
        }

        val entries = actionable.map { resource ->
            val hit = hits[resource.id]?.takeIf { it.type == resource.type }

            if (hit == null) {
                return@map refused(resource, hasUpdate = false, error = storeError)
            }

            replace(resource, hit, atBoot)
        }

        val still = entries.count { it.outcome == ReconcileOutcome.REFUSED }

        if (still > 0) {
            logger.warn("{} resource(s) stay refused: {}", still, entries.filter { it.outcome == ReconcileOutcome.REFUSED }.joinToString { it.id })
        }

        return ReconcileReport(clock(), entries + heldEntries(), storeReachable = storeError == null)
    }

    /**
     * The plugins that are compatible themselves but held back by a refused required dependency, read after the run
     * (a dependency this run replaced has already lifted its dependents). They count as refused: they do not run.
     */
    private fun heldEntries(): List<ReconcileEntry> = catalog.installed()
        .filter { it.heldBy != null && !it.disabledByAdmin }
        .map {
            ReconcileEntry(
                id = it.id,
                type = it.type,
                version = it.version,
                apiLevel = it.apiLevel,
                verdict = it.verdict,
                outcome = ReconcileOutcome.REFUSED,
                heldBy = it.heldBy
            )
        }

    private fun refused(resource: InstalledResource, hasUpdate: Boolean, error: String?) = ReconcileEntry(
        id = resource.id,
        type = resource.type,
        version = resource.version,
        apiLevel = resource.apiLevel,
        verdict = resource.verdict,
        outcome = ReconcileOutcome.REFUSED,
        hasCompatibleUpdate = hasUpdate,
        lastError = error
    )

    private suspend fun replace(resource: InstalledResource, hit: CompatibleHit, atBoot: Boolean): ReconcileEntry {
        var file: File? = null

        try {
            val downloaded = try {
                withTimeout(downloadBudgetMs) { store.download(hit, downloadFolder()) }
            } catch (_: TimeoutCancellationException) {
                return refused(resource, hasUpdate = true, error = "DOWNLOAD_TIMEOUT")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Downloading {} {} failed: {}", resource.id, hit.version, e.message)

                return refused(resource, hasUpdate = true, error = "DOWNLOAD_FAILED: ${e.message ?: e.javaClass.simpleName}")
            }

            file = downloaded.file

            // The store's answer may carry the file's SHA-256; a file that differs is never installed. Without the
            // field nothing changes (the file is still read and judged by what it declares, below).
            val expectedHash = downloaded.hash ?: hit.hash

            if (expectedHash != null && !hashMatches(downloaded.file, expectedHash)) {
                logger.warn("The store's file for {} {} does not match the hash the store gave; it was not installed.", resource.id, hit.version)

                return refused(resource, hasUpdate = true, error = "DOWNLOAD_MISMATCH: the file does not match the hash the store gave")
            }

            // Believe the file only after reading what it declares: the right resource, on a level this Pano runs.
            val inspected = catalog.inspect(downloaded.file, resource.type)
            val mismatch = when {
                inspected == null -> "not a ${resource.type.name.lowercase()} file"
                !inspected.id.equals(resource.id, ignoreCase = true) -> "the file is '${inspected.id}', not '${resource.id}'"
                ApiLevelGate.check(inspected.apiLevel) != Verdict.OK -> "the file declares API level ${inspected.apiLevel}"
                else -> null
            }

            if (mismatch != null) {
                logger.warn("The store's file for {} {} was not installed: {}", resource.id, hit.version, mismatch)

                return refused(resource, hasUpdate = true, error = "DOWNLOAD_MISMATCH: $mismatch")
            }

            val installError = try {
                installer.install(downloaded.file, resource.type, downloaded.hash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }

            // The install code reports its own bookkeeping failing after the plugin is already running (at boot the
            // database is not up). What counts is the state.
            val after = catalog.installed().firstOrNull { it.id == resource.id && it.type == resource.type }

            if (after != null && after.verdict == Verdict.OK && after.running) {
                if (installError != null) {
                    logger.warn("{} {} is installed and running; the install code also reported: {}", resource.id, hit.version, installError)
                }

                logger.info("Installed {} {} for API level {} (was {}).", resource.id, after.version, ApiLevel.CURRENT, resource.version)

                return ReconcileEntry(
                    id = resource.id,
                    type = resource.type,
                    version = resource.version,
                    apiLevel = resource.apiLevel,
                    verdict = resource.verdict,
                    outcome = ReconcileOutcome.INSTALLED,
                    installedVersion = after.version,
                    hasCompatibleUpdate = true,
                    restartRequired = !atBoot && resource.type == ResourceType.THEME
                )
            }

            val reason = installError
                ?: if (after != null && after.verdict == Verdict.OK) "the plugin did not start" else "the file was not put in place"

            logger.warn("Installing {} {} did not work: {}", resource.id, hit.version, reason)

            return refused(resource, hasUpdate = true, error = "INSTALL_FAILED: $reason")
        } finally {
            // The install code moves or deletes the file on success; a leftover is ours to remove.
            file?.takeIf { it.exists() }?.delete()
        }
    }
}

// ---- Pano's own managers behind the interfaces ----------------------------------------------------

/**
 * The installed plugins from the [PluginManager] (every wrapper, including the ones the gate holds disabled) and the
 * installed themes from the `manifest.json` of each theme folder. Themes are read from disk, not from the UI manager,
 * so this works before the UI manager exists (at boot) and costs no bean.
 */
class PanoResourceCatalog(
    private val pluginManager: PluginManager,
    private val themesFolder: () -> File = { File(AppConstants.THEMES_FOLDER_PATH) }
) : ResourceCatalog {
    private val logger = LoggerFactory.getLogger(PanoResourceCatalog::class.java)

    override fun installed(): List<InstalledResource> = plugins() + themes()

    private fun plugins(): List<InstalledResource> {
        val disabled = adminDisabledIds()

        return pluginManager.plugins.map { wrapper ->
            val descriptor = wrapper.descriptor as? PanoPluginDescriptor
            val level = descriptor?.apiLevel ?: 0

            InstalledResource(
                id = wrapper.pluginId,
                type = ResourceType.PLUGIN,
                title = descriptor?.name,
                version = wrapper.descriptor.version ?: "",
                apiLevel = level,
                verdict = ApiLevelGate.check(level),
                running = wrapper.pluginState == PluginState.STARTED,
                disabledByAdmin = wrapper.pluginId in disabled,
                heldBy = pluginManager.heldBy(wrapper.pluginId)?.let { HeldDependency(it.pluginId, it.verdict, it.via, it.name) }
            )
        }
    }

    private fun themes(): List<InstalledResource> {
        val folder = themesFolder()

        return folder.listFiles()
            ?.filter { it.isDirectory && it.name.matches(THEME_FOLDER) && !it.name.equals(DEFAULT_THEME_ID, ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { themeFolder ->
                val manifest = File(themeFolder, "manifest.json")

                if (!manifest.isFile) {
                    return@mapNotNull null
                }

                try {
                    val json = JsonObject(manifest.readText())
                    val id = json.getString("id")?.takeIf { it.isNotBlank() } ?: themeFolder.name
                    val level = ReleaseApiLevel.level(json.getValue("apiLevel")) ?: 0

                    if (id.equals(DEFAULT_THEME_ID, ignoreCase = true)) {
                        return@mapNotNull null
                    }

                    InstalledResource(
                        id = id,
                        type = ResourceType.THEME,
                        title = json.getString("title"),
                        version = json.getString("version") ?: "",
                        apiLevel = level,
                        verdict = ApiLevelGate.check(level)
                    )
                } catch (e: Exception) {
                    logger.debug("Theme folder {} has no readable manifest: {}", themeFolder.name, e.message)

                    null
                }
            } ?: emptyList()
    }

    /** The ids in PF4J's `disabled.txt` of the plugins folder: the plugins an admin switched off. */
    private fun adminDisabledIds(): Set<String> = try {
        val file = pluginManager.pluginsRoot.resolve("disabled.txt").toFile()

        if (file.isFile) {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        } else {
            emptySet()
        }
    } catch (_: Exception) {
        emptySet()
    }

    override fun inspect(file: File, type: ResourceType): InspectedFile? = try {
        when (type) {
            ResourceType.PLUGIN -> {
                val descriptor = PanoManifestPluginDescriptorFinder().find(file.toPath()) as PanoPluginDescriptor

                InspectedFile(descriptor.pluginId, descriptor.version ?: "", descriptor.apiLevel)
            }

            ResourceType.THEME -> ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                val json = JsonObject(zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) })

                InspectedFile(
                    json.getString("id") ?: return null,
                    json.getString("version") ?: "",
                    ReleaseApiLevel.level(json.getValue("apiLevel")) ?: 0
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val THEME_FOLDER = Regex("^[a-zA-Z0-9-]+$")
    }
}

/**
 * The existing resource-install code ([InstallManager.installResource]) without a user and without a hash row: the
 * reconcile runs at boot, with no request and, at that point, no database. The beans are fetched when the first
 * install happens, so a boot that installs nothing builds none of them.
 */
class PanoResourceInstaller(private val applicationContext: ApplicationContext) : ResourceInstaller {
    override suspend fun install(file: File, type: ResourceType, hash: String?): String? {
        val installManager = applicationContext.getBean(InstallManager::class.java)

        if (type == ResourceType.THEME) {
            // The install code looks the theme up in the UI manager's list, which is empty until the UI manager
            // starts: without this an update would be copied over the old folder instead of replacing it.
            applicationContext.getBean(com.panomc.platform.UIManager::class.java).reloadInstalledThemes()
        }

        var failure: String? = null

        installManager.installResource(null, hash, null, file, type) { result ->
            if (result is DomainError) {
                failure = listOfNotNull(result.code, result.message).joinToString(": ")
            }
        }

        return failure
    }
}

/**
 * One [IncompatibleResourcesNotification] to every holder of the manage-addons permission, through the same
 * manager the platform update notification uses. An older unread one is marked read first, so the bell never shows
 * two of them.
 */
class PanoRefusalNotifier(private val applicationContext: ApplicationContext) : RefusalNotifier {
    override suspend fun refused(count: Int) = refused(count, emptyList())

    override suspend fun refused(count: Int, ids: List<String>) {
        val databaseManager = applicationContext.getBean(DatabaseManager::class.java)
        val notificationManager = applicationContext.getBean(NotificationManager::class.java)
        val sqlClient = databaseManager.getSqlClient()

        val type = IncompatibleResourcesNotification().getName()
        val unread = databaseManager.panelNotificationDao.getNotReadByType(type, sqlClient)

        // The same resources are still refused and the admins have not read the notice yet: no second row.
        if (alreadyAnnounced(unread.map { it.details }, count, ids)) {
            return
        }

        unread.forEach {
            databaseManager.panelNotificationDao.markReadById(it.id, sqlClient)
        }

        notificationManager.sendNotificationToAllWithPermission(
            notificationType = IncompatibleResourcesNotification(count, ids),
            permission = ManageAddonsPermission(),
            sqlClient = sqlClient
        )
    }

    companion object {
        /** Whether one of the [unread] notice details already names exactly [count] and [ids]. */
        internal fun alreadyAnnounced(unread: List<JsonObject>, count: Int, ids: List<String>): Boolean = unread.any { details ->
            details.getInteger("count") == count &&
                details.getJsonArray("resources")?.map { it.toString() }?.sorted() == ids.sorted()
        }
    }
}
