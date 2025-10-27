package com.panomc.platform.server

import com.panomc.platform.db.model.Server
import com.panomc.platform.util.TextUtil.convertToSnakeCase
import java.lang.reflect.ParameterizedType

abstract class ServerEvent<R : ServerEventRequest> {
    @Suppress("UNCHECKED_CAST")
    val requestClass: Class<R> by lazy {
        val superclass = (this::class.java.genericSuperclass as ParameterizedType)
        superclass.actualTypeArguments[0] as Class<R>
    }

    abstract suspend fun handle(request: R, server: Server): PlatformMessage?

    fun getEventName() = this.javaClass.simpleName.replace("Event", "").convertToSnakeCase().uppercase()
}