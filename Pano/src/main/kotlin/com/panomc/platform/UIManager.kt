package com.panomc.platform

import com.panomc.platform.util.OperatingSystem
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.net.URL
import java.nio.file.*
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.name
import kotlin.system.exitProcess

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class UIManager(
    private val logger: Logger
) {
    private val themesFolderPath = System.getProperty("pano.themesFolder", "themes")
    private val librariesFolderPath = System.getProperty("pano.librariesFolder", "libraries")
    private val setupUIFolderPath = System.getProperty("pano.setupUIFolder", "setup-ui")
    private val panelUIFolderPath = System.getProperty("pano.panelUIFolder", "panel-ui")
    private val defaultThemeFolderPath = themesFolderPath + File.separator + "vanilla-theme"

    private val themesFolder = File(themesFolderPath)
    private val librariesFolder = File(librariesFolderPath)
    private val setupUIFolder = File(setupUIFolderPath)
    private val panelUIFolder = File(panelUIFolderPath)
    private val defaultThemeFolder = File(defaultThemeFolderPath)

    private val githubUrl = "https://github.com"

    private val bunVersion = "bun-v1.2.0"
    private val bunZipFileName by lazy {
        "bun-${Main.OPERATING_SYSTEM.name.lowercase()}-${Main.ARCHITECTURE.name.lowercase()}"
    }
    private val bunFileName by lazy {
        val suffix = if (Main.OPERATING_SYSTEM == OperatingSystem.WINDOWS) ".exe" else ""

        bunVersion + suffix
    }
    private val bunFilePath by lazy {
        librariesFolder.absolutePath + File.separator + bunFileName
    }

    private fun deleteOldBunRuntimes() {
        // Delete existing Bun files
        librariesFolder.listFiles { file ->
            file.name.startsWith("bun")
        }?.forEach { it.delete() }
    }

    //    Example: https://github.com/oven-sh/bun/releases/download/bun-v1.2.0/bun-darwin-x64-baseline-profile.zip
    private fun downloadBunRuntime() {
        val fullUrl = "$githubUrl/oven-sh/bun/releases/download/$bunVersion/$bunZipFileName.zip"

        try {
            // Download the zip file
            val zipFile = File(librariesFolder, "$bunZipFileName.zip")

            URL(fullUrl).openStream().use { input ->
                Files.copy(input, zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            logger.info("Download complete.")
            logger.info("Extracting...")

            // Extract the zip file
            ZipFile(zipFile).use { zip ->
                val osSpecificBinaryName = when (Main.OPERATING_SYSTEM) {
                    OperatingSystem.WINDOWS -> "bun.exe"
                    else -> "bun"
                }

                val entries = zip.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    if (entry.name.endsWith(osSpecificBinaryName)) {
                        val targetFile = File(librariesFolder, bunFileName)

                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }

                        targetFile.setExecutable(true)

                        break
                    }
                }
            }

            // Clean up the zip file
            zipFile.delete()

            logger.info("Done.")
        } catch (e: Exception) {
            logger.error("Couldn't download Bun runtime: {}", e.message)
            exitProcess(1)
        }
    }

    private fun unzipUIFiles(uiName: String, targetDir: File) {
        val resourceDirUri = ClassLoader.getSystemClassLoader().getResource("UIFiles")?.toURI()
        val dirPath = try {
            Paths.get(resourceDirUri)
        } catch (e: FileSystemNotFoundException) {
            // If this is thrown, then it means that we are running the JAR directly (example: not from an IDE)
            val env = mutableMapOf<String, String>()
            FileSystems.newFileSystem(resourceDirUri, env).getPath("UIFiles")
        }

        // Find the ZIP file matching the pattern setup-ui-*.zip
        val optionalZipFile = Files.list(dirPath).filter { file ->
            file.name.startsWith("$uiName-") && file.name.endsWith(".zip")
        }.findFirst()

        if (!optionalZipFile.isPresent) {
            logger.error("No file matching $uiName-*.zip was found!")

            exitProcess(1)
        }

        val zipFile =
            ClassLoader.getSystemClassLoader().getResource("UIFiles/" + optionalZipFile.get().name).openStream()

        logger.info("Found file: ${optionalZipFile.get().name}")

        // Extract the ZIP file
        ZipInputStream(zipFile).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile.mkdirs()
                    outFile.outputStream().use { output ->
                        zipStream.copyTo(output)
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }

        logger.info("File successfully extracted to: ${targetDir.absolutePath}")
    }

    internal fun init() {
        if (!librariesFolder.exists()) {
            librariesFolder.mkdirs()
        }

        if (!setupUIFolder.exists()) {
            logger.warn("Setup UI not found, installing...")

            unzipUIFiles("setup-ui", setupUIFolder)
        }

        if (!panelUIFolder.exists()) {
            logger.warn("Panel UI not found, installing...")

            unzipUIFiles("panel-ui", panelUIFolder)
        }

        if (!defaultThemeFolder.exists()) {
            logger.warn("Default Vanilla Theme not found, installing...")

            unzipUIFiles("vanilla-theme", defaultThemeFolder)
        }

        logger.info("Verifying Bun runtime...")

        if (!File(bunFilePath).exists()) {
            logger.warn("Required Bun runtime is not found.")
            logger.warn("Downloading Bun runtime, may take a while...")

            deleteOldBunRuntimes()

            downloadBunRuntime()
        }
    }
}