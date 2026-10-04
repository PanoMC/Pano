val pluginsDir by extra { file("${layout.buildDirectory.get()}/plugins") }

buildscript {
    repositories {
        mavenCentral()
    }
}

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("kapt") version "2.2.21"
    application
}

allprojects {
    repositories {
        mavenCentral()
    }
}

tasks {
    named("run") {
        enabled = false
        mustRunAfter(":plugins:build")
        doLast {
            dependsOn(":Pano:run")
        }
        project(":Updater") {
            tasks.matching { it.name == "run" }.configureEach {
                enabled = false
            }
        }

        // The node daemon is a separate process an operator (or Pano's local-node supervisor)
        // starts on its own; `./gradlew run` must never boot one alongside the platform.
        project(":Node") {
            tasks.matching { it.name == "run" }.configureEach {
                enabled = false
            }
        }
    }

    build {
        dependsOn(":plugins:build")
        dependsOn(":Updater:build")
        dependsOn(":Node:build")
        // Both of the daemon's release assets, named explicitly: the jar and the checksum are
        // published together and a release with only one of them is a release Pano cannot verify.
        dependsOn(":Node:panoNodeJarChecksum")
        dependsOn(":Pano:build")
    }

    clean {
        dependsOn(":Pano:clean")
        dependsOn(":Updater:clean")
        dependsOn(":Node:clean")
        dependsOn(":plugins:clean")
    }

    jar {
        enabled = false
    }

    register("publishPano") {
        dependsOn(":Pano:publishToMavenLocal")
    }
}