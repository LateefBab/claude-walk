// AGP 8.2.2 + Kotlin 1.9.22 + Gradle 8.9 (wrapper), built with JDK 17.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// Optional: build somewhere other than android/app/build.
//
// Set `claudewalk.buildDir` in android/local.properties (per-machine, never
// committed) to move build output elsewhere. Useful when the checkout lives in a
// cloud-synced folder (OneDrive, Dropbox, iCloud), where the sync client locks or
// removes intermediates while Gradle is using them. Use an absolute path, with
// forward slashes on Windows:
//
//     claudewalk.buildDir=C:/Users/you/AppData/Local/ClaudeWalkBuild
//
// local.properties is not exposed as Gradle properties, so it is read directly.
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
localProps.getProperty("claudewalk.buildDir")?.trim()?.takeIf { it.isNotEmpty() }?.let { dir ->
    subprojects {
        layout.buildDirectory.set(File(dir, name))
    }
}
