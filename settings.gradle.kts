pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/compiler-plugin-dev-kit/eap")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    kotlin("compiler.plugin.devkit") version "0.0.3-dev-66e2b55"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/compiler-plugin-dev-kit/eap")
    }
}

rootProject.name = "kotlin-structural-typing"

include(
    ":structural-annotations",
    ":structural-compiler-plugin",
    ":structural-gradle-plugin",
    ":structural-intellij-plugin",
    ":sample",
)
