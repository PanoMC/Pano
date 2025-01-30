import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.HttpURLConnection
import java.net.URL

val vertxVersion: String by project
val gsonVersion: String by project
val springContextVersion: String by project
val handlebarsVersion: String by project
val log4jVersion = "2.24.3"
val appMainClass = "com.panomc.platform.Main"
val pf4jVersion: String by project
val pluginsDir: File? by rootProject.extra

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("kapt") version "2.1.0"
    id("com.gradleup.shadow") version "8.3.5"
    application
    `maven-publish`
}

group = "com.panomc"
version =
    (if (project.hasProperty("version") && project.findProperty("version") != "unspecified") project.findProperty("version") else "local-build")!!

val buildType = project.findProperty("buildType") as String? ?: "alpha"
val timeStamp: String by project
val buildDir by extra { file("${rootProject.layout.buildDirectory.get()}/libs") }

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/iovertx-3720/")
    maven("https://jitpack.io")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
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
    implementation("io.vertx:vertx-web-proxy:$vertxVersion")

    // https://mvnrepository.com/artifact/com.auth0/java-jwt
    implementation("com.auth0:java-jwt:4.4.0")

    implementation(group = "org.apache.logging.log4j", name = "log4j-api", version = log4jVersion)
    implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = log4jVersion)
    implementation(group = "org.apache.logging.log4j", name = "log4j-slf4j2-impl", version = log4jVersion)

    // recaptcha v2 1.0.4
    implementation("com.github.triologygmbh:reCAPTCHA-V2-java:1.0.4")

    // https://mvnrepository.com/artifact/commons-codec/commons-codec
    implementation(group = "commons-codec", name = "commons-codec", version = "1.17.2")

    // https://mvnrepository.com/artifact/commons-io/commons-io
    implementation("commons-io:commons-io:2.18.0")

    // https://mvnrepository.com/artifact/org.apache.tika/tika-core
    implementation("org.apache.tika:tika-core:2.9.2")

    // https://mvnrepository.com/artifact/org.springframework/spring-context
    implementation("org.springframework:spring-context:$springContextVersion")

    // https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:$gsonVersion")

    implementation("org.pf4j:pf4j:${pf4jVersion}")
    kapt("org.pf4j:pf4j:${pf4jVersion}")

    implementation("com.typesafe:config:1.4.3")
}

val organization = "PanoMC"
val repositories = listOf("$organization/panel-ui", "$organization/setup-ui", "$organization/vanilla-theme")
val outputDir = file("src/main/resources/UIFiles")

tasks {
    register("downloadUIReleases") {
        doFirst {
            println("Fetching latest releases for repositories...")

            // Create the assets directory (if exists, delete old zip files)
            if (outputDir.exists()) {
                outputDir.listFiles()?.forEach { file ->
                    if (file.extension == "zip") {
                        println("Deleting existing zip file: ${file.name}")
                        file.delete()
                    }
                }
            } else {
                outputDir.mkdirs() // Create the directory if it doesn't exist
            }

//            val isDevBuild = project.gradle.startParameter.taskNames.contains("buildDev")
            val isDevBuild = true

            repositories.forEach { repo ->
                println("Processing repository: $repo")

                // GitHub API URL
                val apiUrl = "https://api.github.com/repos/$repo/releases"

                // Send API request
                val connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                // Read the response
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val releases = JsonParser.parseString(response).asJsonArray

                // Get the release that fits the condition (non-prerelease or prerelease based on buildDev task)
                val releaseFilter = if (isDevBuild) {
                    { release: JsonObject -> release["prerelease"].asBoolean }
                } else {
                    { release: JsonObject -> !release["prerelease"].asBoolean }
                }

                val latestRelease = releases.firstOrNull { release ->
                    releaseFilter(release.asJsonObject)
                } ?: throw IllegalStateException("No matching release found in repository: $repo")

                // Check asset files and get `repo-name-version.zip` file
                val repoName = repo.substringAfter("/")
                val assets = latestRelease.asJsonObject["assets"].asJsonArray
                val asset = assets.firstOrNull { asset ->
                    val name = asset.asJsonObject["name"].asString
                    name.startsWith(repoName) && name.endsWith(".zip")
                }
                    ?: throw IllegalStateException("No matching ${repoName}-*.zip file found in the latest release of $repo")

                // Get the download URL
                val downloadUrl = asset.asJsonObject["browser_download_url"].asString
                println("Downloading asset from: $downloadUrl")

                // Download & save the file
                val outputFile = File(outputDir, asset.asJsonObject["name"].asString)
                URL(downloadUrl).openStream().use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("Downloaded asset saved to: ${outputFile.absolutePath}")
            }
        }
    }

    register("copyJar") {
        if (shadowJar.get().archiveFile.get().asFile.parentFile.absolutePath != buildDir.absolutePath) {
            doLast {
                copy {
                    from(shadowJar.get().archiveFile.get().asFile.absolutePath)
                    into(buildDir)
                }
            }
        }

        outputs.upToDateWhen { false }
        mustRunAfter(shadowJar)
    }

    register("buildDev") {
        dependsOn("build")
    }

    shadowJar {
        archiveClassifier.set("")

        from("src/main/resources")
        mustRunAfter("downloadUIReleases")

        manifest {
            val attrMap = mutableMapOf<String, String>()

            if (project.gradle.startParameter.taskNames.contains("buildDev"))
                attrMap["MODE"] = "DEVELOPMENT"

            attrMap["VERSION"] = version.toString()
            attrMap["BUILD_TYPE"] = buildType

            attributes(attrMap)
        }

        archiveFileName.set("${rootProject.name}-${version}.jar")

        if (project.gradle.startParameter.taskNames.contains("publish")) {
            archiveFileName.set(archiveFileName.get().lowercase())
        }
    }

    jar {
        dependsOn(shadowJar)
        dependsOn("copyJar")

        enabled = false
    }
}

tasks.named("build") {
    dependsOn("downloadUIReleases")
}

tasks.named<JavaExec>("run") {
    environment("EnvironmentType", "DEVELOPMENT")
    environment("PanoVersion", version)
    environment("PanoBuildType", buildType)
    pluginsDir?.let { systemProperty("pf4j.pluginsDir", it.absolutePath) }
}

application {
    mainClass.set(appMainClass)
}

publishing {
    repositories {
        maven {
            name = "Pano"
            url = uri("https://maven.pkg.github.com/panocms/pano")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME_GITHUB")
                password = project.findProperty("gpr.token") as String? ?: System.getenv("TOKEN_GITHUB")
            }
        }
    }

    publications {
        create<MavenPublication>("shadow") {
            artifactId = "pano"

            artifact(tasks["shadowJar"])
        }
    }
}

java {
    withJavadocJar()
    withSourcesJar()

    // Use Java 21 for compilation
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21) // Ensure Kotlin uses the Java 21 toolchain
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}