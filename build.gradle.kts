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
    }

    build {
        dependsOn(":plugins:build")
        dependsOn(":Updater:build")
        dependsOn(":Pano:build")
    }

    clean {
        dependsOn(":Pano:clean")
        dependsOn(":Updater:clean")
        dependsOn(":plugins:clean")
    }

    jar {
        enabled = false
    }

    register("publishPano") {
        dependsOn(":Pano:publishToMavenLocal")
    }
}