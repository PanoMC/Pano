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
            .setIdleTimeout(60)
            .setIdleTimeoutUnit(TimeUnit.SECONDS)
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
    open fun gson(): Gson {
        val builder = GsonBuilder()

        builder.registerTypeAdapterFactory(LenientListLongAdapterFactory())
        builder.registerTypeAdapterFactory(LenientListStringAdapterFactory())
        builder.registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
        builder.registerTypeAdapter(java.lang.Boolean::class.java, BooleanDeserializer())
        builder.registerTypeAdapter(JsonObject::class.java, JsonObjectDeserializer())
        builder.registerTypeAdapter(NotificationType::class.java, NotificationTypeDeserializer())
        builder.registerTypeAdapter(TokenType::class.java, TokenTypeAdapter())
        builder.registerTypeAdapter(Server.Companion.ServerSettings::class.java, ServerSettingsDeserializer())

        // Register core token types before building Gson
        TokenTypeRegistry.registerCoreTypes()

        val gson = builder.create()

        // Initialize DBEntity gson
        DBEntity.gson = gson

        return gson
    }
}