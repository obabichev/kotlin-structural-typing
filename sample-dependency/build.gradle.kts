// A plain module, deliberately compiled WITHOUT the structural plugin: it stands in for a library on the classpath
// whose class files nobody can change. structural-runtime adds the interfaces to its classes as they are loaded.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}
