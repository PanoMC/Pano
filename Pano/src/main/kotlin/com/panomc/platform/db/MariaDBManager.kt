package com.panomc.platform.db

import com.panomc.platform.Main
import com.panomc.platform.error.DbInstallFailed
import com.panomc.platform.error.PortableDbNotSupportedOs
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.util.Architecture
import com.panomc.platform.util.OperatingSystem
import io.vertx.core.json.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.zip.ZipFile

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MariaDBManager : DisposableBean {
    private val logger: Logger = LoggerFactory.getLogger(MariaDBManager::class.java)

    // Using MariaDB 11.4.2 GA (Stable)
    private val mariaDbVersion = "11.4.2"

    private val currentOs = Main.OPERATING_SYSTEM
    private val currentArch = Main.ARCHITECTURE

    fun isSupported(): Boolean {
        return when (currentOs) {
            OperatingSystem.WINDOWS -> currentArch == Architecture.X64 || currentArch == Architecture.AARCH64
            OperatingSystem.LINUX -> currentArch == Architecture.X64 || currentArch == Architecture.AARCH64
            else -> false
        }
    }

    private val downloadUrl: String
        get() {
            if (!isSupported()) {
                throw PortableDbNotSupportedOs()
            }
            val base = "https://archive.mariadb.org/mariadb-$mariaDbVersion"
            return when (currentOs) {
                OperatingSystem.WINDOWS -> "$base/winx64-packages/mariadb-$mariaDbVersion-winx64.zip"
                OperatingSystem.LINUX -> {
                    val arch = when (currentArch) {
                        Architecture.X64 -> "x86_64"
                        Architecture.AARCH64 -> "aarch64"
                        else -> throw PortableDbNotSupportedOs()
                    }
                    "$base/bintar-linux-systemd-$arch/mariadb-$mariaDbVersion-linux-systemd-$arch.tar.gz"
                }
                else -> throw PortableDbNotSupportedOs()
            }
        }
    
    // We will place MariaDB in the "libraries/mariadb" folder
    private val librariesFolder = File("libraries")
    private val mariaDbFolder = File(librariesFolder, "mariadb")
    private val dataFolder = File("data", "db")
    
    private var process: Process? = null
    private val port = 3307 // Use a different port than default 3306 to avoid conflicts
    private val passwordFile = File(dataFolder, ".root_password")
    
    private val password: String by lazy {
        if (passwordFile.exists()) {
             passwordFile.readText().trim()
        } else {
             generateRandomPassword()
        }
    }

    init {
        if (!librariesFolder.exists()) {
            librariesFolder.mkdirs()
        }
    }

    private fun generateRandomPassword(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#"
        return (1..16).map { chars.random() }.joinToString("")
    }

    fun isInstalled(): Boolean {
        if (!mariaDbFolder.exists()) return false
        val binaryName = if (currentOs == OperatingSystem.WINDOWS) "bin/mysqld.exe" else "bin/mysqld"
        return File(mariaDbFolder, binaryName).exists()
    }

    override fun destroy() {
        stop()
    }

    fun install(): Result {
        if (!isSupported()) {
            throw PortableDbNotSupportedOs()
        }

        if (isInstalled()) {
            return Successful()
        }

        val url = downloadUrl
        logger.info("Downloading Portable MariaDB $mariaDbVersion for $currentOs ($currentArch)...")
        logger.info("URL: $url")

        try {
            val isZip = url.endsWith(".zip")
            val archiveName = if (isZip) "mariadb.zip" else "mariadb.tar.gz"
            val archiveFile = File(librariesFolder, archiveName)
            
            // Download
            val website = URL(url)
            val rbc = Channels.newChannel(website.openStream())
            val fos = FileOutputStream(archiveFile)
            fos.channel.transferFrom(rbc, 0, Long.MAX_VALUE)
            fos.close()
            rbc.close()

            logger.info("Download complete. Extracting...")
            
            if (mariaDbFolder.exists()) {
                mariaDbFolder.deleteRecursively()
            }
            mariaDbFolder.mkdirs()

            if (isZip) {
                // Extract Zip
                ZipFile(archiveFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        // Strip first component
                        val entryName = entry.name.substringAfter("/")
                        if (entryName.isEmpty()) continue

                        val outFile = File(mariaDbFolder, entryName)
                        
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                Files.copy(input, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
            } else {
                // Extract Tar.gz using command line
                // Note: User must have 'tar' installed (standard on Linux/Mac)
                val tarCmd = listOf("tar", "-xzf", archiveFile.absolutePath, "-C", mariaDbFolder.absolutePath, "--strip-components=1")
                val pb = ProcessBuilder(tarCmd)
                pb.redirectErrorStream(true)
                val p = pb.start()
                val output = p.inputStream.bufferedReader().readText()
                val exitCode = p.waitFor()
                if (exitCode != 0) {
                    logger.error("Tar extraction failed: $output")
                    throw DbInstallFailed("Tar extraction failed with code $exitCode")
                }
            }
            
            archiveFile.delete()
            
            logger.info("Extraction complete.")
            
            // Initialize data directory
            val mysqlDir = File(dataFolder, "mysql")
            if (!dataFolder.exists() || !mysqlDir.exists()) {
                if (dataFolder.exists()) {
                    logger.info("Data folder exists but appears corrupted (missing 'mysql' folder). Re-initializing.")
                    dataFolder.deleteRecursively()
                }
                dataFolder.mkdirs()

                // Create my.ini (or my.cnf for linux) FIRST
                val confName = if (currentOs == OperatingSystem.WINDOWS) "my.ini" else "my.cnf"
                val myConf = File(mariaDbFolder, confName)
                val absDataPath = dataFolder.absolutePath.replace("\\", "/")
                val absBaseDir = mariaDbFolder.absolutePath.replace("\\", "/")

                myConf.writeText("""
                    [mysqld]
                    datadir=$absDataPath
                    basedir=$absBaseDir
                    port=$port
                    bind-address=127.0.0.1
                    max_allowed_packet=64M
                """.trimIndent())
                
                logger.info("Initializing database data directory...")
                
                val installDbBin = if (currentOs == OperatingSystem.WINDOWS) {
                     File(mariaDbFolder, "bin/mysql_install_db.exe")
                } else {
                     // Try mariadb-install-db first, then mysql_install_db
                     val mdb = File(mariaDbFolder, "bin/mariadb-install-db")
                     if (mdb.exists()) mdb else File(mariaDbFolder, "scripts/mysql_install_db")
                }
                
                // Final check for binary existence
                val finalInstallBin = if (installDbBin.exists()) installDbBin else {
                     if (currentOs != OperatingSystem.WINDOWS) {
                         val alt = File(mariaDbFolder, "bin/mysql_install_db")
                         if (alt.exists()) alt else installDbBin
                     } else installDbBin
                }
                
                if (!finalInstallBin.exists()) {
                     throw DbInstallFailed("Could not find database installation binary at ${finalInstallBin.absolutePath}")
                }

                if (currentOs != OperatingSystem.WINDOWS) {
                    finalInstallBin.setExecutable(true)
                    File(mariaDbFolder, "bin/mysqld").setExecutable(true)
                }

                val installDbCmd = mutableListOf(
                    finalInstallBin.absolutePath,
                    "--datadir=${dataFolder.absolutePath}"
                )

                if (currentOs == OperatingSystem.WINDOWS) {
                    // Windows version of mysql_install_db.exe has different flags
                    installDbCmd.add("--password=$password")
                    installDbCmd.add("--port=$port")
                    installDbCmd.add("--default-user")
                } else {
                    installDbCmd.add(1, "--defaults-file=${myConf.absolutePath}")
                    installDbCmd.add("--basedir=${mariaDbFolder.absolutePath}")
                }

                val pb = ProcessBuilder(installDbCmd)
                pb.directory(mariaDbFolder) // Set working directory to basedir for script execution
                pb.redirectErrorStream(true)
                val p = pb.start()
                val scanner = Scanner(p.inputStream)
                while (scanner.hasNextLine()) {
                    val line = scanner.nextLine()
                    logger.info("InstallDB: $line")
                }
                val exitCode = p.waitFor()

                if (exitCode != 0) {
                     logger.error("mysql_install_db failed with exit code $exitCode")
                     throw DbInstallFailed("Database initialization failed with code $exitCode")
                }
                
                // Save password as we initialized a fresh DB
                passwordFile.writeText(password)
            }

            return Successful()
        } catch (e: Exception) {
            logger.error("Failed to install MariaDB", e)
            mariaDbFolder.deleteRecursively()
            throw DbInstallFailed()
        }
    }

    private fun isPortInUse(port: Int): Boolean {
        return try {
            Socket("127.0.0.1", port).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun start() {
        if (!isInstalled()) return
        
        if (isPortInUse(port)) {
            logger.info("Port $port is already in use. Assuming MariaDB is running.")
            return
        }

        logger.info("Starting Portable MariaDB on port $port...")
        
        val binName = if (currentOs == OperatingSystem.WINDOWS) "bin/mysqld.exe" else "bin/mysqld"
        val mysqld = File(mariaDbFolder, binName)
        
        val confName = if (currentOs == OperatingSystem.WINDOWS) "my.ini" else "my.cnf"
        val defaultsFile = File(mariaDbFolder, confName)
        
        if (currentOs != OperatingSystem.WINDOWS) {
            mysqld.setExecutable(true)
        }

        val cmd = listOf(
            mysqld.absolutePath,
            "--defaults-file=${defaultsFile.absolutePath}",
            "--console"
        )
        
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        process = pb.start()
        
        // Consume output in a separate thread to prevent blocking
        Thread {
            val scanner = Scanner(process!!.inputStream)
            while (scanner.hasNextLine()) {
                val line = scanner.nextLine()
                logger.info("MariaDB: $line") 
            }
        }.start()
        
        Runtime.getRuntime().addShutdownHook(Thread {
             stop()
        })

        // Wait for MariaDB to start listening
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 30000) {
            if (!process!!.isAlive) {
                logger.error("MariaDB process died immediately!")
                return
            }
            if (isPortInUse(port)) {
                logger.info("MariaDB started successfully and is listening on port $port.")
                return
            }
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                logger.warn("Wait interrupted")
            }
        }
        
        logger.error("MariaDB failed to start (timeout) on port $port.")
    }
    
    fun stop() {
         logger.info("Stopping Portable MariaDB...")
         process?.destroy()
    }

    fun createDefaultDatabase() {
        if (!isInstalled()) return

        logger.info("Creating default database 'pano'...")
        val binName = if (currentOs == OperatingSystem.WINDOWS) "bin/mysql.exe" else "bin/mysql"
        val mysql = File(mariaDbFolder, binName)
        
        if (currentOs != OperatingSystem.WINDOWS) {
            mysql.setExecutable(true)
        }

        val sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '${password}'; CREATE DATABASE IF NOT EXISTS pano;"

        // Try without password (first run)
        var cmd = listOf(
            mysql.absolutePath,
            "--port=$port",
            "-u", "root",
            "-e", sql
        )

        var pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        var p = pb.start()
        
        var output = p.inputStream.bufferedReader().readText()
        var exitCode = p.waitFor()

        if (exitCode != 0) {
             logger.warn("Initial DB config failed (Exit code $exitCode). Output: $output")
             if (output.contains("Access denied", true)) {
                 logger.info("Access denied using empty password. Retrying with generated password...")
                 // Try with password
                 cmd = listOf(
                    mysql.absolutePath,
                    "--port=$port",
                    "-u", "root",
                    "-p$password",
                    "-e", sql
                 )
                 pb = ProcessBuilder(cmd)
                 pb.redirectErrorStream(true)
                 p = pb.start()
                 output = p.inputStream.bufferedReader().readText()
                 exitCode = p.waitFor()
             }
        }
        
        if (exitCode != 0) {
            logger.error("Failed to configure database. Exit: $exitCode. Output: $output")
            // Throw? Or assume it might be okay? 
            // If we throw, user cannot proceed. If we silently fail, they might proceed with fallback or manual fix.
            // But usually this means we cannot connect.
        } else {
            logger.info("Default database checked/created.")
        }
    }

    fun getCredentials(): JsonObject {
        return JsonObject()
            .put("host", "127.0.0.1:$port")
            .put("dbName", "pano")
            .put("username", "root")
            .put("password", password) 
            .put("prefix", "pano_")
    }
}
