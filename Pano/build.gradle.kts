
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.zip.ZipInputStream

val vertxVersion: String by project
val gsonVersion: String by project
val springContextVersion: String by project
val handlebarsVersion: String by project
val log4jVersion = "2.25.0"
val appMainClass = "com.panomc.platform.Main"
val pf4jVersion: String by project
val pluginsDir: File? by rootProject.extra

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("kapt") version "2.2.21"
    id("com.gradleup.shadow") version "9.2.2"
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

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.3")
    // Gradle 9 requires the JUnit Platform launcher to be declared explicitly on the
    // test runtime classpath; without it `gradle test` errors with "Failed to load
    // JUnit Platform" even when jupiter-api + jupiter-engine are present.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.3")
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
    implementation("com.auth0:java-jwt:4.5.0")

    implementation(group = "org.apache.logging.log4j", name = "log4j-api", version = log4jVersion)
    implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = log4jVersion)
    implementation(group = "org.apache.logging.log4j", name = "log4j-slf4j2-impl", version = log4jVersion)

    // recaptcha v2 1.0.4
    implementation("com.github.triologygmbh:reCAPTCHA-V2-java:1.0.4")

    // https://mvnrepository.com/artifact/commons-codec/commons-codec
    implementation(group = "commons-codec", name = "commons-codec", version = "1.20.0")

    // https://mvnrepository.com/artifact/commons-io/commons-io
    implementation("commons-io:commons-io:2.21.0")

    // https://mvnrepository.com/artifact/org.apache.tika/tika-core
    implementation("org.apache.tika:tika-core:2.9.4")

    // https://mvnrepository.com/artifact/org.springframework/spring-context
    implementation("org.springframework:spring-context:$springContextVersion")

    // https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:$gsonVersion")

    implementation("org.pf4j:pf4j:${pf4jVersion}")
    kapt("org.pf4j:pf4j:${pf4jVersion}")

    implementation("com.typesafe:config:1.4.3")

    // https://mvnrepository.com/artifact/org.imgscalr/imgscalr-lib
    implementation("org.imgscalr:imgscalr-lib:4.2")

    // Let's Encrypt / ACME
    implementation("org.shredzone.acme4j:acme4j-client:3.5.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    
    // JLine and terminal support
    implementation("org.jline:jline:3.29.0")
    implementation("org.fusesource.jansi:jansi:2.4.1")
    implementation("net.java.dev.jna:jna:5.16.0")

    // AuthMe Migration support
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.yaml:snakeyaml:2.4")

    // LuckPerms Migration support (H2 database — must use 2.1.x to read LP's format-2 files)
    implementation("com.h2database:h2:2.1.214")

    // Password hashing
    implementation("de.mkammerer:argon2-jvm:2.11")
    implementation("org.mindrot:jbcrypt:0.4")
}

val organization = "PanoMC"
val uiReleasesFile = rootProject.file("ui-releases.yml")
val repoMapping = mapOf(
    "panel-ui" to "$organization/panel-ui",
    "setup-ui" to "$organization/setup-ui",
    "vanilla-theme" to "$organization/vanilla-theme"
)
val mailTemplatesRepo = "$organization/pano-email"
val outputDir = file("src/main/resources/UIFiles")
val mailTemplatesOutputDir = file("src/main/resources")

