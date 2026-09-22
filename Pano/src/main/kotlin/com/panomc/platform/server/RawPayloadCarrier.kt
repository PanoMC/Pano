package com.panomc.platform.server

import io.vertx.core.json.JsonObject

/**
 * A plugin reply whose whole frame is kept, not only the fields this Pano version declared.
 *
 * Every other event here is decoded into a typed request and that is the right shape for a message
 * whose fields Pano decided. `FILE_RESULT` is not one of those: it answers nine different requests
 * with nine different bodies, and the agent-lite contract is that those bodies are the node's
 * verbatim (§2.4.17 C), so pinning them into a Kotlin class here would create a second definition
 * to keep in step. [ServerManager] fills [raw] in after decoding, and the channel hands it back as
 * the payload the caller reads.
 */
interface RawPayloadCarrier {
    var raw: JsonObject?
}
