# Publishing

Three artifacts are published to Maven Central under `com.obabichev.structural`, inside the verified namespace
`com.obabichev`:

| Artifact | Contents |
|---|---|
| `structural-annotations` | The `@Structural` annotation |
| `structural-compiler-plugin` | The K2 compiler plugin |
| `structural-gradle-plugin` | The Gradle plugin, applied as `com.obabichev.structural` |

The version names the Kotlin version it works with, e.g. `0.2.0-kotlin-2.4.20`, because the compiler plugin uses
internal compiler APIs. Bump the Kotlin part when moving to a new Kotlin release; see `build.gradle.kts`.

The IntelliJ plugin is not on Maven Central. Its zip is attached to the matching
[GitHub release](https://github.com/obabichev/kotlin-structural-typing/releases), built with
`./gradlew :structural-intellij-plugin:buildPlugin`. The JetBrains Marketplace would be the next step, and is a separate
process (account, review, one build per supported IDE version).

## Trying it locally first

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew publishToMavenLocal
```

Then in another project, with `mavenLocal()` in both `pluginManagement` and `dependencyResolutionManagement`
repositories:

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("com.obabichev.structural") version "0.2.0-kotlin-2.4.20"
}
```

The Gradle plugin adds `structural-annotations` to `implementation` and the compiler plugin to the Kotlin compilation,
so nothing else is needed.

## One-time setup for Maven Central

These steps need an account and a signing key, so they can't be scripted here.

1. **Sign in** at [central.sonatype.com](https://central.sonatype.com). The namespace `com.obabichev` is already
   verified; *Namespaces* lists it and *Deployments* shows past uploads. Publishing needs a **user token** from
   *Account → Generate User Token*, not the login password.
2. **Generate a signing key** and publish the public half, because Maven Central only accepts signed artifacts.
   GnuPG isn't installed on the development machine yet (`brew install gnupg`):
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

   # one way to provide the key: the ASCII-armored secret key, BEGIN/END lines removed, newlines kept as \n
   signingInMemoryKey=<exported secret key>
   signingInMemoryKeyPassword=<key passphrase>

   # or, with the key in the local GnuPG keyring instead:
   # signing.gnupg.keyName=<KEY_ID>
   # signing.gnupg.passphrase=<key passphrase>
   ```
   Without any of these, `publishAndReleaseToMavenCentral` fails with `mavenCentralUsername not found`. Artifacts are
   signed when `signingInMemoryKey`, `signing.keyId` or `signing.gnupg.keyName` is set, so local publishing keeps
   working without a key. With `signing.gnupg.keyName` the build signs through the `gpg` command, which is what that
   key form requires. The
   [gradle-maven-publish-plugin docs](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) describe the
   key formats in detail.

   For a one-off run without storing anything, the same values work as environment variables:
   ```bash
   ORG_GRADLE_PROJECT_mavenCentralUsername=... ORG_GRADLE_PROJECT_mavenCentralPassword=... \
     JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew publishAndReleaseToMavenCentral
   ```

## Publishing a version

1. Check signatures are produced: `./gradlew publishToMavenLocal`, then look for `.asc` files next to the jars in
   `~/.m2/repository/com/obabichev/structural/...`. Central rejects a deployment without them, and
   `gpg --verify <file>.asc <file>` should say "Good signature".
2. Set the version in `build.gradle.kts` and commit it.
3. Check the whole build is green: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew build`.
4. Upload and release:
   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew publishAndReleaseToMavenCentral
   ```
   Use `publishToMavenCentral` instead to upload without releasing, and finish the release by hand in the Central
   Portal. Artifacts usually appear on Maven Central within an hour.
5. Tag the release in git and publish a GitHub release with the IntelliJ plugin zip attached:
   ```bash
   git tag v<version> && git push origin v<version>
   gh release create v<version> structural-intellij-plugin/build/distributions/*.zip
   ```

**Published versions can't be changed or deleted.** Publish a new version instead.
