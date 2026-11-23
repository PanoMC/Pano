package com.panomc.platform

import com.panomc.platform.SpringConfig.Companion.pluginEventManager
import com.panomc.platform.SpringConfig.Companion.pluginUiManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import com.panomc.platform.error.PluginApiLevelUnsupported
import com.panomc.platform.gate.ApiLevelGate
import com.panomc.platform.gate.Verdict
import com.panomc.platform.license.LicenseManager
import com.panomc.platform.license.findLicenseRequiredInCauseChain
import kotlinx.coroutines.runBlocking
import org.pf4j.*
import org.springframework.beans.BeansException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

@Component
class PluginManager(importPaths: List<Path> = listOf(Paths.get(System.getProperty("pf4j.pluginsDir", "./plugins")))) :
    DefaultPluginManager(importPaths) {
    companion object {
        internal val pluginGlobalBeanContext by lazy {
            val pluginGlobalBeanContext = AnnotationConfigApplicationContext()

            pluginGlobalBeanContext.setAllowBeanDefinitionOverriding(true)

            pluginGlobalBeanContext.beanFactory.registerSingleton(SpringConfig.vertx.javaClass.name, SpringConfig.vertx)

            pluginGlobalBeanContext.refresh()

            pluginGlobalBeanContext
        }

        internal val lifecycleListeners = mutableSetOf<PluginLifecycleListener>()
    }

    private val log = LoggerFactory.getLogger(PluginManager::class.java)

    /**
     * Plugins the API level gate refused (doc 04 section 7, gate 1), by plugin id. A refusal is in memory only:
     * PF4J holds a plugin that [isPluginValid] rejects as `DISABLED` without writing `disabled.txt`, so the plugin
     * starts by itself once a compatible jar is installed (unload + load builds a new wrapper and a new verdict).
     * Read by the compatibility surface; an entry is cleared when its plugin is unloaded.
     */
    val incompatible = ConcurrentHashMap<String, Verdict>()

    /**
     * PF4J asks this when it wraps a plugin (the plugin is then held `DISABLED` in memory), in [enablePlugin] and,
     * through it, in [startPlugin] (a disabled plugin is enabled first). [startPlugins] skips disabled plugins.
     * Nothing here touches the status provider.
     */
    override fun isPluginValid(pluginWrapper: PluginWrapper): Boolean {
        val valid = super.isPluginValid(pluginWrapper)
        val descriptor = pluginWrapper.descriptor
        val level = (descriptor as? PanoPluginDescriptor)?.apiLevel ?: 0
        val verdict = ApiLevelGate.check(level)

        if (verdict == Verdict.OK) {
            incompatible.remove(descriptor.pluginId)

            // A compatible plugin whose required dependency the gate holds back is held back as well (it would start
            // without the dependency, which is PF4J's default): PF4J keeps it DISABLED in memory, never in disabled.txt.
            val hold = findHold(descriptor)

            if (hold != null) {
                noteHeld(descriptor.pluginId, hold)

                return false
            }

            return valid
        }

        if (incompatible.put(descriptor.pluginId, verdict) != verdict) {
            log.warn(
                "Plugin '{}' needs API level {} ({}); this Pano supports {} to {}. It stays disabled until a compatible version is installed.",
                descriptor.pluginId,
                level,
                verdict,
                ApiLevel.MIN_SUPPORTED,
                ApiLevel.CURRENT
            )
        }

        return false
    }

    /**
     * A plugin held back because a plugin it requires is held back by the gate: [pluginId] is the root cause (the
     * required plugin whose own API level is refused), [verdict] its verdict, [via] the direct dependency of the held
     * plugin (equal to [pluginId] unless the hold passes through a chain of required dependencies).
     *
     * Before this existed PF4J did the following with such a plugin. The dependency is loaded by
     * `AbstractPluginManager.loadPluginFromPath`, where [isPluginValid] rejects it and the wrapper is put in
     * `DISABLED`; the dependent is valid, so `resolvePlugins` moves both to the resolved list (the resolver only looks
     * at ids and version constraints, never at state). `startPlugins` then walks `resolvedPlugins` in dependency
     * order and starts every plugin that is not disabled: the dependency is skipped, the dependent is started without
     * it. `startPlugin(dependent)` is the same: it calls `startPlugin(dependency)` (a no-op that returns `DISABLED`)
     * and then starts the dependent anyway.
     */
    data class HeldBy(val pluginId: String, val verdict: Verdict, val via: String, val name: String? = null)

    /** Plugins held back through a dependency, by plugin id. In memory only; an entry is cleared by a lift or an unload. */
    private val held = ConcurrentHashMap<String, HeldBy>()

    /** Held dependents that were unloaded together with their dependency during an update; reloaded by [liftHolds]. */
    private val pendingReload = ConcurrentHashMap<String, Path>()

    private fun levelOf(pluginId: String): Int =
        (getPlugin(pluginId)?.descriptor as? PanoPluginDescriptor)?.apiLevel ?: 0

    /**
     * The required dependency (directly or through a chain of required dependencies) that keeps [descriptor] from
     * starting, or null. Optional dependencies never hold a plugin back; a dependency that is not loaded is left to
     * PF4J's resolver. A plugin whose own level is refused is not "held": it is refused in its own right.
     */
    private fun findHold(descriptor: PluginDescriptor, seen: MutableSet<String> = mutableSetOf()): HeldBy? {
        if (!seen.add(descriptor.pluginId)) {
            return null
        }

        for (dependency in descriptor.dependencies) {
            if (dependency.isOptional) {
                continue
            }

            val dependencyWrapper = getPlugin(dependency.pluginId) ?: continue
            val dependencyVerdict = ApiLevelGate.check(levelOf(dependency.pluginId))

            if (dependencyVerdict != Verdict.OK) {
                return HeldBy(dependency.pluginId, dependencyVerdict, dependency.pluginId, (dependencyWrapper.descriptor as? PanoPluginDescriptor)?.name)
            }

            val nested = findHold(dependencyWrapper.descriptor, seen)

            if (nested != null) {
                return HeldBy(nested.pluginId, nested.verdict, dependency.pluginId, nested.name)
            }
        }

        return null
    }

    /** Why [pluginId], which is compatible itself, is held back through a required dependency; null when it is not. */
    fun heldBy(pluginId: String): HeldBy? {
        val wrapper = getPlugin(pluginId) ?: return null

        if (ApiLevelGate.check(levelOf(pluginId)) != Verdict.OK) {
            return null
        }

        return findHold(wrapper.descriptor)
    }

    private fun noteHeld(pluginId: String, hold: HeldBy) {
        if (held.put(pluginId, hold) != hold) {
            val through = if (hold.via != hold.pluginId) " (through '${hold.via}')" else ""

            log.warn(
                "Plugin '{}' stays disabled: it requires '{}'{}, which is not compatible with this Pano ({}; this Pano supports API level {} to {}). It starts again once '{}' is updated.",
                pluginId,
                hold.pluginId,
                through,
                "API level ${levelOf(hold.pluginId)}, ${hold.verdict}",
                ApiLevel.MIN_SUPPORTED,
                ApiLevel.CURRENT,
                hold.pluginId
            )
        }
    }

    /** Holds every loaded, not started plugin that needs a held-back dependency: state DISABLED, nothing persisted. */
    private fun holdDependents() {
        for (wrapper in resolvedPlugins.toList()) {
            if (wrapper.pluginState.isStarted || ApiLevelGate.check(levelOf(wrapper.pluginId)) != Verdict.OK) {
                continue
            }

            val hold = findHold(wrapper.descriptor) ?: continue

            noteHeld(wrapper.pluginId, hold)

            if (wrapper.pluginState != PluginState.DISABLED) {
                wrapper.pluginState = PluginState.DISABLED
            }
        }
    }

    /** Boot start: plugins that were held and are free now go back to RESOLVED so the bulk start takes them in order. */
    private fun releaseHeldForBulkStart() {
        for ((pluginId, _) in held.entries.toList()) {
            val wrapper = getPlugin(pluginId)

            if (wrapper == null) {
                held.remove(pluginId)

                continue
            }

            if (findHold(wrapper.descriptor) != null) {
                continue
            }

            held.remove(pluginId)

            if (wrapper.pluginState == PluginState.DISABLED && !isDisabledByAdmin(pluginId) &&
                ApiLevelGate.check(levelOf(pluginId)) == Verdict.OK
            ) {
                wrapper.pluginState = PluginState.RESOLVED
            }
        }
    }

    /**
     * Starts again, in dependency order, the plugins that were held back only because of a dependency that is
     * compatible now (call it after the dependency was installed, enabled and started). Plugins the admin disabled
     * (`disabled.txt`) and plugins whose own dependencies are disabled stay as they are. Held dependents that were
     * unloaded together with the dependency are loaded again first. Never throws; returns the ids it started.
     */
    @Synchronized
    fun liftHolds(): List<String> {
        val started = mutableListOf<String>()

        try {
            // Repeated until nothing more loads: a dependent can only load after what it requires (a carrier after
            // the shipping add-on after the market), whatever order the map hands them out in.
            var progress = true

            while (progress) {
                progress = false

                for ((pluginId, path) in pendingReload.entries.toList()) {
                    if (plugins.containsKey(pluginId) || !java.nio.file.Files.exists(path)) {
                        pendingReload.remove(pluginId)
                        reloadAsHeld.remove(pluginId)

                        continue
                    }

                    val descriptor = runCatching { pluginDescriptorFinder.find(path) }.getOrNull()

                    if (descriptor == null) {
                        pendingReload.remove(pluginId)
                        reloadAsHeld.remove(pluginId)

                        continue
                    }

                    // PF4J refuses to load a plugin whose required dependency is not loaded; wait for it.
                    if (descriptor.dependencies.any { !it.isOptional && !plugins.containsKey(it.pluginId) }) {
                        continue
                    }

                    pendingReload.remove(pluginId)
                    progress = true

                    try {
                        super.loadPlugin(path)

                        if (reloadAsHeld.remove(pluginId)) {
                            held.putIfAbsent(pluginId, HeldBy(pluginId, Verdict.OK, pluginId))
                        }
                    } catch (e: Exception) {
                        log.warn("Plugin '{}' could not be loaded again after the update of its dependency: {}", pluginId, e.message)
                    }
                }
            }

            for (pluginId in restartAfterReload.toList()) {
                val wrapper = getPlugin(pluginId) ?: continue

                restartAfterReload.remove(pluginId)

                if (wrapper.pluginState.isStarted || isDisabledByAdmin(pluginId)) {
                    continue
                }

                // startPlugin parks it when something it requires is off; it then starts together with that.
                if (startPlugin(pluginId) == PluginState.STARTED) {
                    started += pluginId
                }
            }

            for (wrapper in resolvedPlugins.toList()) {
                val pluginId = wrapper.pluginId

                // DISABLED is the held state; a plugin reloaded after its dependency's update comes back RESOLVED.
                if (!held.containsKey(pluginId) || wrapper.pluginState.isStarted || wrapper.pluginState == PluginState.FAILED) {
                    continue
                }

                if (findHold(wrapper.descriptor) != null || ApiLevelGate.check(levelOf(pluginId)) != Verdict.OK) {
                    continue
                }

                if (isDisabledByAdmin(pluginId)) {
                    held.remove(pluginId)

                    continue
                }

                val dependenciesReady = wrapper.descriptor.dependencies.filter { !it.isOptional }.all {
                    val state = getPlugin(it.pluginId)?.pluginState

                    state != null && state != PluginState.DISABLED && state != PluginState.FAILED
                }

                if (!dependenciesReady) {
                    continue
                }

                held.remove(pluginId)

                log.info("Plugin '{}' is no longer held back: its dependencies are compatible. Starting it.", pluginId)

                if (enablePlugin(pluginId) && startPlugin(pluginId) == PluginState.STARTED) {
                    started += pluginId
                } else {
                    log.warn("Plugin '{}' did not start after its dependency was updated", pluginId)
                }
            }
        } catch (e: Exception) {
            log.error("Could not start the plugins that were held back by a dependency", e)
        }

        return started
    }

    // ---- plugins that wait: for a required plugin that is off, or for their license -----------------------------
    //
    // Both are kept in memory and never written to disabled.txt, which stays the admin's own list. A dependent of a
    // plugin the admin switched off waits for it and starts when it is started again; a plugin whose license check
    // failed waits for a license and starts when one arrives (account connected, license refreshed). Before this,
    // both were written to disabled.txt like an admin's choice, so a market add-on stayed off after the market or its
    // license came back and the market never saw it.

    /** Plugin id -> the required plugin it waits for. */
    private val waiting = ConcurrentHashMap<String, String>()

    /** Plugins whose own start failed on their license. */
    private val licenseBlocked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Dependents that were running (or waiting) when they were unloaded with a plugin being updated. */
    private val restartAfterReload: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Dependents that were running when PF4J stopped them together with a plugin they require. An update stops the plugin
     * before it unloads it, so at unload time they look STOPPED; they still have to start again after the update.
     */
    private val stoppedWithDependency: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Dependents that were held back by the gate when they were unloaded with a plugin being updated. */
    private val reloadAsHeld: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The required plugin [pluginId] waits for, or null. */
    fun waitingFor(pluginId: String): String? = waiting[pluginId]

    /** Whether [pluginId] is off because its license check failed (it starts again by [retryLicenseBlocked]). */
    fun isLicenseBlocked(pluginId: String): Boolean = licenseBlocked.contains(pluginId)

    private fun isOff(pluginId: String): Boolean {
        val wrapper = getPlugin(pluginId) ?: return true
        val state = wrapper.pluginState

        return state == PluginState.DISABLED || state == PluginState.FAILED || waiting.containsKey(pluginId) ||
            licenseBlocked.contains(pluginId) || isDisabledByAdmin(pluginId)
    }

    /** The first required dependency of [descriptor] that is off, or null. A dependency that is not loaded is PF4J's. */
    private fun offDependency(descriptor: PluginDescriptor): String? =
        descriptor.dependencies.firstOrNull { !it.isOptional && getPlugin(it.pluginId) != null && isOff(it.pluginId) }
            ?.pluginId

    /** Loaded plugins that require [pluginId] (not optionally). */
    private fun requiredBy(pluginId: String): List<String> =
        plugins.values.filter { wrapper -> wrapper.descriptor.dependencies.any { it.pluginId == pluginId && !it.isOptional } }
            .map { it.pluginId }

    /**
     * Runs the host's disable hooks of a plugin that was enabled (they undo load() / onEnable()); never throws. Does
     * nothing for a plugin whose object was never created or whose hooks already ran in this enable cycle.
     */
    private fun runDisableHooks(pluginId: String) {
        runCatching {
            val plugin = (getPlugin(pluginId) as? PanoPluginWrapper)?.instance ?: return

            if (!plugin.hostLoaded) {
                return
            }

            runBlocking {
                lifecycleListeners.forEach { listener -> listener.onPluginDisable(plugin) }

                plugin.onDisable()
                plugin.unload()
            }
        }.onFailure { log.warn("Disable hooks of plugin '{}' failed: {}", pluginId, it.message) }
    }

    /**
     * Takes [pluginId] off without writing disabled.txt: its dependents first (they wait for it), then it is stopped,
     * its disable hooks run (only when it had been enabled) and it is held DISABLED in memory.
     */
    private fun takeOff(pluginId: String) {
        val wrapper = getPlugin(pluginId) ?: return

        for (dependent in requiredBy(pluginId)) {
            if (!isDisabledByAdmin(dependent) && getPlugin(dependent)?.pluginState != PluginState.DISABLED) {
                park(dependent, pluginId)
            } else if (!isDisabledByAdmin(dependent) && waiting.containsKey(dependent)) {
                waiting[dependent] = pluginId
            }
        }

        val before = wrapper.pluginState

        if (before.isStarted) {
            super.stopPlugin(pluginId)
        }

        if (before == PluginState.STARTED || before == PluginState.STOPPED || before == PluginState.FAILED) {
            runDisableHooks(pluginId)
        }

        wrapper.pluginState = PluginState.DISABLED
    }

    /** [pluginId] waits for [dependencyId]: taken off (see [takeOff]) and started again with it. */
    private fun park(pluginId: String, dependencyId: String) {
        takeOff(pluginId)
        waiting[pluginId] = dependencyId

        log.info("Plugin '{}' waits for '{}', which is off; it starts again together with it.", pluginId, dependencyId)
    }

    /** [pluginId] failed its license: taken off (see [takeOff]) until [retryLicenseBlocked] starts it. */
    fun blockForLicense(pluginId: String) {
        takeOff(pluginId)
        licenseBlocked.add(pluginId)
    }

    /**
     * The admin switches [pluginId] off: its dependents are taken off and wait for it (they are not written to
     * disabled.txt, so they come back with it), then it is stopped and disabled as before.
     */
    fun disableWithDependents(pluginId: String) {
        for (dependent in requiredBy(pluginId)) {
            if (!isDisabledByAdmin(dependent) && getPlugin(dependent)?.pluginState != PluginState.DISABLED) {
                park(dependent, pluginId)
            }
        }

        if (getPlugin(pluginId)?.pluginState?.isStarted == true) {
            stopPlugin(pluginId)
        }

        disablePlugin(pluginId)
        waiting.remove(pluginId)
        licenseBlocked.remove(pluginId)
    }

    /** Starts the plugins that wait for [pluginId], once nothing else they require is off. */
    private fun startWaitingDependents(pluginId: String) {
        for ((dependent, dependency) in waiting.entries.toList()) {
            if (dependency != pluginId) {
                continue
            }

            if (isDisabledByAdmin(dependent) || getPlugin(dependent) == null) {
                waiting.remove(dependent)

                continue
            }

            val stillOff = getPlugin(dependent)?.descriptor?.let { offDependency(it) }

            if (stillOff != null) {
                waiting[dependent] = stillOff

                continue
            }

            waiting.remove(dependent)

            log.info("Plugin '{}' starts again: '{}', which it requires, is running.", dependent, pluginId)

            if (!(enablePlugin(dependent) && startPlugin(dependent) == PluginState.STARTED) && !waiting.containsKey(dependent)) {
                log.warn("Plugin '{}' did not start again after '{}' started", dependent, pluginId)
            }
        }
    }

    /**
     * Tries again the plugins that are off because of their license ([only] or all of them), e.g. after the account
     * was connected or a license was refreshed. A plugin whose license still fails stays off. Never throws.
     */
    fun retryLicenseBlocked(only: String? = null): List<String> {
        val started = mutableListOf<String>()

        for (pluginId in licenseBlocked.toList()) {
            if (only != null && pluginId != only) {
                continue
            }

            if (getPlugin(pluginId) == null || isDisabledByAdmin(pluginId)) {
                licenseBlocked.remove(pluginId)

                continue
            }

            try {
                if (enablePlugin(pluginId) && startPlugin(pluginId) == PluginState.STARTED) {
                    started += pluginId
                }
            } catch (e: Exception) {
                log.warn("Plugin '{}' could not be started again after its license changed: {}", pluginId, e.message)
            }
        }

        return started
    }

    override fun resolvePlugins() {
        super.resolvePlugins()

        holdDependents()
    }

    override fun startPlugins() {
        releaseHeldForBulkStart()
        holdDependents()

        // A plugin that requires one the admin switched off waits for it instead of starting without it.
        for (wrapper in resolvedPlugins.toList()) {
            if (wrapper.pluginState == PluginState.DISABLED || held.containsKey(wrapper.pluginId)) {
                continue
            }

            val dependency = offDependency(wrapper.descriptor) ?: continue

            wrapper.pluginState = PluginState.DISABLED
            waiting[wrapper.pluginId] = dependency
        }

        super.startPlugins()

        // A plugin whose license failed during the bulk start waits for its license; what requires it waits for it.
        for (wrapper in resolvedPlugins.toList()) {
            if (wrapper.pluginState == PluginState.FAILED && wrapper.failedException.findLicenseRequiredInCauseChain() != null) {
                captureLicenseFailureIfAny(wrapper.pluginId)
                blockForLicense(wrapper.pluginId)
            }
        }

        for (wrapper in resolvedPlugins.toList()) {
            if (!wrapper.pluginState.isStarted || held.containsKey(wrapper.pluginId)) {
                continue
            }

            val dependency = offDependency(wrapper.descriptor) ?: continue

            park(wrapper.pluginId, dependency)
        }
    }

    /** The API level verdict of a loaded plugin, read from its descriptor (a missing declaration is level 0). */
    fun verdictOf(pluginId: String): Verdict {
        val wrapper = getPlugin(pluginId) ?: return Verdict.OK

        return ApiLevelGate.check((wrapper.descriptor as? PanoPluginDescriptor)?.apiLevel ?: 0)
    }

    /**
     * Throws [PluginApiLevelUnsupported] (PLUGIN_API_LEVEL_UNSUPPORTED) when [pluginId], or a plugin it requires, is
     * outside the supported API level: the one check every route that switches a plugin on goes through.
     */
    fun requireCompatible(pluginId: String) {
        val wrapper = getPlugin(pluginId) ?: return
        val level = (wrapper.descriptor as? PanoPluginDescriptor)?.apiLevel ?: 0
        val verdict = ApiLevelGate.check(level)

        if (verdict != Verdict.OK) {
            throw PluginApiLevelUnsupported(pluginId, verdict.name, level)
        }

        val hold = findHold(wrapper.descriptor)

        if (hold != null) {
            throw PluginApiLevelUnsupported(pluginId, hold.verdict.name, levelOf(hold.pluginId), dependencyId = hold.pluginId)
        }
    }

    /** Whether the admin switched [pluginId] off (it is in `disabled.txt`), as opposed to the gate holding it. */
    fun isDisabledByAdmin(pluginId: String): Boolean = pluginStatusProvider.isPluginDisabled(pluginId)

    override fun unloadPlugin(pluginId: String, unloadDependents: Boolean, resolveDependencies: Boolean): Boolean {
        if (unloadDependents) {
            rememberHeldDependents(pluginId)

            // PF4J unloads the dependents with the plugin but only stops them: their host hooks (onDisable, unload()
            // and the lifecycle listeners) never ran, so the old objects stayed in the event, database, route and UI
            // registries and pinned their class loaders (a hosted Pano froze after a market update, 2026-11-22).
            for (dependent in dependentsDeepestFirst(pluginId)) {
                takeDownForUnload(dependent)
            }
        }

        takeDownForUnload(pluginId)

        val instance = (getPlugin(pluginId) as? PanoPluginWrapper)?.instance
        val unloaded = super.unloadPlugin(pluginId, unloadDependents, resolveDependencies)

        if (unloaded) {
            incompatible.remove(pluginId)
            held.remove(pluginId)

            for (id in waiting.keys.toList() + licenseBlocked.toList()) {
                if (!plugins.containsKey(id)) {
                    waiting.remove(id)
                    licenseBlocked.remove(id)
                }
            }

            forgetUnloaded(pluginId, instance)
        }

        return unloaded
    }

    /** Every loaded plugin that requires [pluginId], directly or not, each one after all the plugins that require it. */
    private fun dependentsDeepestFirst(pluginId: String): List<String> {
        val ordered = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun visit(id: String) {
            for (dependent in dependencyResolver.getDependents(id)) {
                if (seen.add(dependent)) {
                    visit(dependent)
                    ordered += dependent
                }
            }
        }

        visit(pluginId)

        return ordered
    }

    /** Before PF4J unloads [pluginId]: stops it if it runs and runs its host disable hooks if they did not run yet. */
    private fun takeDownForUnload(pluginId: String) {
        val wrapper = getPlugin(pluginId) as? PanoPluginWrapper ?: return
        val plugin = wrapper.instance ?: return

        if (!plugin.hostLoaded) {
            return
        }

        // onStop runs before the disable hooks, as everywhere else.
        if (wrapper.pluginState.isStarted) {
            runCatching { super.stopPlugin(pluginId) }
                .onFailure { log.warn("Plugin '{}' could not be stopped before it is unloaded: {}", pluginId, it.message) }
        }

        runDisableHooks(pluginId)
    }

    /**
     * After PF4J unloaded [pluginId]: drops whatever the host still holds of it (an object whose hooks failed, or an
     * older object of the same id), so nothing keeps its class loader alive. Never throws.
     */
    private fun forgetUnloaded(pluginId: String, instance: PanoPlugin?) {
        if (instance != null && instance.hostLoaded) {
            runCatching { instance.unload() }
                .onFailure { log.warn("Unload of plugin '{}' failed: {}", pluginId, it.message) }
        }

        val leftovers = (PluginEventManager.getEventListeners().keys + pluginUiManager.getRegisteredPlugins().map { it.first })
            .filter { runCatching { it.pluginId == pluginId }.getOrDefault(false) }
            .toSet()

        for (plugin in leftovers) {
            log.warn("Plugin '{}' left registrations behind after its unload; they are dropped", pluginId)

            runCatching { pluginEventManager.unregisterPlugin(plugin) }
            runCatching { pluginUiManager.unRegisterPlugin(plugin) }

            for (listener in lifecycleListeners.toList()) {
                runCatching { runBlocking { listener.onPluginUnload(plugin) } }
            }
        }

        for (listener in lifecycleListeners.toList()) {
            runCatching {
                when (listener) {
                    is com.panomc.platform.api.PluginDatabaseManager -> listener.forgetPluginId(pluginId)
                    is com.panomc.platform.route.RouterProvider -> runBlocking { listener.forgetPluginId(pluginId) }
                }
            }.onFailure { log.warn("Could not drop the registrations of plugin '{}': {}", pluginId, it.message) }
        }
    }

    /** PF4J unloads the dependents with their dependency: each is remembered so an update can load it again. */
    private fun rememberHeldDependents(pluginId: String) {
        val queue = ArrayDeque(dependencyResolver.getDependents(pluginId))
        val seen = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val dependent = queue.removeFirst()

            if (!seen.add(dependent)) {
                continue
            }

            val wrapper = getPlugin(dependent)

            // Every dependent is loaded again (else it vanished from the panel until the next boot); the ones that
            // were running or waiting start again once their dependencies run.
            if (wrapper != null) {
                pendingReload[dependent] = wrapper.pluginPath

                if (held.containsKey(dependent)) {
                    reloadAsHeld.add(dependent)
                }

                if (!held.containsKey(dependent) && !isDisabledByAdmin(dependent) &&
                    (wrapper.pluginState == PluginState.STARTED || stoppedWithDependency.remove(dependent) ||
                        waiting.containsKey(dependent) || licenseBlocked.contains(dependent))
                ) {
                    restartAfterReload.add(dependent)
                }
            }

            queue.addAll(dependencyResolver.getDependents(dependent))
        }
    }

    fun addLifecycleListener(listener: PluginLifecycleListener) {
        lifecycleListeners.add(listener)
    }

    override fun createPluginRepository(): PluginRepository {
        return CompoundPluginRepository()
            .add(DevelopmentPluginRepository(getPluginsRoots())) { this.isDevelopment }
            .add(JarPluginRepository(getPluginsRoots())) { this.isNotDevelopment }
    }

    override fun createPluginDescriptorFinder(): CompoundPluginDescriptorFinder {
        return CompoundPluginDescriptorFinder()
            .add(PanoManifestPluginDescriptorFinder())
    }

    override fun createPluginFactory(): PluginFactory {
        return PluginFactory(pluginEventManager, pluginUiManager)
    }

    override fun createPluginLoader(): PluginLoader {
        return CompoundPluginLoader()
            .add(PanoPluginLoader(this)) { this.isNotDevelopment }
    }

    fun getActivePanoPlugins(): List<PanoPlugin> = getPlugins(PluginState.STARTED).mapNotNull { plugin ->
        runCatching {
            val pluginWrapper = plugin as PanoPluginWrapper
            pluginWrapper.plugin as PanoPlugin
        }.getOrNull()
    }

    fun getPluginWrappers() = plugins.values.map { it as PanoPluginWrapper }

    override fun createPluginWrapper(
        pluginDescriptor: PluginDescriptor,
        pluginPath: Path,
        pluginClassLoader: ClassLoader
    ): PluginWrapper {
        val pluginWrapper = PanoPluginWrapper(this, pluginDescriptor, pluginPath, pluginClassLoader)

        pluginWrapper.setPluginFactory(getPluginFactory())

        return pluginWrapper
    }


    override fun enablePlugin(pluginId: String): Boolean {
        val wrapper = getPlugin(pluginId)

        // A refused plugin is never instantiated: reading wrapper.plugin below would run its load() / onCreate().
        if (!isPluginValid(wrapper)) {
            log.warn("Plugin '{}' can not be enabled: it is not compatible with this Pano", pluginId)

            return false
        }

        val stateBefore = wrapper.pluginState

        /**
         * PF4J returns `true` from [AbstractPluginManager.enablePlugin] whenever the plugin is
         * **not** [PluginState.DISABLED] — including [PluginState.FAILED] and [PluginState.STOPPED]
         * — without unloading or resetting the extension instance.
         *
         * Our host hooks ([PanoPlugin.load], [PanoPlugin.onEnable]) must never run again without a
         * prior [disablePlugin] unload cycle; otherwise Spring/plugin contexts corrupt and retries
         * can stall or crash the Vert.x worker (operators see the panel “splash” / disconnect).
         */
        if (stateBefore == PluginState.FAILED || stateBefore == PluginState.STOPPED) {
            disablePlugin(pluginId)
        }

        val result = super.enablePlugin(pluginId)

        val plugin = getPlugin(pluginId)?.plugin as PanoPlugin?

        if (result) {
            plugin?.let {
                try {
                    runBlocking {
                        it.load()

                        lifecycleListeners.forEach { listener ->
                            listener.onPluginEnable(it)
                        }

                        it.onEnable()
                    }
                } catch (e: Exception) {
                    log.error(
                        "Plugin '{}' host enable hooks failed during load/onEnable: {}",
                        pluginId,
                        e.message,
                        e,
                    )
                }
            }
        } else {
            log.warn("Plugin '{}' super.enablePlugin returned false (pf4j did not enable)", pluginId)
        }

        return result
    }

    override fun startPlugin(pluginId: String?): PluginState? {
        // Started again by anyone: no longer a dependent waiting to come back after its dependency's update.
        pluginId?.let { stoppedWithDependency.remove(it) }

        // The last line of the gate: a plugin outside the supported level is never started, whoever asks (a
        // dependent pulling it in, the console, a reload). It stays DISABLED; the panel refuses with an error first.
        if (pluginId != null) {
            val heldWrapper = getPlugin(pluginId)

            if (heldWrapper != null && !heldWrapper.pluginState.isStarted && (verdictOf(pluginId) != Verdict.OK || heldBy(pluginId) != null)) {
                isPluginValid(heldWrapper)

                if (heldWrapper.pluginState != PluginState.DISABLED) {
                    heldWrapper.pluginState = PluginState.DISABLED
                }

                log.warn("Plugin '{}' is not started: it is not compatible with this Pano", pluginId)

                return heldWrapper.pluginState
            }
        }

        // A plugin never starts without a required plugin that is off (PF4J would switch the admin's choice back on
        // by starting it first): it waits for it and starts together with it.
        if (pluginId != null) {
            val wrapper = getPlugin(pluginId)
            val dependency = wrapper?.takeIf { !it.pluginState.isStarted }?.descriptor?.let { offDependency(it) }

            if (wrapper != null && dependency != null) {
                if (wrapper.pluginState != PluginState.DISABLED || !waiting.containsKey(pluginId)) {
                    park(pluginId, dependency)
                }

                return PluginState.DISABLED
            }
        }

        // Previous startup failures can leave a stale wrapper.failedException. If we don't
        // clear it before a retry, the old LicenseRequiredException keeps forcing FAILED.
        if (pluginId != null) {
            getPlugin(pluginId)?.failedException = null
        }

        val state = super.startPlugin(pluginId)
        if (pluginId != null) {
            val wrapper = getPlugin(pluginId)
            val licenseException = wrapper?.failedException.findLicenseRequiredInCauseChain()
            if (licenseException != null) {
                captureLicenseFailureIfAny(pluginId)
                // Held off in memory, not in disabled.txt: a license that arrives later starts it (retryLicenseBlocked).
                blockForLicense(pluginId)
                log.warn(
                    "Plugin '{}' startup blocked by license; it starts once a license is available: {}",
                    pluginId,
                    licenseException.message,
                )
                return PluginState.FAILED
            }
        }

        if (pluginId != null && state == PluginState.FAILED) {
            val fe = getPlugin(pluginId)?.failedException
            if (fe != null) {
                val licenseException = fe.findLicenseRequiredInCauseChain()
                if (licenseException != null) {
                    blockForLicense(pluginId)
                    log.warn(
                        "Plugin '{}' startup blocked by license; it starts once a license is available: {}",
                        pluginId,
                        licenseException.message,
                    )
                } else {
                    log.warn(
                        "Plugin '{}' PF4J start failed — {}",
                        pluginId,
                        fe.message ?: fe.javaClass.simpleName,
                        fe,
                    )
                }
            } else {
                log.warn(
                    "Plugin '{}' PF4J start failed (FAILED state, no failedException on wrapper)",
                    pluginId,
                )
            }
            captureLicenseFailureIfAny(pluginId)
        }

        if (pluginId != null && state == PluginState.STARTED) {
            runCatching {
                Main.applicationContext.getBean(LicenseManager::class.java).clearFailure(pluginId)
            }
            licenseBlocked.remove(pluginId)
            waiting.remove(pluginId)
            startWaitingDependents(pluginId)
        }
        return state
    }

    /**
     * Look at the failed plugin's exception chain. If any link is a [LicenseRequiredException]
     * (raised by the plugin's own onStart while calling LicenseManager), record it on the
     * host so the panel UI can surface it.
     *
     * Best-effort: we deliberately swallow any exception here because failure-capture must
     * never itself break plugin loading.
     */
    private fun captureLicenseFailureIfAny(pluginId: String) {
        try {
            val wrapper = getPlugin(pluginId) ?: return
            val ex = wrapper.failedException ?: return
            val licEx = ex.findLicenseRequiredInCauseChain() ?: return
            val licenseManager = try {
                Main.applicationContext.getBean(LicenseManager::class.java)
            } catch (_: BeansException) {
                return
            } catch (_: Throwable) {
                return
            }
            licenseManager.recordFailure(pluginId, licEx)
        } catch (_: Throwable) {
            // never fail plugin loading because of bookkeeping
        }
    }

    override fun stopPlugin(pluginId: String?): PluginState? {
        if (pluginId != null) {
            stoppedWithDependency.addAll(runningDependents(pluginId))
        }

        return super.stopPlugin(pluginId)
    }

    /** Every plugin that requires [pluginId], directly or through another one, and is running now. */
    private fun runningDependents(pluginId: String): Set<String> {
        val found = mutableSetOf<String>()
        val queue = ArrayDeque(dependencyResolver.getDependents(pluginId))

        while (queue.isNotEmpty()) {
            val dependent = queue.removeFirst()

            if (!found.add(dependent)) {
                continue
            }

            queue.addAll(dependencyResolver.getDependents(dependent))
        }

        return found.filter { getPlugin(it)?.pluginState == PluginState.STARTED }.toSet()
    }

    fun reloadPlugin(pluginId: String): PluginState? {
        stopPlugin(pluginId)
        return startPlugin(pluginId)
    }

    override fun disablePlugin(pluginId: String): Boolean {
        // A refused plugin never started, so it has no hooks to run (and its class is not instantiated).
        if (incompatible.containsKey(pluginId)) {
            return super.disablePlugin(pluginId)
        }

        val plugin = getPlugin(pluginId).plugin as PanoPlugin

        // Stopped first, so onStop still finds the plugin's context, which unload() closes (PF4J would stop it only
        // in super.disablePlugin, after the hooks).
        if (plugin.hostLoaded && getPlugin(pluginId).pluginState.isStarted) {
            stopPlugin(pluginId)
        }

        // Hooks run once per enable cycle: a plugin already taken off (or disabled before) has nothing left to undo.
        if (plugin.hostLoaded) {
            runBlocking {
                lifecycleListeners.forEach { listener ->
                    listener.onPluginDisable(plugin)
                }

                plugin.onDisable()
                plugin.unload()
            }
        }

        return super.disablePlugin(pluginId)
    }
}