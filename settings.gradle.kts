rootProject.name = "Pano"


include(":Pano")
include("plugins")

// Include all subprojects under the plugins/ folder
File("plugins").listFiles()?.filter {
    it.isDirectory && (File(it, "build.gradle.kts").exists() || File(it, "build.gradle").exists())
}?.forEach { subproject ->
    include("plugins:" + subproject.name)
}
