plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":structural-annotations"))
    ksp(project(":structural-processor"))
    testImplementation(kotlin("test"))
}

ksp {
    arg("structural.packages", "com.example.model")
}

tasks.test {
    useJUnitPlatform()
}
