package com.panomc.platform.db

import com.panomc.platform.Main
import com.panomc.platform.UIManager.Companion.isAndroid
import com.panomc.platform.error.DbInstallFailed
import com.panomc.platform.error.PortableDbNotSupportedOs
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.util.OperatingSystem
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MariaDBManager : DisposableBean {
    @Autowired
    private lateinit var vertx: Vertx
    var installProgress: Int = 0
        private set

    private val logger: Logger = LoggerFactory.getLogger(MariaDBManager::class.java)

    // Relocatable MariaDB builds for every OS / architecture, see https://github.com/PanoMC/portable-mariadb.
    // The release is pinned, so a new MariaDB version reaches Pano only through a Pano release.
    // Existing installs move to a new tag on their next start, see upgradeBinaries.
    private val releaseTag = "v11.8.10-r1"
    private val manifestUrl = "https://github.com/PanoMC/portable-mariadb/releases/download/$releaseTag/manifest.json"

    private val currentOs = Main.OPERATING_SYSTEM
    private val isWindows = currentOs == OperatingSystem.WINDOWS

    private val target: String? by lazy { portableTarget() }

    fun isSupported(): Boolean = target != null

    // We will place MariaDB in the "libraries/mariadb" folder
    private val librariesFolder = File("libraries")
    private val mariaDbFolder = File(librariesFolder, "mariadb")
    private val dataFolder = File("data", "db")

    // An upgrade extracts the new release into stagedFolder and keeps the old binaries in backupFolder until it runs.
    private val stagedFolder = File(librariesFolder, "mariadb-new")
    private val backupFolder = File(librariesFolder, "mariadb-old")

    // The portable-mariadb release the binaries in mariaDbFolder come from. Missing on older installs.
    private val releaseFile get() = File(mariaDbFolder, RELEASE_FILE)

    private var process: Process? = null
    private var shutdownHookAdded = false
    private val port = 3307 // Use a different port than default 3306 to avoid conflicts
    private val passwordFile = File(dataFolder, ".root_password")

    private val password: String by lazy {
        if (passwordFile.exists()) {
             passwordFile.readText().trim()
        } else {
             generateRandomPassword()
        }
    }

    // mariadbd refuses to run as root unless it is told to.
    private val runsAsRoot = !isWindows && System.getProperty("user.name") == "root"

    private val confFile = File(mariaDbFolder, if (isWindows) "my.ini" else "my.cnf")

    init {
        if (!librariesFolder.exists()) {
            librariesFolder.mkdirs()
        }
    }

    private fun generateRandomPassword(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#"
        return (1..16).map { chars.random() }.joinToString("")
    }

    private fun binary(vararg names: String, folder: File = mariaDbFolder): File {
        val suffix = if (isWindows) ".exe" else ""
        val files = names.map { File(folder, "bin/$it$suffix") }

        return files.firstOrNull { it.exists() } ?: files.first()
    }

    // Installs made before portable-mariadb (Windows, MariaDB 11.4) only have the old mysql* names.
    private fun serverBinary(folder: File) = binary("mariadbd", "mysqld", folder = folder)
    private val serverBinary get() = serverBinary(mariaDbFolder)
    private val clientBinary get() = binary("mariadb", "mysql")

    fun isInstalled(): Boolean = serverBinary.exists()

    override fun destroy() {
        process?.destroy()
    }

    suspend fun install(): Result = vertx.executeBlocking {
        val target = target ?: throw PortableDbNotSupportedOs()

        if (isInstalled()) {
            return@executeBlocking Successful()
        }

        try {
            fetchPackage(target, mariaDbFolder)

            ensureMyIni()

            // Initialize if mysql folder doesn't exist
            val mysqlDir = File(dataFolder, "mysql")
            if (!dataFolder.exists() || !mysqlDir.exists()) {
                if (dataFolder.exists()) {
                    logger.info("Data folder exists but appears corrupted. Re-initializing.")
                    dataFolder.deleteRecursively()
                }
                dataFolder.mkdirs()
                runInstallDb()
            }

            installProgress = 100
            return@executeBlocking Successful()
        } catch (e: Exception) {
            logger.error("Failed to install MariaDB", e)
            throw DbInstallFailed()
        }
    }.coAwait()

    /** Downloads, verifies and extracts the [releaseTag] package for [target] into [folder], then checks it runs. */
    private fun fetchPackage(target: String, folder: File) {
        logger.info("Downloading Portable MariaDB ($releaseTag) for $target...")

        val manifest = JsonObject(URL(manifestUrl).readText())
        val asset = manifest.getJsonArray("assets")
            .map { it as JsonObject }
            .firstOrNull { it.getString("target") == target }
            ?: throw PortableDbNotSupportedOs()

        val url = asset.getString("url")
        logger.info("URL: $url")

        val isZip = asset.getString("format") == "zip"
        val archiveFile = File(librariesFolder, if (isZip) "mariadb.zip" else "mariadb.tar.gz")

        val sha256 = download(url, archiveFile)

        if (!sha256.equals(asset.getString("sha256"), ignoreCase = true)) {
            archiveFile.delete()
            throw DbInstallFailed("Downloaded MariaDB package does not match its SHA-256")
        }

        installProgress = 99

        logger.info("Download complete. Extracting...")

        if (folder.exists()) {
            folder.deleteRecursively()
        }
        folder.mkdirs()

        try {
            if (isZip) {
                extractZip(archiveFile, folder)
            } else {
                extractTarGz(archiveFile, folder)
            }
        } finally {
            archiveFile.delete()
        }

        logger.info("Extraction complete.")

        if (!isWindows) {
            listOf("bin", "scripts").forEach { dir ->
                File(folder, dir).listFiles()?.forEach { it.setExecutable(true) }
            }
        }

        checkServerRuns(asset, folder)

        File(folder, RELEASE_FILE).writeText(releaseTag)
    }

    /**
     * Replaces binaries from another release (or from before the release file existed) with [releaseTag].
     * Returns the folder that holds the old binaries, or null when nothing changed. Never throws: on any
     * failure the old binaries stay in place.
     */
    private fun upgradeBinaries(): File? {
        val target = target ?: return null

        if (!needsUpgrade(installedRelease(mariaDbFolder), releaseTag)) return null

        logger.info("Updating Portable MariaDB from ${installedRelease(mariaDbFolder) ?: "an older release"} to $releaseTag...")

        return try {
            fetchPackage(target, stagedFolder)
            swapIn(mariaDbFolder, stagedFolder, backupFolder)
            backupFolder
        } catch (e: Exception) {
            logger.warn("Could not update Portable MariaDB, keeping the current version.", e)
            stagedFolder.deleteRecursively()
            null
        }
    }

    /** Lets mariadb-upgrade bring the system tables in data/db up to the new server version. */
    private fun runMariaDbUpgrade() {
        val upgrade = binary("mariadb-upgrade", "mysql_upgrade")

        val (exitCode, output) = try {
            run(
                listOf(
                    upgrade.absolutePath,
                    "--no-defaults",
                    "--host=127.0.0.1",
                    "--port=$port",
                    "--skip-ssl-verify-server-cert",
                    "-u", "root",
                    "-p$password"
                )
            )
        } catch (e: Exception) {
            -1 to (e.message ?: "")
        }

        if (exitCode == 0) {
            logger.info("mariadb-upgrade finished: ${output.trim()}")
        } else {
            logger.warn("mariadb-upgrade failed (exit code $exitCode): ${output.trim()}")
        }
    }

    /** Downloads [url] into [file] while reporting progress, returns the file's SHA-256. */
    private fun download(url: String, file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        URL(url).openConnection().let { connection ->
            val contentLength = connection.contentLengthLong
            connection.getInputStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastDotAt = 0L
                    val dotInterval = 1024 * 1024 // 1 MB

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (contentLength > 0) {
                            installProgress = (totalRead * 100 / contentLength).toInt().coerceAtMost(99)
                        }

                        if (totalRead - lastDotAt >= dotInterval) {
                            print(". ")
                            System.out.flush()
                            lastDotAt = totalRead
                        }
                    }
                }
            }
        }
        println()

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractZip(archiveFile: File, folder: File) {
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                // Strip first component
                val entryName = entry.name.replace("\\", "/").substringAfter("/")
                if (entryName.isEmpty()) continue

                val outFile = File(folder, entryName)

                if (!outFile.canonicalPath.startsWith(folder.canonicalPath + File.separator)) {
                    throw DbInstallFailed("Unexpected path in MariaDB package: ${entry.name}")
                }

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
    }

    // tar keeps the symlinks and file modes of the bundled libraries, it is on every Linux and macOS.
    private fun extractTarGz(archiveFile: File, folder: File) {
        val tarCmd = listOf("tar", "-xzf", archiveFile.absolutePath, "-C", folder.absolutePath, "--strip-components=1")
        val (exitCode, output) = run(tarCmd, folder)
        if (exitCode != 0) {
            logger.error("Tar extraction failed: $output")
            throw DbInstallFailed("Tar extraction failed with code $exitCode")
        }
    }

    /** Fails the install with a readable reason when the package cannot run here, e.g. a too old glibc. */
    private fun checkServerRuns(asset: JsonObject, folder: File) {
        val (exitCode, output) = try {
            run(listOf(serverBinary(folder).absolutePath, "--version"), folder)
        } catch (e: Exception) {
            -1 to (e.message ?: "")
        }

        if (exitCode != 0) {
            val requirement = asset.getString("minGlibc")?.let { " (needs glibc $it or newer)" } ?: ""
            logger.error("MariaDB server cannot run on this system$requirement: $output")
            throw DbInstallFailed("MariaDB server cannot run on this system$requirement")
        }

        logger.info(output.trim())
    }

    private fun run(cmd: List<String>, folder: File = mariaDbFolder): Pair<Int, String> {
        val pb = ProcessBuilder(cmd)
        pb.directory(folder)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()

        return p.waitFor() to output
    }

    // Unix socket paths are limited to 107 bytes, a deep install folder would stop the server from starting.
    private val socketFile: File
        get() {
            val inData = File(dataFolder.absoluteFile, "mariadb.sock")

            return if (inData.path.toByteArray().size <= 100) inData
            else File(System.getProperty("java.io.tmpdir"), "pano-mariadb-$port.sock")
        }

    private fun ensureMyIni() {
        val absDataPath = dataFolder.absolutePath.replace("\\", "/")
        val absBaseDir = mariaDbFolder.absolutePath.replace("\\", "/")

        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        val lines = mutableListOf(
            "[mysqld]",
            "datadir=\"$absDataPath\"",
            "basedir=\"$absBaseDir\"",
            "port=$port",
            "bind-address=127.0.0.1",
            "max_allowed_packet=64M"
        )

        if (isWindows) {
            lines.add("innodb_flush_method=normal")
        } else {
            lines.add("socket=\"${socketFile.absolutePath}\"")
        }

        if (runsAsRoot) {
            lines.add("user=root")
        }

        confFile.writeText(lines.joinToString("\n") + "\n")
    }

    private fun runInstallDb() {
        val installDbCmd = if (isWindows) {
            val installDb = binary("mariadb-install-db", "mysql_install_db")

            mutableListOf(
                installDb.absolutePath,
                "--datadir=${dataFolder.absolutePath.replace("\\", "/")}",
                "--password=$password",
                "--port=$port",
                "--default-user"
            )
        } else {
            // root gets an empty password here, createDefaultDatabase sets the real one.
            mutableListOf(
                File(mariaDbFolder, "scripts/mariadb-install-db").absolutePath,
                "--no-defaults",
                "--basedir=${mariaDbFolder.absolutePath}",
                "--datadir=${dataFolder.absolutePath}",
                "--auth-root-authentication-method=normal",
                "--skip-test-db"
            ).apply {
                if (runsAsRoot) add("--user=root")
            }
        }

        if (!File(installDbCmd.first()).exists()) {
            throw DbInstallFailed("Could not find database installation binary at ${installDbCmd.first()}")
        }

        val pb = ProcessBuilder(installDbCmd)
        pb.directory(mariaDbFolder)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val scanner = Scanner(p.inputStream)
        while (scanner.hasNextLine()) {
            val line = scanner.nextLine()
            logger.info("InstallDB: $line")
        }
        val exitCode = p.waitFor()

        if (exitCode != 0) {
            throw DbInstallFailed("Database initialization failed with code $exitCode")
        }
        passwordFile.writeText(password)
    }

    private fun isPortInUse(port: Int): Boolean {
        return try {
            Socket("127.0.0.1", port).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun start() = vertx.executeBlocking {
        // A stop in the middle of an update can leave only the old binaries behind.
        try {
            restoreInterruptedSwap(mariaDbFolder, backupFolder)
        } catch (e: Exception) {
            logger.warn("Could not restore the previous MariaDB binaries", e)
        }

        if (!isInstalled()) return@executeBlocking

        if (isPortInUse(port)) {
            logger.info("Port $port is already in use. Assuming MariaDB is running.")
            return@executeBlocking
        }

        val oldBinaries = upgradeBinaries()

        if (launch()) {
            if (oldBinaries != null) {
                runMariaDbUpgrade()
                oldBinaries.deleteRecursively()
                logger.info("Portable MariaDB is now on $releaseTag.")
            }
            return@executeBlocking
        }

        if (oldBinaries != null) {
            logger.warn("Portable MariaDB $releaseTag did not start, going back to the previous version.")
            stopAndWait()
            try {
                rollBack(mariaDbFolder, oldBinaries)
                launch()
            } catch (e: Exception) {
                logger.error("Could not restore the previous MariaDB binaries", e)
            }
        }
    }.coAwait()

    private fun stopAndWait() {
        process?.let {
            it.destroy()
            if (!it.waitFor(30, TimeUnit.SECONDS)) it.destroyForcibly().waitFor()
        }
        process = null
    }

    /** Starts the server and waits until it listens, returns whether it does. */
    private fun launch(): Boolean {
        logger.info("Starting Portable MariaDB on port $port...")

        val mysqld = serverBinary

        if (!isWindows) {
            mysqld.setExecutable(true)
        }

        ensureMyIni()

        // --defaults-file has to be the first option.
        val cmd = mutableListOf(
            mysqld.absolutePath,
            "--defaults-file=${confFile.absolutePath.replace("\\", "/")}"
        )

        if (isWindows) {
            cmd.add("--console")
        }

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        process = pb.start()

        // Consume output in a separate thread to prevent blocking
        Thread {
            val s = process?.inputStream
            if (s != null) {
                val scanner = Scanner(s)
                while (scanner.hasNextLine()) {
                    val line = scanner.nextLine()
                    logger.info("MariaDB: $line")
                }
            }
        }.start()

        if (!shutdownHookAdded) {
            shutdownHookAdded = true
            Runtime.getRuntime().addShutdownHook(Thread {
                process?.destroy()
            })
        }

        // Wait for MariaDB to start listening
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 30000) {
            if (process?.isAlive == false) {
                logger.error("MariaDB process died immediately!")
                return false
            }
            if (isPortInUse(port)) {
                logger.info("MariaDB started successfully and is listening on port $port.")
                return true
            }
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                logger.warn("Wait interrupted")
            }
        }

        logger.error("MariaDB failed to start (timeout) on port $port.")
        return false
    }

    fun stop() {
        if (process != null) {
            logger.info("Stopping Portable MariaDB...")
            process?.destroy()
            process = null
        }
    }

    suspend fun createDefaultDatabase() = vertx.executeBlocking {
        if (!isInstalled()) return@executeBlocking

        logger.info("Creating default database 'pano'...")
        val mysql = clientBinary

        if (!isWindows) {
            mysql.setExecutable(true)
        }

        // mariadb-install-db also creates root@127.0.0.1, root@::1 and root@<hostname>, on Unix without a
        // password. Only root@localhost is kept (TCP from 127.0.0.1 resolves to it) and it gets the password.
        val sql = "DELETE FROM mysql.global_priv WHERE (User = 'root' AND Host <> 'localhost') OR User = ''; " +
                "FLUSH PRIVILEGES; " +
                "ALTER USER 'root'@'localhost' IDENTIFIED BY '${password}'; CREATE DATABASE IF NOT EXISTS pano;"

        // The server has a self-signed certificate; the connection is local, so it is not verified.
        fun clientCmd(vararg auth: String) = listOf(
            mysql.absolutePath,
            "--no-defaults",
            "--host=127.0.0.1",
            "--port=$port",
            "--skip-ssl-verify-server-cert",
            "-u", "root",
            *auth,
            "-e", sql
        )

        // Try without password (first run)
        var (exitCode, output) = run(clientCmd())

        if (exitCode != 0) {
             logger.warn("Initial DB config failed (Exit code $exitCode). Output: $output")
             if (output.contains("Access denied", true)) {
                 logger.info("Access denied using empty password. Retrying with generated password...")
                 // Try with password
                 run(clientCmd("-p$password")).let {
                     exitCode = it.first
                     output = it.second
                 }
             }
        }

        if (exitCode != 0) {
            logger.error("Failed to configure database. Exit: $exitCode. Output: $output")
        } else {
            logger.info("Default database checked/created.")
        }
    }.coAwait()

    fun getCredentials(): JsonObject {
        return JsonObject()
            .put("host", "127.0.0.1:$port")
            .put("dbName", "pano")
            .put("username", "root")
            .put("password", password)
            .put("prefix", "pano_")
    }

    companion object {
        internal const val RELEASE_FILE = ".portable-mariadb-release"

        /** The release recorded in [folder], or null when the install predates the release file. */
        internal fun installedRelease(folder: File): String? =
            File(folder, RELEASE_FILE).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

        /** Binaries without a release file, or from another release, are replaced by [wanted]. */
        internal fun needsUpgrade(installed: String?, wanted: String): Boolean = installed != wanted

        /** Moves [current] to [backup] and [staged] to [current]; puts [current] back if the second move fails. */
        internal fun swapIn(current: File, staged: File, backup: File) {
            if (backup.exists()) backup.deleteRecursively()

            Files.move(current.toPath(), backup.toPath())

            try {
                Files.move(staged.toPath(), current.toPath())
            } catch (e: Exception) {
                Files.move(backup.toPath(), current.toPath())
                throw e
            }
        }

        /** Throws away the binaries in [current] and puts [backup] back in their place. */
        internal fun rollBack(current: File, backup: File) {
            if (current.exists() && !current.deleteRecursively()) {
                throw IllegalStateException("Could not remove ${current.path}")
            }

            Files.move(backup.toPath(), current.toPath())
        }

        /** After a stop between the two moves of [swapIn] only [backup] exists, it becomes [current] again. */
        internal fun restoreInterruptedSwap(current: File, backup: File) {
            if (!current.exists() && backup.exists()) {
                Files.move(backup.toPath(), current.toPath())
            }
        }

        /**
         * The portable-mariadb target (`<os>-<arch>`, `linux-musl-<arch>`) for this machine, or null when there is
         * no package for it. Android (Termux) has neither glibc nor musl, so it gets none.
         */
        internal fun portableTarget(
            os: OperatingSystem = Main.OPERATING_SYSTEM,
            osArch: String = System.getProperty("os.arch"),
            root: File = File("/")
        ): String? {
            val arch = when (osArch.lowercase()) {
                "amd64", "x86_64", "x64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                "x86", "i386", "i486", "i586", "i686" -> "x86"
                "arm", "armv7", "armv7l", "armhf", "aarch32" -> "armv7"
                "ppc64le" -> "ppc64le"
                "s390x" -> "s390x"
                "riscv64" -> "riscv64"
                else -> return null
            }

            return when (os) {
                OperatingSystem.WINDOWS -> if (arch in listOf("x64", "arm64", "x86")) "windows-$arch" else null
                OperatingSystem.DARWIN -> if (arch in listOf("x64", "arm64")) "macos-$arch" else null
                OperatingSystem.LINUX -> when {
                    isAndroid(root) -> null
                    isMusl(root) -> if (arch in listOf("x64", "arm64", "x86", "armv7")) "linux-musl-$arch" else null
                    else -> "linux-$arch"
                }
            }
        }

        /** Whether the system C library is musl (Alpine and the like): its loader is there and glibc's is not. */
        internal fun isMusl(root: File = File("/")): Boolean {
            val loaders = listOf("lib", "lib64").flatMap { dir -> File(root, dir).listFiles()?.map { it.name } ?: listOf() }

            return loaders.any { it.startsWith("ld-musl-") } && loaders.none { it.startsWith("ld-linux") }
        }
    }
}
