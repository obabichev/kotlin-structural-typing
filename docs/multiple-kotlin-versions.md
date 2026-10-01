# Building against several Kotlin versions

The compiler plugin uses internal compiler APIs, so a build made for one Kotlin version can fail on another. The
[Kotlin compiler plugin DevKit](https://github.com/Kotlin/compiler-plugin-template/tree/devkit) compiles the plugin
against each version and packs the results into one artifact, which picks the right one at runtime.

## Why it is worth the complexity

It found a bug that had already shipped. `KtFakeSourceElementKind.PluginGenerated.Default`, used when adding a
supertype to an enum class, does not exist before Kotlin 2.4.20:

```
e: java.lang.NoClassDefFoundError: org/jetbrains/kotlin/KtFakeSourceElementKind$PluginGenerated$Default
```

Release `0.2.0-kotlin-2.4` fails that way on Kotlin 2.4.0 and 2.4.10 for any project where an enum class matches a
`@Structural` interface, although its notes claim both versions work. Nothing short of compiling against them says so.

## How it is set up

`settings.gradle.kts` applies `kotlin("compiler.plugin.devkit")` from the DevKit's EAP repository, and
`gradle.properties` decides which versions are built:

```properties
kotlin.compiler.plugin.devkit.minCliVersion=2.4.0
kotlin.compiler.plugin.devkit.includeBetaAndRc=LATEST
kotlin.compiler.plugin.devkit.minIdeaVersion=262
```

That produces eight version groups: 2.4.0, 2.4.10, the 2.4.20 betas and RC, the dev build IntelliJ analyzes with, and
two IntelliJ 262 builds. `./gradlew build` compiles the plugin against all of them and runs the box tests on each.

**Where the compiler API changed, the source sets split.** `versionHierarchy` in the module's build file names the
split and a predicate for the older side, and `SourceElements.kt` has one `actual` per side:

```kotlin
split("2420") { it.major == 2 && it.minor == 4 && it.patch < 20 }
```

**The published artifact is one coordinate.** The umbrella jar carries a copy of the plugin per version under
`META-INF/kotlin/plugin/com.obabichev.structural/versions/`, and the DevKit's entry point loads the one matching the
compiler in use. Users depend on `structural-compiler-plugin` as before.

## What it does not do

It builds and tests against the compiler IntelliJ ships, which is not the same as testing IntelliJ: the editor resolves
declarations on demand through its own frontend, and the DevKit has no runner for that. Every bug this plugin has had
in the editor came from that difference, so those tests live in `structural-intellij-plugin` instead and analyze code
through the Kotlin plugin in a test IDE.

## Costs

- The DevKit is an EAP dependency, pinned to `0.0.3-dev-66e2b55` from a JetBrains EAP repository. Only this build needs
  it; the runtime it contributes is inside the published jar.
- That build predates Kotlin 2.4.20, hence `includeBetaAndRc=LATEST`; see [`known-issues.md`](known-issues.md).
- The plugin's own tests need the plugin and exactly one compiler on a classpath, so they live in
  `structural-compiler-plugin-tests`, which compiles the same sources again for the version it tests against.
- 2.5.0-dev is not built: the DevKit's own test infrastructure fails on it (`IrDiagnosticsHandler`), so
  `useLatestDev` stays off.
