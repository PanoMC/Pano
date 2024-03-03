import org.jetbrains.kotlin.incremental.createDirectory

plugins {
    kotlin("jvm")
}

val pluginsDir: File? by rootProject.extra

tasks.register("copyJars") {
    pluginsDir?.let {
        doLast {
            if (!it.exists()) {
                it.createDirectory()
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
        dependsOn(subprojects.map { it.tasks.build })
    }

    clean {
        dependsOn(subprojects.map { it.tasks.named("clean") })
    }
}