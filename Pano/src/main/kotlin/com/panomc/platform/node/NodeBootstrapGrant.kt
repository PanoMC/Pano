package com.panomc.platform.node

/**
 * What a node becomes when it spends a bootstrap token.
 *
 * A bootstrap token means "Pano itself arranged for this daemon to exist", which is why a node
 * that uses one is approved without anybody clicking accept. But Pano arranges for daemons in
 * three different ways, and the node has no business telling Pano which one it was: it is decided
 * when the token is minted and carried with it.
 */
data class NodeBootstrapGrant(val kind: NodeKind, val bootstrap: NodeBootstrap) {
    companion object {
        /** The node Pano spawns on its own machine. */
        val LOCAL_NODE = NodeBootstrapGrant(NodeKind.LOCAL, NodeBootstrap.LOCAL)

        /** A node on somebody else's host that Pano installed for them. */
        fun remote(bootstrap: NodeBootstrap) = NodeBootstrapGrant(NodeKind.REMOTE, bootstrap)
    }
}
