package com.panomc.platform.node

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.panomc.platform.node.dto.NodeResources
import java.lang.reflect.Type

/**
 * Reads the `resources` column of a node row, which holds the whole snapshot as one JSON string.
 *
 * Gson sees a string where the field type is an object, so it needs this the same way server
 * settings do. Anything unreadable degrades to [NodeResources.EMPTY]: a node row must still load
 * when the column holds a shape written by a newer Pano.
 */
class NodeResourcesDeserializer : JsonDeserializer<NodeResources> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): NodeResources {
        if (json == null || !json.isJsonPrimitive) {
            return NodeResources.EMPTY
        }

        return NodeResources.decode(json.asString)
    }
}
