# Publishing

Three artifacts are published to Maven Central under `io.github.obabichev.structural`:

| Artifact | Contents |
|---|---|
| `structural-annotations` | The `@Structural` annotation |
| `structural-compiler-plugin` | The K2 compiler plugin |
| `structural-gradle-plugin` | The Gradle plugin, applied as `io.github.obabichev.structural` |

The version names the Kotlin version it works with, e.g. `0.1.0-kotlin-2.4.20`, because the compiler plugin uses
internal compiler APIs. Bump the Kotlin part when moving to a new Kotlin release; see `build.gradle.kts`.

The IntelliJ plugin is not published here. It would go to the JetBrains Marketplace, which is a separate process
(account, review, one build per supported IDE version).

## Trying it locally first

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew publishToMavenLocal
```

Then in another project, with `mavenLocal()` in both `pluginManagement` and `dependencyResolutionManagement`
repositories:

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("io.github.obabichev.structural") version "0.1.0-kotlin-2.4.20"
}
```

The Gradle plugin adds `structural-annotations` to `implementation` and the compiler plugin to the Kotlin compilation,
so nothing else is needed.

## One-time setup for Maven Central

These steps need an account and a signing key, so they can't be scripted here.

1. **Create a Sonatype account** at [central.sonatype.com](https://central.sonatype.com) and **verify the namespace**
   `io.github.obabichev`. For `io.github.*` namespaces, verification is done through the matching GitHub account.
2. **Generate a signing key** and publish the public half, because Maven Central only accepts signed artifacts:
   ```bash
   gpg --full-generate-key
   gpg --list-secret-keys --keyid-format=long          # note the key id
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>           # the value used below
   ```
3. **Put the credentials in `~/.gradle/gradle.properties`**, never in this repository:
   ```properties
   mavenCentralUsername=<user token name from the Central Portal>
   mavenCentralPassword=<user token value>
   signingInMemoryKey=<the exported secret key, without the BEGIN/END lines>
   signingInMemoryKeyPassword=<key passphrase>
   ```
   The build signs artifacts only when `signingInMemoryKey` is set, so local publishing works without a key. See the
   [gradle-maven-publish-plugin docs](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) for other ways
   to provide the key, such as `useGpgCmd()`.

## Publishing a version

1. Set the version in `build.gradle.kts` and commit it.
2. Check the whole build is green: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew build`.
3. Upload and release:
   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew publishAndReleaseToMavenCentral
   ```
   Use `publishToMavenCentral` instead to upload without releasing, and finish the release by hand in the Central
   Portal. Artifacts usually appear on Maven Central within an hour.
4. Tag the release in git: `git tag v<version> && git push origin v<version>`.

**Published versions can't be changed or deleted.** Publish a new version instead.
