package com.panomc.platform

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.route.RouterProvider
import com.panomc.platform.setup.SetupManager
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
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
    open fun router(
        schemaRepository: SchemaRepository,
        configManager: ConfigManager,
        httpClient: HttpClient,
        setupManager: SetupManager,
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
            .provide()

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