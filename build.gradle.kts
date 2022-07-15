val vertxVersion = "4.3.2"

plugins {
    java
    kotlin("jvm") version "1.7.10"
    kotlin("kapt") version "1.7.10"
    id("io.vertx.vertx-plugin") version "1.3.0"
}

group = "com.panomc.platform"
version = "1.0"

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/iovertx-3720/")
    maven("https://jitpack.io")
}

vertx {
    mainVerticle = "com.panomc.platform.Main"
    vertxVersion = this@Build_gradle.vertxVersion
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
    testImplementation("io.vertx:vertx-unit:$vertxVersion")

    implementation("io.vertx:vertx-web:$vertxVersion")
    implementation("io.vertx:vertx-web-client:$vertxVersion")
    implementation("io.vertx:vertx-mysql-client:$vertxVersion")
    implementation("io.vertx:vertx-mail-client:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-web-templ-handlebars:$vertxVersion")
    implementation("io.vertx:vertx-config:$vertxVersion")
    implementation("io.vertx:vertx-config-hocon:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("io.vertx:vertx-web-validation:$vertxVersion")
    implementation("io.vertx:vertx-json-schema:$vertxVersion")

    implementation(group = "org.apache.logging.log4j", name = "log4j-api", version = "2.18.0")
    implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.18.0")
    implementation(group = "org.apache.logging.log4j", name = "log4j-slf4j-impl", version = "2.18.0")

    // recaptcha v2 1.0.4
    implementation("com.github.triologygmbh:reCAPTCHA-V2-java:1.0.4")

    // https://mvnrepository.com/artifact/commons-codec/commons-codec
    implementation(group = "commons-codec", name = "commons-codec", version = "1.15")

    implementation("io.jsonwebtoken:jjwt-api:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    // Uncomment the next line if you want to use RSASSA-PSS (PS256, PS384, PS512) algorithms:
    //runtimeOnly("org.bouncycastle:bcprov-jdk15on:1.60")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")

    implementation("org.springframework:spring-context:5.3.21")
}

tasks {
    register("copyJar") {
        doLast {
            copy {
                from(shadowJar.get().archiveFile.get().asFile.absolutePath)
                into("./")
            }
        }

        dependsOn(shadowJar)
    }

    vertxDebug {
        environment("EnvironmentType", "DEVELOPMENT")
    }

    vertxRun {
        environment("EnvironmentType", "DEVELOPMENT")
    }

    build {
        dependsOn("copyJar")
    }

    register("buildDev") {
        dependsOn("build")
    }

    shadowJar {
        manifest {
            if (project.gradle.startParameter.taskNames.contains("buildDev"))
                attributes(mapOf("MODE" to "DEVELOPMENT"))
        }
    }
}