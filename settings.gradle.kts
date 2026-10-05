rootProject.name = "Pano"


include(":Pano")
include("plugins")

// Include all subprojects under the plugins/ folder. A directory without its own build file is a
// container: its direct children that hold a build file are included as plugins:<container>:<child>.
fun File.hasBuildFile() = File(this, "build.gradle.kts").exists() || File(this, "build.gradle").exists()

fun File.isProjectDir() = isDirectory && !name.startsWith(".") && name != "build"

File("plugins").listFiles()?.filter { it.isProjectDir() }?.sortedBy { it.name }?.forEach { dir ->
    if (dir.hasBuildFile()) {
        include("plugins:" + dir.name)
    } else {
        dir.listFiles()?.filter { it.isProjectDir() && it.hasBuildFile() }?.sortedBy { it.name }?.forEach { child ->
            include("plugins:" + dir.name + ":" + child.name)
        }
    }
}

include("Updater")
include("Node")
