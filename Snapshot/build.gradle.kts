val gsonVersion: String by project

plugins {
    kotlin("jvm") version "2.4.21"
}

group = "com.panomc"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    // The manifest and the repository config are Gson documents, written exactly as pano-node writes them.
    api("com.google.code.gson:gson:$gsonVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
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

tasks.named<Test>("test") {
    useJUnitPlatform()

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
