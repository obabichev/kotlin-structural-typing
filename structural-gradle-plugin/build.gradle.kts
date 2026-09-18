plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)
}

// The plugin refers to the published artifacts, so it needs their coordinates.
val generateCoordinates = tasks.register("generateCoordinates") {
    val directory = layout.buildDirectory.dir("generated/coordinates")
    val artifactGroup = project.group.toString()
    val artifactVersion = project.version.toString()
    outputs.dir(directory)
    doLast {
        val file = directory.get().file("com/obabichev/structural/gradle/Coordinates.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.obabichev.structural.gradle

            internal const val ARTIFACT_GROUP: String = "$artifactGroup"
            internal const val ARTIFACT_VERSION: String = "$artifactVersion"
            """.trimIndent() + "\n",
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateCoordinates)
}

gradlePlugin {
    plugins {
        create("structural") {
            id = "com.obabichev.structural"
            implementationClass = "com.obabichev.structural.gradle.StructuralGradlePlugin"
            displayName = "Structural typing for Kotlin"
            description = "Lets classes whose members match a @Structural interface be used as that interface."
        }
    }
}
