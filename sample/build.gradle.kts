plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // What the Gradle plugin passes for a real project: the @Structural interfaces published by dependencies.
        // This module wires the compiler plugin directly instead of applying that plugin, so it names them itself.
        // IntelliJ reads compiler plugin options from the Gradle import; it hands plugins no classpath to read.
        freeCompilerArgs.addAll("-P", "plugin:com.obabichev.structural:interface=com/example/shapes/Sized")
    }
}

dependencies {
    implementation(project(":structural-annotations"))
    implementation(project(":sample-library"))
    kotlinCompilerPluginClasspath(project(":structural-compiler-plugin"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
