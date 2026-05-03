package com.panomc.platform

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.platform.db.DBEntity
import com.panomc.platform.db.model.Server
import com.panomc.platform.notification.NotificationType
import com.panomc.platform.notification.NotificationTypeDeserializer
import com.panomc.platform.notification.ServerSettingsDeserializer
import com.panomc.platform.token.TokenType
import com.panomc.platform.token.TokenTypeAdapter
import com.panomc.platform.token.TokenTypeRegistry
import com.panomc.platform.route.RouterProvider
import com.panomc.platform.util.deserializer.BooleanDeserializer
import com.panomc.platform.util.deserializer.JsonObjectDeserializer
import com.panomc.platform.util.deserializer.LenientListLongAdapterFactory
import com.panomc.platform.util.deserializer.LenientListStringAdapterFactory
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.PoolOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.templ.handlebars.HandlebarsTemplateEngine
import io.vertx.json.schema.Draft
import io.vertx.json.schema.JsonSchemaOptions
import io.vertx.json.schema.SchemaRepository
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.*
import java.util.concurrent.TimeUnit


@Configuration
@ComponentScan("com.panomc.platform")
open class SpringConfig {
    companion object {
        private const val SECRET_KEY = ""

        internal lateinit var vertx: Vertx
        private lateinit var logger: Logger

        internal val pluginEventManager = PluginEventManager()
        internal val pluginUiManager = PluginUiManager()

        internal fun setDefaults(vertx: Vertx, logger: Logger) {
            SpringConfig.vertx = vertx
            SpringConfig.logger = logger
        }
    }

    @Autowired
    private lateinit var applicationContext: AnnotationConfigApplicationContext

    @Bean
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun vertx() = vertx

    @Bean
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun logger() = logger

    @Bean
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun recaptcha() = ReCaptcha(SECRET_KEY)

    @Bean
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun routerProvider(
        schemaRepository: SchemaRepository,
        pluginManager: PluginManager,
        uiManager: UIManager
    ) =
        RouterProvider.create(
            vertx,
            applicationContext,
            schemaRepository,
            pluginManager,
            uiManager
        )

    @Bean
    open fun router(routerProvider: RouterProvider) = routerProvider.provide()

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun templateEngine(): HandlebarsTemplateEngine = HandlebarsTemplateEngine.create(vertx)

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun provideWebClient(): WebClient = WebClient.create(vertx, WebClientOptions().setFollowRedirects(true))

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun provideSchemeParser(vertx: Vertx): SchemaRepository =
        SchemaRepository.create(JsonSchemaOptions().setBaseUri("https://panomc.com").setDraft(Draft.DRAFT7))

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun provideHttpClient(): HttpClient = vertx.createHttpClient(
        HttpClientOptions()
            .setConnectTimeout(10000)
            .setKeepAlive(true)
            // Vert.x default keep-alive timeout is 60s, but the Bun/SvelteKit adapter-node
            // upstream closes idle keep-alive connections after ~5s (Node's default
            // [server.keepAliveTimeout]). When the page sits idle for ≥5s the upstream
            // silently sends FIN; Vert.x still has the socket in the pool and reuses it on
            // the next request, getting RST/EOF and surfacing it as a [Stream reset: 0]
            // logged by [HttpClientRequestImpl] (the proxy's exception handler resets the
            // upstream request with code 0 on any downstream failure). The user-visible
            // symptom is broken page assets that "magically" load after F5.
            //
            // Keep our timeout WELL below the upstream's so the pooled socket is evicted
            // before it can go stale. Note that eviction only happens on pool-cleaner
            // ticks (default [PoolOptions.cleanerPeriod] = 1000ms), so the actual eviction
            // delay is up to keepAliveTimeout + cleanerPeriod. With a 4s timeout that
            // worst-case is ~5s, exactly Bun's idle-close — a race we lost in practice.
            // 2s timeout + 500ms cleaner gives a hard ceiling of ~2.5s, comfortably below
            // Bun's 5s.
            //
            // NOTE: Only [setKeepAliveTimeout] is set, NOT [setIdleTimeout]. The two are
            // very different: keepAliveTimeout is HTTP/1.x specific and only affects the
            // window between two requests on a kept-alive connection. idleTimeout is a
            // socket-level read/write inactivity timeout that ALSO kills upgraded
            // connections such as the Vite HMR WebSocket in theme dev mode, which would
            // make the dev page silently reload every few seconds.
            .setKeepAliveTimeout(2)
            .setIdleTimeout(60)
            .setIdleTimeoutUnit(TimeUnit.SECONDS),
        // Default pool size in Vert.x 5 is 5 connections per origin (HTTP/1.x). This
        // HttpClient is shared between the panel-ui and theme Bun reverse-proxy targets,
        // and a single SvelteKit page open fires 10–30 parallel module/asset requests
        // (importmap entries, _app/* chunks, images, fonts...). With 5 connections any
        // burst that arrives faster than the upstream can reply gets queued; if the
        // wait-queue is also exhausted the request fails immediately with
        // ConnectionPoolTooBusyException, which the proxy turns into a 502 Bad Gateway.
        // Pool sizing in Vert.x 5 lives on PoolOptions, not HttpClientOptions.
        //
        // [setCleanerPeriod] tightens the eviction loop from the default 1000ms to 500ms
        // — see the [setKeepAliveTimeout] comment above for why we need it.
        PoolOptions()
            .setHttp1MaxSize(256)
            .setHttp2MaxSize(256)
            .setMaxWaitQueueSize(-1)
            .setCleanerPeriod(500)
    )

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun providePluginUiManager(): PluginUiManager = pluginUiManager

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun providePluginEventManager(): PluginEventManager = pluginEventManager

    @Bean
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun gson(tokenTypeRegistry: TokenTypeRegistry): Gson {
        val builder = GsonBuilder()

        builder.registerTypeAdapterFactory(LenientListLongAdapterFactory())
        builder.registerTypeAdapterFactory(LenientListStringAdapterFactory())
        builder.registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
        builder.registerTypeAdapter(java.lang.Boolean::class.java, BooleanDeserializer())
        builder.registerTypeAdapter(JsonObject::class.java, JsonObjectDeserializer())
        builder.registerTypeAdapter(NotificationType::class.java, NotificationTypeDeserializer())
        builder.registerTypeAdapter(TokenType::class.java, TokenTypeAdapter(tokenTypeRegistry))
        builder.registerTypeAdapter(Server.Companion.ServerSettings::class.java, ServerSettingsDeserializer())

        val gson = builder.create()

        // Initialize DBEntity gson
        DBEntity.gson = gson

        return gson
    }
}