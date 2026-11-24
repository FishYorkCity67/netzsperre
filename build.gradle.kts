plugins {
    id("com.android.application") version "8.13.0" apply false
}

// Build-Ausgaben liegen getrennt vom Quellcode.
val externalBuildRoot: File? = System.getenv("LOCALAPPDATA")?.let { File(it, "NetzSperreBuild") }
allprojects {
    if (externalBuildRoot != null) {
        layout.buildDirectory.set(File(externalBuildRoot, project.name))
    }
}
