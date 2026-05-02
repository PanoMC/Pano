plugins {
    kotlin("jvm")
}

val pluginsDir: File? by rootProject.extra

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
}