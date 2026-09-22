package com.panomc.platform.node

import java.io.File

/**
 * Finds the `pano-node.jar` this Pano should run as its local node.
 *
 * Four places, in the order that makes the least surprising thing win. An explicit `jar-path` in
 * config.conf is an operator's decision and beats everything. `-Dpano.node.jar` is how a
 * development run points at a jar it just built. `Node/build/libs/pano-node.jar` is where the
 * Gradle build puts it, which makes a checkout work with no configuration at all. The working
 * directory and the running jar's directory are where a release install has it: [NodeJarSync]
 * downloads it there from this Pano's release and replaces it when Pano has updated itself.
 *
 * Pure and side-effect free so the precedence can be tested without a filesystem layout that only
 * exists on a release install.
 */
object LocalNodeJarLocator {
    const val JAR_NAME = "pano-node.jar"

    /**
     * The same jar under the name a Pano Agent is saved as in a server's folder (SM-74): the agent
     * is the node, run from there instead of the server jar, and recognises itself by this name.
     */
    const val AGENT_JAR_NAME = "pano-agent.jar"

    /** System property a development run sets to point at a freshly built jar. */
    const val JAR_PROPERTY = "pano.node.jar"

    /** Where the Gradle build leaves the jar inside a checkout. */
    val DEV_RELATIVE_PATH = "Node" + File.separator + "build" + File.separator + "libs" + File.separator + JAR_NAME

    /**
     * The first candidate that exists, or null when the jar has to be downloaded.
     *
     * [exists] is injected purely so the order can be asserted in a test; production always passes
     * the real check.
     */
    fun locate(
        configuredPath: String?,
        systemProperty: String?,
        workingDir: File,
        runningJarDir: File?,
        exists: (File) -> Boolean = { it.isFile }
    ): File? {
        candidates(configuredPath, systemProperty, workingDir, runningJarDir).forEach { candidate ->
            if (exists(candidate)) {
                return candidate
            }
        }

        return null
    }

    /** Every place that is looked at, in order. Exposed so a failure can say where it looked. */
    fun candidates(
        configuredPath: String?,
        systemProperty: String?,
        workingDir: File,
        runningJarDir: File?
    ): List<File> {
        val candidates = mutableListOf<File>()

        configuredPath?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(File(it).absoluteFile) }
        systemProperty?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(File(it).absoluteFile) }

        candidates.add(File(workingDir, DEV_RELATIVE_PATH).absoluteFile)
        candidates.add(File(workingDir, JAR_NAME).absoluteFile)

        runningJarDir?.let { candidates.add(File(it, JAR_NAME).absoluteFile) }

        return candidates.distinctBy { it.path }
    }
}
