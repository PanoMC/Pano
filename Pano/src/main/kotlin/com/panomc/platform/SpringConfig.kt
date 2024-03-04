package com.panomc.platform

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.mail.MailClientProvider
import com.panomc.platform.route.RouterProvider
import com.panomc.platform.setup.SetupManager
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.templ.handlebars.HandlebarsTemplateEngine
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.SchemaRouter
import io.vertx.json.schema.SchemaRouterOptions
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.*


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

    private val pluginsDir = System.getProperty("pf4j.pluginsDir", "./plugins")

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
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun mailClientProvider(configManager: ConfigManager) = MailClientProvider.create(vertx, configManager)


    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun router(
        schemaParser: SchemaParser,
        configManager: ConfigManager,
        httpClient: HttpClient,
        setupManager: SetupManager,
        pluginManager: PluginManager
    ) =
        RouterProvider.create(
            vertx,
            applicationContext,
            schemaParser,
            configManager,
            httpClient,
            setupManager,
            pluginManager
        )
            .provide()

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun templateEngine(): HandlebarsTemplateEngine = HandlebarsTemplateEngine.create(vertx)

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun provideWebClient(): WebClient = WebClient.create(vertx)

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun provideSchemeParser(vertx: Vertx): SchemaParser = SchemaParser.createOpenAPI3SchemaParser(
        SchemaRouter.create(vertx, SchemaRouterOptions())
    )

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun provideHttpClient(): HttpClient = vertx.createHttpClient()

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun providePluginUiManager(): PluginUiManager = pluginUiManager

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun providePluginEventManager(): PluginEventManager = pluginEventManager
}