plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":structural-annotations"))
    kotlinCompilerPluginClasspath(project(":structural-compiler-plugin"))
}
