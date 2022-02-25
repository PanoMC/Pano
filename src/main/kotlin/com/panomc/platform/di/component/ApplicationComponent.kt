package com.panomc.platform.di.component

import com.panomc.platform.Main
import com.panomc.platform.db.DaoImpl
import com.panomc.platform.db.DatabaseMigration
import com.panomc.platform.di.module.*
import com.panomc.platform.model.Api
import com.panomc.platform.model.SetupApi
import com.panomc.platform.route.api.auth.RegisterAPI
import com.panomc.platform.route.api.panel.BasicDataAPI
import com.panomc.platform.route.api.panel.platformAuth.RefreshKeyAPI
import com.panomc.platform.route.api.server.ConnectNewAPI
import com.panomc.platform.route.api.setup.CheckAPI
import com.panomc.platform.route.api.setup.DBConnectionTestAPI
import com.panomc.platform.route.api.setup.FinishAPI
import com.panomc.platform.route.api.setup.step.NextStepAPI
import com.panomc.platform.route.staticFolder.src.SrcFolderRoute
import com.panomc.platform.route.template.IndexTemplate
import com.panomc.platform.util.AssetsStaticHandler
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        (RecaptchaModule::class),
        (VertxModule::class),
        (LoggerModule::class),
        (RouterModule::class),
        (TemplateEngineModule::class),
        (ConfigManagerModule::class),
        (SetupManagerModule::class),
        (DatabaseManagerModule::class),
        (PlatformCodeManagerModule::class),
        (MailClientModule::class),
        (AuthProviderModule::class),
        (WebClientModule::class)
    ]
)
interface ApplicationComponent {
    fun inject(main: Main)

    fun inject(indexTemplate: IndexTemplate)

    fun inject(assetsStaticHandler: AssetsStaticHandler)

    fun inject(srcFolderRoute: SrcFolderRoute)

    fun inject(checkAPI: CheckAPI)

    fun inject(nextStepAPI: NextStepAPI)

    fun inject(finishAPI: FinishAPI)

    fun inject(basicDataAPI: BasicDataAPI)

    fun inject(refreshKeyAPI: RefreshKeyAPI)

    fun inject(connectNewAPI: ConnectNewAPI)

    fun inject(api: Api)

    fun inject(daoImpl: DaoImpl)

    fun inject(databaseMigration: DatabaseMigration)

    fun inject(setupApi: SetupApi)

    fun inject(dbConnectionTestAPI: DBConnectionTestAPI)

    fun inject(registerAPI: RegisterAPI)
}