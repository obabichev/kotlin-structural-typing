import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.intellij.platform) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Published artifacts share these coordinates. The version names the Kotlin version, because the compiler plugin uses
// internal compiler APIs and only works with that one.
allprojects {
    group = "com.obabichev.structural"
    version = "0.1.0-kotlin-2.4.20"
}

// Artifacts published to Maven Central: the annotation, the compiler plugin and the Gradle plugin.
// Signing needs a key, so it is only configured when one is available (see docs/publishing.md).
val publishedModules = mapOf(
    "structural-annotations" to "Structural typing annotations",
    "structural-compiler-plugin" to "Structural typing Kotlin compiler plugin",
    "structural-gradle-plugin" to "Structural typing Gradle plugin",
)

subprojects {
    val displayName = publishedModules[name] ?: return@subprojects
    apply(plugin = "com.vanniktech.maven.publish")
    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        // Maven Central requires signatures; local publishing works without a key (see docs/publishing.md).
        val gnupgKey = providers.gradleProperty("signing.gnupg.keyName")
        val hasSigningKey = gnupgKey.isPresent ||
            listOf("signingInMemoryKey", "signing.keyId").any { providers.gradleProperty(it).isPresent }
        if (hasSigningKey) {
            // A key from the GnuPG keyring is used through the gpg command; the other forms are read by Gradle itself.
            if (gnupgKey.isPresent) {
                apply(plugin = "signing")
                extensions.configure<SigningExtension> { useGpgCmd() }
            }
            signAllPublications()
        }
        pom {
            this.name = displayName
            description = "Structural typing for Kotlin: classes whose members match a @Structural interface can be " +
                "used as that interface, without declaring it."
            inceptionYear = "2026"
            url = "https://github.com/obabichev/kotlin-structural-typing"
            licenses {
                license {
                    this.name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
            developers {
                developer {
                    id = "obabichev"
                    this.name = "Oleg Babichev"
                    url = "https://github.com/obabichev"
                }
            }
            scm {
                url = "https://github.com/obabichev/kotlin-structural-typing"
                connection = "scm:git:https://github.com/obabichev/kotlin-structural-typing.git"
                developerConnection = "scm:git:ssh://git@github.com/obabichev/kotlin-structural-typing.git"
            }
        }
    }
}
