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

Between releases the version in `build.gradle.kts` ends with `-SNAPSHOT`. Releasing is its own commit, which contains
nothing but the version change, and carries the tag.

1. Check the build is green and signatures are produced:
   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew build publishToMavenLocal
   ls ~/.m2/repository/com/obabichev/structural/structural-annotations/<version>/
   ```
   Every jar and pom needs an `.asc` next to it, and `gpg --verify <file>.asc <file>` should say "Good signature";
   Central rejects a deployment without them.
2. **The release commit:** drop `-SNAPSHOT` from the version in `build.gradle.kts`, update the version in `README.md`
   and this file if it is mentioned there, and commit it on its own:
   ```bash
   git commit -am "Release <version>"
   git tag -a "release/<version>" -m "Release <version>"
   git push origin main "release/<version>"
   ```
3. Upload, then release it in the [portal](https://central.sonatype.com) after looking at the staged files:
   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew publishToMavenCentral
   ```
   `publishAndReleaseToMavenCentral` skips the manual step. Artifacts appear on Maven Central within about an hour.
4. Attach the IntelliJ plugin to a GitHub release, since it isn't published anywhere else:
   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-intellij-plugin:buildPlugin \
       "-Pstructural.ideaPath=/Applications/IntelliJ IDEA.app"
   gh release create "release/<version>" structural-intellij-plugin/build/distributions/*.zip
   ```
5. **Back to development:** set the next version with `-SNAPSHOT` in `build.gradle.kts` and commit that on its own.

The first release used the tag `v0.1.0-kotlin-2.4.20`; later ones use `release/<version>`.

**Published versions can't be changed or deleted.** Publish a new version instead.
