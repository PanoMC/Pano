package com.panomc.platform.frontend

import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** A plugin's fallback page cannot be used; handled like a refused endpoint path (the plugin load fails). */
class FallbackPageRefusal(message: String) : RuntimeException(message)

/**
 * A page that is registered, with the class loader that owns its template.
 *
 * @property id the full target id (`auth.activate`, `market.order`)
 * @property owner `core` or the plugin id
 */
class RegisteredFallbackPage(
    val id: String,
    val page: FallbackPage,
    val classLoader: ClassLoader,
    val owner: String
)

/**
 * The built-in fallback pages by target id (doc 05 section 10.3). Core's pages are registered when the router
 * is built, a plugin's when the plugin loads (their target is prefixed with the plugin's namespace) and
 * dropped again when it unloads.
 *
 * The registry also tells the [FrontendUrlMap] which `fallback: true` targets of a plugin have a page, so a
 * plugin that declares one without a page fails its load, naming the target.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class FallbackPageRegistry(
    frontendUrlMap: FrontendUrlMap,
    private val logger: Logger
) {
    private val pages = ConcurrentHashMap<String, RegisteredFallbackPage>()
    private val pagesOfPlugin = ConcurrentHashMap<String, List<String>>()

    init {
        frontendUrlMap.fallbackPageCheck = { targetId -> has(targetId) }
    }

    fun find(targetId: String): RegisteredFallbackPage? = pages[targetId]

    fun has(targetId: String) = pages.containsKey(targetId)

    /** Every registered target id, sorted. */
    fun ids(): List<String> = pages.keys.sorted()

    /** Registers the pages of core; a target is its full id. A second page for one id is left out. */
    fun registerCore(corePages: Collection<FallbackPage>) {
        val loader = FallbackPageRegistry::class.java.classLoader

        corePages.forEach { page ->
            add(RegisteredFallbackPage(page.target, page, page.javaClass.classLoader ?: loader, CoreFrontendTargets.OWNER))
        }
    }

    /**
     * Registers the pages of a plugin under `<namespace>.<target>`. A target that is not a plain name
     * refuses the whole plugin; a target another page already holds is left out and logged. [classLoader] owns
     * the templates; it is the class loader of each page's class unless given.
     */
    fun registerPlugin(
        pluginId: String,
        namespace: String,
        pluginPages: Collection<FallbackPage>,
        classLoader: ClassLoader? = null
    ) {
        unregisterPlugin(pluginId)

        val checked = pluginPages.map { page ->
            if (!TARGET_PATTERN.matches(page.target)) {
                throw FallbackPageRefusal(
                    "Fallback page ${page.javaClass.name} of plugin '$pluginId' has the target '${page.target}'; " +
                        "give a plain name without the namespace and without dots, such as 'order'."
                )
            }

            "$namespace.${page.target}" to page
        }

        val accepted = mutableListOf<String>()

        checked.forEach { (id, page) ->
            val registered = RegisteredFallbackPage(id, page, classLoader ?: page.javaClass.classLoader, pluginId)

            if (add(registered)) {
                accepted.add(id)
            }
        }

        if (accepted.isNotEmpty()) {
            pagesOfPlugin[pluginId] = accepted
        }
    }

    /** Drops every page of the plugin. */
    fun unregisterPlugin(pluginId: String) {
        pagesOfPlugin.remove(pluginId)?.forEach { id ->
            pages.computeIfPresent(id) { _, registered -> if (registered.owner == pluginId) null else registered }
        }
    }

    private fun add(registered: RegisteredFallbackPage): Boolean {
        val existing = pages.putIfAbsent(registered.id, registered)

        if (existing != null) {
            logger.warn(
                "Fallback page for target '{}' ({}) is already registered by '{}' and is left out.",
                registered.id, registered.page.javaClass.name, existing.owner
            )

            return false
        }

        return true
    }

    companion object {
        private val TARGET_PATTERN = Regex("""[A-Za-z0-9][A-Za-z0-9_-]*""")
    }
}
