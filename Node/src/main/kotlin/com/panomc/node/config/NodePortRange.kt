package com.panomc.node.config

import io.vertx.core.json.JsonObject

/**
 * The node's port range as it travels: `start-end` on the command line (`--port-range`) and in
 * `PANO_NODE_PORT_RANGE`, `{start, end}` in `NODE_HELLO`.
 *
 * The range is what a node that runs in a container publishes, so it is not a preference: a Coolify
 * node whose container publishes 25660-25669 and whose server was given 25565 was a server nobody
 * outside could reach, with everything reporting healthy. Pano allocates inside the range the
 * hello announces, and the node moves a port Pano asks for outside it (see `PortAllocator.resolve`).
 */
object NodePortRange {
    private val FORMAT = Regex("^(\\d{1,5})\\s*-\\s*(\\d{1,5})$")

    /**
     * `25660-25669` as a range, or null when [value] is anything else: not two numbers, a number
     * outside 1..65535, or the two the wrong way round. A single port is written `25660-25660`.
     *
     * Strict where the config file is forgiving (`NodeConfig.portRange` turns a hand-edited range
     * round): a typo in a compose file should be an error on start, not a range nobody asked for.
     */
    fun parse(value: String): IntRange? {
        val match = FORMAT.matchEntire(value.trim()) ?: return null

        val start = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val end = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null

        return if (start <= end) start..end else null
    }

    /** `start-end`, the form [parse] reads. */
    fun format(range: IntRange) = "${range.first}-${range.last}"

    /** The hello's `portRange`: both ends inclusive. */
    fun toHello(range: IntRange): JsonObject = JsonObject()
        .put("start", range.first)
        .put("end", range.last)
}
