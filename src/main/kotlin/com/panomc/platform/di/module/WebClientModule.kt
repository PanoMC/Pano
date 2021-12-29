package com.panomc.platform.di.module

import dagger.Module
import dagger.Provides
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import javax.inject.Singleton

@Module
class WebClientModule {

    @Provides
    @Singleton
    fun provideWebClient(vertx: Vertx) = WebClient.create(vertx)
}