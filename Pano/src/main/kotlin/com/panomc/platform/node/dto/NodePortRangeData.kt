package com.panomc.platform.node.dto

/**
 * The ports a node's servers may bind (`node.port-range` in its config.conf, or
 * `PANO_NODE_PORT_RANGE`), from the optional `portRange` of its hello. Both ends inclusive.
 *
 * Untrusted and absent from an older node, so both fields are nullable; [toRangeOrNull] decides
 * whether it is a range at all.
 */
data class NodePortRangeData(
    val start: Int? = null,
    val end: Int? = null
) {
    /**
     * The range, or null when either end is missing or outside 1..65535, or they are the wrong way
     * round. Null is "this node has not said", which allocates the way Pano always has.
     */
    fun toRangeOrNull(): IntRange? {
        val first = start?.takeIf { it in 1..MAX_PORT } ?: return null
        val last = end?.takeIf { it in 1..MAX_PORT } ?: return null

        return if (first <= last) first..last else null
    }

    companion object {
        private const val MAX_PORT = 65535
    }
}
