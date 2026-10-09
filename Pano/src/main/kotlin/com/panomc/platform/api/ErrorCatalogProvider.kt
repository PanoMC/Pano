package com.panomc.platform.api

import com.panomc.platform.model.Error

/**
 * A plugin lists the error codes it can answer by declaring a bean of this type (any Spring stereotype in the
 * plugin's bean context). The codes appear in the plugin's OpenAPI document (`x-pano-error-catalog`, a sorted list
 * of `{ code, status }`), so a client author sees them even for a code no endpoint names in its `doc`.
 *
 * Each entry builds one example of the error; the class needs an all-default constructor, or arguments
 * [ErrorStandIn] can fill. Entries that cannot be built, or that throw, are left out of the document and never
 * break it. Plugin codes are not prefixed and must be unique inside the plugin (doc 04 section 3).
 */
interface ErrorCatalogProvider {
    val entries: List<() -> Error>
}

/**
 * Builds an [Error] of a class whose constructor needs arguments, with stand-in values: the code and status of an
 * error are constants of its class, so any argument will do. Used by the OpenAPI generator and by the code-freeze test.
 */
object ErrorStandIn {
    /** An instance of [type] through its all-default constructor, else through its first constructor with stand-in arguments; `null` when neither works. */
    fun create(type: Class<*>): Error? {
        val constructors = type.declaredConstructors
            // The synthetic default-arguments constructor of Kotlin carries a marker parameter; skip it.
            .filter { !it.isSynthetic }
            .sortedBy { it.parameterCount }

        for (constructor in constructors) {
            val arguments = constructor.parameterTypes.map { standIn(it) }

            if (arguments.any { it === NONE }) {
                continue
            }

            val created = runCatching {
                constructor.isAccessible = true

                constructor.newInstance(*arguments.toTypedArray()) as? Error
            }.getOrNull()

            if (created != null) {
                return created
            }
        }

        return null
    }

    /** Marks a parameter type no stand-in exists for. */
    private val NONE = Any()

    private fun standIn(type: Class<*>): Any = when (type) {
        String::class.java, CharSequence::class.java -> "x"
        Int::class.javaPrimitiveType, Int::class.javaObjectType -> 1
        Long::class.javaPrimitiveType, Long::class.javaObjectType -> 1L
        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> false
        Double::class.javaPrimitiveType, Double::class.javaObjectType -> 1.0
        List::class.java, Collection::class.java, Iterable::class.java -> listOf<Any?>()
        Map::class.java -> mapOf<String, Any?>()
        else -> NONE
    }
}
