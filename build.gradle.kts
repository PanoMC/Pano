val pluginsDir by extra { file("${layout.buildDirectory.get()}/plugins") }

buildscript {
    repositories {
        mavenCentral()
    }
}

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("kapt") version "2.1.0"
    application
    `maven-publish`
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
        enabled = true
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}
