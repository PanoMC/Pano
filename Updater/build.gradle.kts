val appMainClass = "com.panomc.updater.Main"

plugins {
    kotlin("jvm") version "2.1.0"
    id("com.gradleup.shadow") version "8.3.8"
    application
}

group = "com.panomc"
version =
    (if (project.hasProperty("version") && project.findProperty("version") != "unspecified") project.findProperty("version") else "local-build")!!

repositories {
    mavenCentral()
}

dependencies {
    // No external dependencies
}

// Copy target moved to Pano/src/main/resources
val panoResourcesDir = rootProject.layout.projectDirectory.dir("Pano/src/main/resources")

tasks {
    // Produce a fat jar with Shadow plugin
    shadowJar {
        archiveClassifier.set("")

        manifest {
            val attrMap = mutableMapOf<String, String>()
            attrMap["VERSION"] = version.toString()
            attributes(attrMap)
        }

        // Final jar file name
        archiveFileName.set("pano-updater.jar")
    }

    // Zip the produced shadow jar (zip contains only the jar file)
    val zipUpdater = register<Zip>("zipUpdater") {
        description = "Packages pano-updater.jar into a ZIP archive."
        group = "distribution"

        mustRunAfter(shadowJar)

        from(shadowJar.flatMap { it.archiveFile }) // include the jar as a single entry
        archiveFileName.set("pano-updater.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    }

    // Copy the ZIP into Pano/build/generated/updater (NOT into src/main/resources)
    val copyUpdaterZip = register<Copy>("copyUpdaterZip") {
        description = "Copies pano-updater.zip into Pano generated resources."
        group = "distribution"

        mustRunAfter(zipUpdater)
        mustRunAfter(zipUpdater)

        from(zipUpdater.flatMap { it.archiveFile })
        into(panoResourcesDir)

        outputs.upToDateWhen { false }
    }


    // Convenience task to trigger build dev flow
    register("buildDev") {
        dependsOn("build")
    }

    // Ensure the pipeline runs on build
    jar {
        dependsOn(shadowJar)
        dependsOn(zipUpdater)
        dependsOn(copyUpdaterZip)

        // We don't need the plain jar artifact
        enabled = false
    }
}

application {
    mainClass.set(appMainClass)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

kotlin {
    jvmToolchain(11)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
