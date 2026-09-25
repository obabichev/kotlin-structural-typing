plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

// Only the tests touch the compiler plugin API, through kotlin-compile-testing, which builds the stand-in dependencies.
tasks.compileTestKotlin {
    compilerOptions.optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
}

dependencies {
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.kotlin.metadata.jvm)

    // The runtime needs no dependency on the annotation: it only looks for its descriptor in class files.
    testImplementation(project(":structural-annotations"))
    testImplementation(libs.kctfork.core)
    testImplementation(libs.asm.util)
    testImplementation(kotlin("test"))
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "com.obabichev.structural.runtime.StructuralAgent",
            "Main-Class" to "com.obabichev.structural.runtime.StructuralLauncher",
            "Can-Retransform-Classes" to "false",
        )
    }
}

tasks.test {
    useJUnitPlatform()
}
