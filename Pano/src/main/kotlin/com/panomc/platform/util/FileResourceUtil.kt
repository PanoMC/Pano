package com.panomc.platform.util

import com.panomc.platform.api.PanoPlugin

object FileResourceUtil {
    fun PanoPlugin.getResource(resourceName: String) = this.javaClass.classLoader.getResourceAsStream(resourceName)
}