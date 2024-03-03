val pluginsDir by extra { file("${layout.buildDirectory.get()}/plugins") }

buildscript {
    repositories {
        mavenCentral()
        jcenter()
    }
}

plugins {
    kotlin("jvm") version "1.9.20"
    kotlin("kapt") version "1.9.20"
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