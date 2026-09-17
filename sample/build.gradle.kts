plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":structural-annotations"))
    kotlinCompilerPluginClasspath(project(":structural-compiler-plugin"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
