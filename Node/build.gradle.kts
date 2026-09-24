import java.security.MessageDigest

val appMainClass = "com.panomc.node.Main"

val vertxVersion: String by project
val gsonVersion: String by project

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "9.2.2"
    application
}

group = "com.panomc"
version =
    (if (project.hasProperty("version") && project.findProperty("version") != "unspecified") project.findProperty("version") else "local-build")!!

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    // WebSocket client, HTTP client and the timer wheel the daemon schedules everything on.
    implementation("io.vertx:vertx-core:$vertxVersion")

    // Same encoder Pano decodes node payloads with, so a field never has to be hand-written twice.
    implementation("com.google.code.gson:gson:$gsonVersion")

    // HOCON, so config.conf looks and reads exactly like Pano's and the plugin's.
    implementation("com.typesafe:config:1.4.3")

    // The same cron library Pano validates schedules with, so a schedule saved in the panel and
    // the schedule this daemon runs can never disagree about when "0 4 * * 0" is.
    implementation("com.cronutils:cron-utils:9.2.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.3")
}

tasks {
    shadowJar {
        archiveClassifier.set("")

        // Vert.x resolves its transports and codecs through META-INF/services; without merging
        // them the fat jar boots with whichever copy happened to win.
        mergeServiceFiles()

        manifest {
            val attrMap = mutableMapOf<String, String>()

            attrMap["VERSION"] = version.toString()
            attrMap["Main-Class"] = appMainClass

            attributes(attrMap)
        }

        // The release asset name Pano looks for, so the local-node provisioner can find it
        // without knowing the version.
        archiveFileName.set("pano-node.jar")
    }

    /**
     * The checksum Pano verifies a downloaded pano-node.jar against.
     *
     * Published next to the jar in the GitHub release, because that is where LocalNodeManager
     * looks for it: without the file the daemon is downloaded with a warning and no verification
     * at all, which is the one case where a release asset being absent is a security property
     * quietly turning itself off.
     */
    register("panoNodeJarChecksum") {
        dependsOn("shadowJar")

        val jarFile = layout.buildDirectory.file("libs/pano-node.jar")
        val checksumFile = layout.buildDirectory.file("libs/pano-node.jar.sha256")

        inputs.file(jarFile)
        outputs.file(checksumFile)

        doLast {
            val jar = jarFile.get().asFile
            val digest = MessageDigest.getInstance("SHA-256")

            jar.inputStream().use { stream ->
                val buffer = ByteArray(1 shl 16)

                while (true) {
                    val read = stream.read(buffer)

                    if (read <= 0) {
                        break
                    }

                    digest.update(buffer, 0, read)
                }
            }

            // sha256sum's own format, so `sha256sum -c` works on it and Pano's reader (which
            // takes everything before the first space) is happy either way.
            checksumFile.get().asFile.writeText(
                digest.digest().joinToString("") { "%02x".format(it) } + "  " + jar.name + "\n"
            )
        }
    }

    named("build") {
        dependsOn("panoNodeJarChecksum")
    }

    register("buildDev") {
        dependsOn("build")
    }

    /**
     * The daemon, packaged the way pano-updater.jar is: zipped and dropped into Pano's resources,
     * so the Pano jar carries its own pano-node.jar and unpacks it next to itself at boot
     * (NodeJarSync). A zip rather than the bare jar because Shadow would otherwise merge the
     * daemon's classes into Pano's instead of keeping it as one file.
     */
    val zipNode = register<Zip>("zipNode") {
        description = "Packages pano-node.jar into the ZIP archive Pano bundles."
        group = "distribution"

        mustRunAfter(shadowJar)

        from(shadowJar.flatMap { it.archiveFile })
        archiveFileName.set("pano-node.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    }

    val copyNodeZip = register<Copy>("copyNodeZip") {
        description = "Copies pano-node.zip into Pano's resources."
        group = "distribution"

        mustRunAfter(zipNode)

        from(zipNode.flatMap { it.archiveFile })
        into(rootProject.layout.projectDirectory.dir("Pano/src/main/resources"))

        outputs.upToDateWhen { false }
    }

    jar {
        dependsOn(shadowJar)
        dependsOn(zipNode)
        dependsOn(copyNodeZip)

        // We only ever ship the fat jar.
        enabled = false
    }
}

application {
    mainClass.set(appMainClass)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
