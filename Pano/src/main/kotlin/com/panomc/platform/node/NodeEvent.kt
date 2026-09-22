package com.panomc.platform.node

import com.panomc.platform.db.model.Node
import com.panomc.platform.util.TextUtil.convertToSnakeCase
import java.lang.reflect.ParameterizedType

/**
 * One inbound node message and what Pano does with it.
 *
 * Mirrors `ServerEvent`: subclasses are `@Event` beans, collected by [NodeManager], and the wire
 * name comes from the class name (`NodeHelloEvent` -> `NODE_HELLO`). The handler is handed the
 * [Node] the socket belongs to, which is the only thing it may trust about the sender — anything
 * inside the payload, a server uuid above all, has to be checked against that node.
 */
abstract class NodeEvent<R : NodeEventRequest, M : NodeEventResponse> {
    @Suppress("UNCHECKED_CAST")
    val requestClass: Class<R> by lazy {
        val superclass = (this::class.java.genericSuperclass as ParameterizedType)

        superclass.actualTypeArguments[0] as Class<R>
    }

    abstract suspend fun handle(request: R, node: Node): M?

    fun getEventName() = this.javaClass.simpleName.replace("Event", "").convertToSnakeCase().uppercase()
}
