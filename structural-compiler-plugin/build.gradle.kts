plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)

    testImplementation(project(":structural-annotations"))
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kctfork.core)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
