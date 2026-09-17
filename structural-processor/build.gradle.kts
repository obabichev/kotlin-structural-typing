plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(project(":structural-annotations"))
    testImplementation(libs.kctfork.ksp)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
