package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class NodeJarSyncPlanTest {
    private val devJar = File("/srv/pano/Node/build/libs/pano-node.jar")

    @Test
    fun `a release install replaces a jar from another version, and one it does not have`() {
        assertTrue(NodeJarSyncPlan.needsDownload("1.0.0-alpha.520", chosenByOperator = false, jarVersion = "1.0.0-alpha.519"))
        assertTrue(NodeJarSyncPlan.needsDownload("1.0.0-alpha.520", chosenByOperator = false, jarVersion = null))
        assertFalse(NodeJarSyncPlan.needsDownload("1.0.0-alpha.520", chosenByOperator = false, jarVersion = "1.0.0-alpha.520"))
    }

    @Test
    fun `a jar somebody chose, and every development build, are left alone`() {
        assertFalse(NodeJarSyncPlan.needsDownload("1.0.0-alpha.520", chosenByOperator = true, jarVersion = "1.0.0-alpha.1"))
        assertFalse(NodeJarSyncPlan.needsDownload("local-build", chosenByOperator = false, jarVersion = "1.0.0-alpha.1"))
        assertFalse(NodeJarSyncPlan.isReleaseBuild("local-build"))
        assertFalse(NodeJarSyncPlan.isReleaseBuild(""))
        assertTrue(NodeJarSyncPlan.isReleaseBuild("1.0.0"))
    }

    @Test
    fun `the operator's jar is the configured one, the system property's, or the checkout's build`() {
        val configured = File("/opt/custom/pano-node.jar")
        val released = File("/srv/pano/pano-node.jar")

        assertTrue(NodeJarSyncPlan.isOperatorChoice(configured, configured.path, null, devJar))
        assertTrue(NodeJarSyncPlan.isOperatorChoice(configured, null, " ${configured.path} ", devJar))
        assertTrue(NodeJarSyncPlan.isOperatorChoice(devJar, null, null, devJar))
        assertFalse(NodeJarSyncPlan.isOperatorChoice(released, null, null, devJar))
        assertFalse(NodeJarSyncPlan.isOperatorChoice(released, configured.path, "", devJar))
    }
}
