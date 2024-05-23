val pluginsDir by extra { file("${layout.buildDirectory.get()}/plugins") }

buildscript {
    repositories {
        mavenCentral()
        jcenter()
    }
}

plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("kapt") version "2.0.0"
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
    }

    build {
        dependsOn(":plugins:build")
        dependsOn(":Pano:build")
    }

    clean {
        dependsOn(":Pano:clean")
        dependsOn(":plugins:clean")
    }

    jar {
        enabled = false
    }
}