plugins {
    kotlin("jvm")
}

val pluginsDir: File? by rootProject.extra

// Mirrors the platform detection in each plugin's own build.gradle.kts so that
// devBuildUI reuses the exact bun binary its installBun task downloads into
// <plugin>/build/bun/<platform>/.
val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase()

val isWindows = os.contains("win")
val isMac = os.contains("mac")
val isLinux = os.contains("nix") || os.contains("nux") || os.contains("linux")

val isAarch64 = arch.contains("aarch64") || arch.contains("arm64")
val isX64 = arch.contains("x86_64") || arch.contains("amd64")

val bunPlatform = when {
    isWindows && isX64 -> "bun-windows-x64"
    isMac && isX64 -> "bun-darwin-x64"
    isMac && isAarch64 -> "bun-darwin-aarch64"
    isLinux && isX64 -> "bun-linux-x64"
    isLinux && isAarch64 -> "bun-linux-aarch64"
    else -> throw RuntimeException("Unsupported OS or Architecture")
}

fun bunBinOf(project: Project) = File(
    project.layout.buildDirectory.asFile.get(),
    "bun/$bunPlatform/" + if (isWindows) "bun.exe" else "bun"
)

tasks.register("copyJars") {
    pluginsDir?.let {
        doLast {
            if (!it.exists()) {
                it.mkdir()
            }

            file(System.getProperty("user.dir") + "/plugins")
                .listFiles()
                ?.filter { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
                ?.forEach { file ->
                    val destinationFile = File(it, file.name)
                    file.copyTo(destinationFile, overwrite = true)
                }
        }
    }
}

tasks {
    build {
        dependsOn("copyJars")
    }

    clean {}
}

gradle.projectsEvaluated {
    val pluginBuildTasks = subprojects.mapNotNull { it.tasks.findByName("build") }
    val pluginCleanTasks = subprojects.mapNotNull { it.tasks.findByName("clean") }

    pluginBuildTasks.zipWithNext().forEach { (previous, current) ->
        current.mustRunAfter(previous)
    }

    tasks.named("build") {
        dependsOn(pluginBuildTasks)
    }

    tasks.named("clean") {
        dependsOn(pluginCleanTasks)
    }

    // One-shot dev UI builds: each plugin's `bun run dev` equivalent
    // (DEV=true rollup -c) WITHOUT --watch, so rollup builds the dev UI into
    // src/main/resources/plugin-ui/{client,server} once and exits when done.
    val devBuildUITasks = subprojects.mapNotNull { sub ->
        if (!sub.file("rollup.config.js").exists() || sub.tasks.findByName("installBun") == null) {
            return@mapNotNull null
        }

        val bunBin = bunBinOf(sub)

        val installUIDependencies = sub.tasks.register("installUIDependencies", Exec::class.java) {
            dependsOn(sub.tasks.named("installBun"))
            workingDir = sub.projectDir
            commandLine(bunBin.absolutePath, "install")
            onlyIf { !sub.file("node_modules").exists() }
        }

        sub.tasks.register("devBuildUI", Exec::class.java) {
            group = "pano"
            description = "Builds the plugin UI once in dev mode (bun dev without --watch) and exits."
            dependsOn(installUIDependencies)
            workingDir = sub.projectDir
            environment("DEV", "true")
            commandLine(bunBin.absolutePath, "x", "rollup", "-c")
        }
    }

    tasks.register("devBuildUIs") {
        group = "pano"
        description = "Builds every plugin UI once in dev mode (bun dev without --watch); exits when all are done."
        dependsOn(devBuildUITasks)
    }
}