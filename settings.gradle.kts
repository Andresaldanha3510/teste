pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CentrifugaMais"

include(":protocol")
// The Android app needs the Android SDK; set PROTOCOL_ONLY=1 to build/test
// only the pure-Kotlin protocol module on a machine without it.
if (System.getenv("PROTOCOL_ONLY") == null) {
    include(":app")
}
