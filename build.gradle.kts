buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (System.getenv("PROTOCOL_ONLY") == null) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
