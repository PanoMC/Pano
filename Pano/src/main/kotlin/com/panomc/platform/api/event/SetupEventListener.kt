package com.panomc.platform.api.event

interface SetupEventListener : PanoEventListener {
    suspend fun onSetupFinished() {}
}
