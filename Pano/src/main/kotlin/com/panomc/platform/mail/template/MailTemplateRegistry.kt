package com.panomc.platform.mail.template

import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PluginManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import com.panomc.platform.util.DevMode
import com.panomc.platform.util.FileResourceUtil.getOwnResourceStream
import com.panomc.platform.util.PluginDevUtil
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Who declares which mails: core (`mail-templates/index.json` of the platform jar) and every started plugin
 * (`mail-templates/index.json` of its own jar, read through its class loader on load, dropped on unload).
 *
 * Bundled `<key>/<variant>/<locale>.hbs` files are read through the owner's class loader. In development mode a
 * plugin's source resource folder (`src/main/resources/mail-templates`, found by [PluginDevUtil]) wins, so editing a
 * default needs no jar rebuild.
 */
@Service
class MailTemplateRegistry internal constructor(
    private val coreOwnerProvider: () -> MailTemplateOwner?,
    private val pluginNameOf: (String) -> String? = { null }
) : PluginLifecycleListener {
    private val lock = Any()

    private val pluginOwners = linkedMapOf<String, MailTemplateOwner>()

    private val coreOwner: MailTemplateOwner? by lazy { coreOwnerProvider() }

    @Autowired
    constructor(pluginManager: PluginManager) : this(
        { loadCore() },
        { pluginId -> (pluginManager.getPlugin(pluginId)?.descriptor as? PanoPluginDescriptor)?.name }
    ) {
        pluginManager.addLifecycleListener(this)
    }

    /** Core first, then plugins in load order. */
    fun owners(): List<MailTemplateOwner> = synchronized(lock) {
        listOfNotNull(coreOwner) + pluginOwners.values
    }

    fun owner(id: String): MailTemplateOwner? =
        if (id == CORE) coreOwner else synchronized(lock) { pluginOwners[id] }

    fun mail(owner: String, key: String): MailDefinition? = owner(owner)?.index?.mails?.firstOrNull { it.key == key }

    fun variant(owner: String, key: String, variant: String): VariantDefinition? = mail(owner, key)?.variant(variant)

    /** The raw bundled `.hbs` of that locale, or null when the owner ships none. */
    fun readBundled(owner: String, key: String, variant: String, locale: String): String? =
        owner(owner)?.read(key, variant, locale)

    internal fun registerPlugin(owner: MailTemplateOwner) = synchronized(lock) {
        pluginOwners[owner.id] = owner
    }

    internal fun unregisterPlugin(pluginId: String) = synchronized(lock) {
        pluginOwners.remove(pluginId)
    }

    override suspend fun onPluginLoad(plugin: PanoPlugin) {
        unregisterPlugin(plugin.pluginId)

        val owner = try {
            pluginOwner(plugin.pluginId, pluginNameOf(plugin.pluginId), plugin.javaClass.classLoader)
        } catch (e: Exception) {
            logger.error("Mail templates of plugin '${plugin.pluginId}' are invalid and were not registered: ${e.message}")

            null
        } ?: return

        registerPlugin(owner)
    }

    override suspend fun onPluginDisable(plugin: PanoPlugin) {
        unregisterPlugin(plugin.pluginId)
    }

    override suspend fun onPluginUnload(plugin: PanoPlugin) {
        unregisterPlugin(plugin.pluginId)
    }

    companion object {
        const val CORE = "core"
        const val INDEX_FILE = "index.json"
        const val ROOT = "mail-templates"

        private val logger = LoggerFactory.getLogger("MailTemplateRegistry")

        private fun loadCore(): MailTemplateOwner? = try {
            classpathOwner(CORE, null, null, MailTemplateRegistry::class.java.classLoader, own = false)
        } catch (e: Exception) {
            logger.error("Core mail templates are invalid: ${e.message}")

            null
        }

        /** A plugin's owner, null when it declares no mails. */
        internal fun pluginOwner(pluginId: String, pluginName: String?, classLoader: ClassLoader): MailTemplateOwner? {
            val sourceDir = if (DevMode.isActive()) PluginDevUtil.getPluginResourceDir(pluginId, ROOT) else null

            if (sourceDir != null && File(sourceDir, INDEX_FILE).isFile) {
                val index = MailTemplateIndex.parse(JsonObject(File(sourceDir, INDEX_FILE).readText()))

                return MailTemplateOwner(pluginId, pluginId, pluginName, index) { path ->
                    // Read on every call: a developer's edit shows on the next send / preview.
                    val dir = PluginDevUtil.getPluginResourceDir(pluginId, ROOT)?.takeIf { DevMode.isActive() }
                    val file = dir?.let { File(it, path) }

                    if (file != null && file.isFile) file.readText()
                    else classLoader.getOwnResourceStream("$ROOT/$path")?.bufferedReader()?.use { it.readText() }
                }
            }

            return classpathOwner(pluginId, pluginId, pluginName, classLoader, own = true)
        }

        internal fun classpathOwner(
            id: String,
            pluginId: String?,
            pluginName: String?,
            classLoader: ClassLoader,
            own: Boolean
        ): MailTemplateOwner? {
            fun open(path: String) =
                if (own) classLoader.getOwnResourceStream("$ROOT/$path") else classLoader.getResourceAsStream("$ROOT/$path")

            val indexText = open(INDEX_FILE)?.bufferedReader()?.use { it.readText() } ?: return null
            val index = MailTemplateIndex.parse(JsonObject(indexText))
            val cache = ConcurrentHashMap<String, Any>()

            return MailTemplateOwner(id, pluginId, pluginName, index) { path ->
                val cached = cache.getOrPut(path) {
                    open(path)?.bufferedReader()?.use { it.readText() } ?: MISSING
                }

                cached as? String
            }
        }

        private val MISSING = Any()
    }
}

/**
 * One owner of mails: core ([pluginId] null) or a plugin. [reader] returns the text of a file under its
 * `mail-templates/` folder, or null.
 */
class MailTemplateOwner(
    val id: String,
    val pluginId: String?,
    val pluginName: String?,
    val index: MailTemplateIndex,
    private val reader: (String) -> String?
) {
    fun read(key: String, variant: String, locale: String): String? {
        if (!MailTemplateIndex.KEY_PATTERN.matches(key) || !MailTemplateIndex.KEY_PATTERN.matches(variant) ||
            !LOCALE_PATTERN.matches(locale)
        ) {
            return null
        }

        return reader("$key/$variant/$locale.hbs")
    }

    companion object {
        private val LOCALE_PATTERN = Regex("^[A-Za-z0-9_-]{1,16}$")
    }
}
