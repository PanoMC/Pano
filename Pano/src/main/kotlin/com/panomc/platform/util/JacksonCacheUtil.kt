package com.panomc.platform.util

import org.slf4j.LoggerFactory

/**
 * Vert.x's shared Jackson mapper (`DatabindCodec`, behind `JsonObject.mapFrom` / `mapTo`) caches types, serializers and
 * deserializers by class with strong references. Once a plugin class went through it, the cache pins that class and so
 * the plugin's whole class loader after the plugin is unloaded (PluginClassLoaderLeakTest: every old loader of an
 * updated plugin stayed). The caches refill on demand, so emptying them costs a few rebuilt serializers.
 *
 * Reflection only: jackson-databind is on the runtime class path (through Vert.x), not on the compile class path.
 */
object JacksonCacheUtil {
    private val log = LoggerFactory.getLogger(JacksonCacheUtil::class.java)

    private const val CODEC = "io.vertx.core.json.jackson.DatabindCodec"

    /** Empties the class caches of Vert.x's shared mappers. Never throws. */
    fun forgetCachedClasses() {
        val codec = runCatching { Class.forName(CODEC) }.getOrNull() ?: return

        // Not every Vert.x version has a second, pretty-printing mapper.
        val mappers = listOf("mapper", "prettyMapper")
            .mapNotNull { name -> runCatching { codec.getMethod(name).invoke(null) }.getOrNull() }
            .distinct()

        for (mapper in mappers) {
            flush(mapper)
        }
    }

    private fun flush(mapper: Any) {
        // ObjectMapper.getTypeFactory().clearCache()
        attempt("type cache") {
            val typeFactory = mapper.javaClass.getMethod("getTypeFactory").invoke(mapper)
            typeFactory.javaClass.getMethod("clearCache").invoke(typeFactory)
        }

        // ((DefaultSerializerProvider) ObjectMapper.getSerializerProvider()).flushCachedSerializers()
        attempt("serializer cache") {
            val provider = mapper.javaClass.getMethod("getSerializerProvider").invoke(mapper)
            provider.javaClass.getMethod("flushCachedSerializers").invoke(provider)
        }

        // ObjectMapper._rootDeserializers.clear(): no public way to it.
        attempt("root deserializers") {
            val field = findField(mapper.javaClass, "_rootDeserializers")
            (field.get(mapper) as? MutableMap<*, *>)?.clear()
        }

        // ObjectMapper.getDeserializationContext()._cache.flushCachedDeserializers(): no public way to the cache.
        attempt("deserializer cache") {
            val context = mapper.javaClass.getMethod("getDeserializationContext").invoke(mapper)
            val cache = findField(context.javaClass, "_cache").get(context)
            cache.javaClass.getMethod("flushCachedDeserializers").invoke(cache)
        }
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field {
        var current: Class<*>? = type

        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true

                return it
            }

            current = current.superclass
        }

        throw NoSuchFieldException(name)
    }

    private inline fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.debug("Could not empty the Jackson {}: {}", what, e.toString())
        }
    }
}