tasks {
    register("downloadUIReleases") {
        doFirst {
            println("Reading UI release versions from ${uiReleasesFile.absolutePath}")

            if (!uiReleasesFile.exists()) {
                throw IllegalStateException("ui-releases.yml not found at ${uiReleasesFile.absolutePath}")
            }

            // Parse the YAML file (simple key: value format)
            val versions = mutableMapOf<String, String>()
            uiReleasesFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split(":", limit = 2)
                    if (parts.size == 2) {
                        versions[parts[0].trim()] = parts[1].trim()
                    }
                }
            }

            println("Versions to download: $versions")

            // Create the assets directory (if exists, delete old zip files)
            if (outputDir.exists()) {
                outputDir.listFiles()?.forEach { file ->
                    if (file.extension == "zip") {
                        println("Deleting existing zip file: ${file.name}")
                        file.delete()
                    }
                }
            } else {
                outputDir.mkdirs()
            }

            versions.forEach { (component, version) ->
                val repo = repoMapping[component]
                    ?: throw IllegalStateException("Unknown component '$component' in ui-releases.yml. Known components: ${repoMapping.keys}")

                println("Processing $component $version from $repo")

                // GitHub API URL for the specific release by tag
                val apiUrl = "https://api.github.com/repos/$repo/releases/tags/$version"

                val connection = URI(apiUrl).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                val ghToken = System.getenv("GITHUB_TOKEN") ?: System.getenv("TOKEN_GITHUB")
                if (!ghToken.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $ghToken")
                }

                if (connection.responseCode != 200) {
                    throw IllegalStateException("Failed to fetch release $version for $repo: HTTP ${connection.responseCode}")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val release = JsonParser.parseString(response).asJsonObject

                // Find the zip asset
                val assets = release["assets"].asJsonArray
                val asset = assets.firstOrNull { asset ->
                    val name = asset.asJsonObject["name"].asString
                    name.startsWith(component) && name.endsWith(".zip")
                }
                    ?: throw IllegalStateException("No matching ${component}-*.zip file found in release $version of $repo")

                // Get the download URL
                val downloadUrl = asset.asJsonObject["browser_download_url"].asString
                println("Downloading asset from: $downloadUrl")

                // Download & save the file
                val outputFile = File(outputDir, asset.asJsonObject["name"].asString)
                URI(downloadUrl).toURL().openStream().use { input: java.io.InputStream ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("Downloaded asset saved to: ${outputFile.absolutePath}")
            }
        }
    }

    register("downloadMailTemplates") {
        doFirst {
            println("Fetching latest release for mail templates...")

            // Create the resources directory if it doesn't exist
            if (!mailTemplatesOutputDir.exists()) {
                mailTemplatesOutputDir.mkdirs()
            }

            val repo = mailTemplatesRepo
            println("Processing repository: $repo")

            // GitHub API URL
            val apiUrl = "https://api.github.com/repos/$repo/releases"

            // Send API request
            val connection = URI(apiUrl).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            // Read the response
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val releases = JsonParser.parseString(response).asJsonArray

            // Get the latest release (including prereleases)
            val latestRelease = releases.firstOrNull()?.asJsonObject
                ?: throw IllegalStateException("No releases found in repository: $repo")

            // Check asset files and get the first zip file
            val assets = latestRelease["assets"].asJsonArray
            val asset = assets.firstOrNull { asset ->
                val name = asset.asJsonObject["name"].asString
                name.endsWith(".zip")
            }
                ?: throw IllegalStateException("No zip file found in the latest release of $repo")

            // Get the download URL
            val downloadUrl = asset.asJsonObject["browser_download_url"].asString
            println("Downloading asset from: $downloadUrl")

            // Download & save the file as mail-templates.zip
            val outputFile = File(mailTemplatesOutputDir, "mail-templates.zip")
            URI(downloadUrl).toURL().openStream().use { input: java.io.InputStream ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("Downloaded mail templates saved to: ${outputFile.absolutePath}")

            // Extract the zip file to src/main/resources/mail
            val mailDir = File(mailTemplatesOutputDir, "mail")

            // Delete existing mail directory contents if it exists
            if (mailDir.exists()) {
                println("Deleting existing mail directory contents...")
                mailDir.deleteRecursively()
            }

            // Create mail directory
            mailDir.mkdirs()

            // Extract zip file
            println("Extracting mail templates to: ${mailDir.absolutePath}")
            FileInputStream(outputFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val file = File(mailDir, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            // Delete the zip file after extraction
            outputFile.delete()
            println("Mail templates extracted successfully")
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

        manifest {
            val attrMap = mutableMapOf<String, String>()

            if (project.gradle.startParameter.taskNames.contains("buildDev"))
                attrMap["MODE"] = "DEVELOPMENT"

            attrMap["VERSION"] = version.toString()
            attrMap["BUILD_TYPE"] = buildType
            // JDK 22+ restricted native methods (JNI): avoids warnings when launching with java -jar
            // See https://openjdk.org/jeps/472 — ignored by older JVMs
            attrMap["Enable-Native-Access"] = "ALL-UNNAMED"

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

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    environment("EnvironmentType", "DEVELOPMENT")
    environment("PanoVersion", version)
    environment("PanoBuildType", buildType)
    pluginsDir?.let { systemProperty("pf4j.pluginsDir", it.absolutePath) }

    // Fix JLine illegal reflective access warnings and allow system terminal creation
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"
    )
    systemProperty("org.jline.utils.Log.level", "ERROR")

    if (project.hasProperty("nogui")) {
        args("-nogui")
    }

    if (project.hasProperty("dev")) {
        args("--dev")
    }

    if (project.hasProperty("demo")) {
        args("--demo")
    }
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
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11)) // Java 11 toolchain
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

// Ensure Pano's processResources waits for the Updater zip to be produced and copied
tasks.named<ProcessResources>("processResources") {
    dependsOn(":Updater:copyUpdaterZip")

    // Only depend on generateLicenses for build and buildDev tasks, not for run task
    if (!project.gradle.startParameter.taskNames.contains("run")) {
        dependsOn("generateLicenses")
    }
}

// Task to generate licenses.json from Gradle dependencies
tasks.register("generateLicenses") {
    group = "build"
    description = "Generates licenses.json file from Gradle dependencies"

    val outputDir = file("src/main/resources")
    val outputFile = File(outputDir, "licenses.json")

    // Ensure this task runs after dependencies are resolved
    dependsOn(configurations.runtimeClasspath)

    doLast {
        println("Generating licenses from Gradle dependencies...")

        val licenses = mutableListOf<Map<String, Any?>>()

        // Get all resolved dependencies
        val allConfigurations = listOf(
            configurations.runtimeClasspath.get(),
            configurations.compileClasspath.get(),
            configurations.testRuntimeClasspath.get(),
            configurations.testCompileClasspath.get()
        )

        val seenDependencies = mutableSetOf<String>()

        allConfigurations.forEach { configuration ->
            configuration.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                val moduleVersion = artifact.moduleVersion
                val group = moduleVersion.id.group
                val name = moduleVersion.id.name
                val version = moduleVersion.id.version
                val key = "$group:$name:$version"

                if (seenDependencies.add(key)) {
                    // Try to get license info from Maven Central API
                    val licenseInfo = try {
                        getLicenseFromMavenCentral(group, name, version)
                    } catch (e: Exception) {
                        println("⚠️  Could not fetch license info for $key: ${e.message}")
                        null
                    }

                    if (licenseInfo != null) {
                        licenses.add(licenseInfo)
                    } else {
                        // Add with unknown license if we can't fetch
                        licenses.add(
                            mapOf(
                                "name" to "$group:$name",
                                "version" to version,
                                "license" to "Unknown",
                                "licenseText" to null,
                                "repository" to "https://mvnrepository.com/artifact/$group/$name",
                                "homepage" to null,
                                "author" to null
                            )
                        )
                    }
                }
            }
        }

        // Sort by name
        licenses.sortBy { it["name"] as? String ?: "" }

        // Create JSON array (same format as panel-ui)
        val gson = com.google.gson.GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls() // Include null values (same format as panel-ui)
            .create()
        val licensesArray = com.google.gson.JsonArray()

        licenses.forEach { license ->
            val licenseObj = JsonObject()
            licenseObj.addProperty("name", license["name"] as? String)
            licenseObj.addProperty("version", license["version"] as? String)
            licenseObj.addProperty("license", license["license"] as? String)
            // Always add all fields, even if null (same format as panel-ui)
            // serializeNulls() ensures null values are included
            licenseObj.addProperty("licenseText", license["licenseText"] as? String)
            licenseObj.addProperty("repository", license["repository"] as? String)
            licenseObj.addProperty("homepage", license["homepage"] as? String)
            licenseObj.addProperty("author", license["author"] as? String)
            licensesArray.add(licenseObj)
        }

        // Write to file (array format, same as panel-ui)
        outputDir.mkdirs()
        outputFile.writeText(gson.toJson(licensesArray))

        println("✅ ${licenses.size} dependency licenses collected and saved to ${outputFile.absolutePath}")
    }

    outputs.file(outputFile)
}

// Helper function to get license info from Maven Central
fun getLicenseFromMavenCentral(group: String, name: String, version: String): Map<String, Any?>? {
    try {
        // Use Maven Central Search API - properly encode URL
        val query = "g:\"$group\" AND a:\"$name\" AND v:\"$version\""
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://search.maven.org/solrsearch/select?q=$encodedQuery&wt=json"
        val connection = URI(searchUrl).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val jsonResponse = JsonParser.parseString(response).asJsonObject

        // Try to get POM URL
        val docs = jsonResponse.getAsJsonObject("response")?.getAsJsonArray("docs")
        if (docs != null && docs.size() > 0) {
            val doc = docs[0].asJsonObject
            val groupId = doc.get("g")?.asString ?: group
            val artifactId = doc.get("a")?.asString ?: name

            // Try to fetch POM file
            val pomUrl = "https://repo1.maven.org/maven2/${
                groupId.replace(
                    '.',
                    '/'
                )
            }/$artifactId/$version/$artifactId-$version.pom"
            return try {
                val pomConnection = URI(pomUrl).toURL().openConnection() as HttpURLConnection
                pomConnection.requestMethod = "GET"
                pomConnection.connectTimeout = 5000
                pomConnection.readTimeout = 5000

                val pomContent = pomConnection.inputStream.bufferedReader().use { it.readText() }
                parseLicenseFromPom(pomContent, groupId, artifactId, version)
            } catch (e: Exception) {
                // Fallback: return basic info
                mapOf(
                    "name" to "$groupId:$artifactId",
                    "version" to version,
                    "license" to "Unknown",
                    "licenseText" to null,
                    "repository" to "https://mvnrepository.com/artifact/$groupId/$artifactId/$version",
                    "homepage" to null,
                    "author" to null
                )
            }
        }
    } catch (e: Exception) {
        // Return null to use fallback
    }

    return null
}

// Helper function to parse license from POM XML
fun parseLicenseFromPom(pomContent: String, groupId: String, artifactId: String, version: String): Map<String, Any?> {
    val licenses = mutableListOf<String>()
    val namePattern = Regex("<name>(.*?)</name>")

    // Extract license information from POM
    val licenseMatches = Regex("<license>.*?</license>", RegexOption.DOT_MATCHES_ALL).findAll(pomContent)
    licenseMatches.forEach { match ->
        val licenseBlock = match.value
        val nameMatch = namePattern.find(licenseBlock)

        val licenseName = nameMatch?.groupValues?.get(1) ?: "Unknown"
        licenses.add(licenseName)
    }

    return mapOf(
        "name" to "$groupId:$artifactId",
        "version" to version,
        "license" to if (licenses.isNotEmpty()) licenses.joinToString(", ") else "Unknown",
        "licenseText" to null,
        "repository" to "https://mvnrepository.com/artifact/$groupId/$artifactId/$version",
        "homepage" to null,
        "author" to null
    )
}
// Use JUnit Platform for JUnit Jupiter tests (HashUtilTest et al.). Without this the test
// task uses the legacy JUnit 4 discovery and finds nothing, even though we depend on
// junit-jupiter-engine. Also force the test executor onto JDK 21 — gradle defaults to
// the system JAVA_HOME (JDK 11 on a typical dev machine), but the bytecode our main
// classes compile to needs 21+ to run.
tasks.named<Test>("test") {
    useJUnitPlatform()
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}
