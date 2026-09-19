# Spike: moving the compiler plugin to the Kotlin Compiler DevKit

Experiment on the `devkit-migration` branch, answering whether the
[Kotlin compiler plugin DevKit](https://github.com/Kotlin/compiler-plugin-template/tree/devkit) is worth adopting.
**It is.** The plugin builds and passes tests against several Kotlin versions, including the compilers IntelliJ ships,
with one small compatibility shim.

## What was done

- `settings.gradle.kts` applies `kotlin("compiler.plugin.devkit")` from the DevKit EAP repository
  (`packages.jetbrains.team/maven/p/compiler-plugin-dev-kit/eap`), which resolved without credentials.
- `structural-compiler-plugin` uses `pluginDevKit("compiler-plugin")`; its sources moved to `src/commonMain`.
- `StructuralComponentRegistrar` implements the DevKit's `DevKitComponentRegistrar`, and
  `StructuralCommandLineProcessor` its `DevKitCLP`. The DevKit generates the per-version entry points and the
  `META-INF/services` files, which were deleted.
- Test generation entry points live in `src/generateTests`, `src/generateTestsCli`, `src/generateTestsIde`, with runners
  in `src/commonTestFixtures` and test data in `src/commonTest/data`.

## Results

**Several compiler versions from one source set.** `gradle.properties` decides which versions are built and tested:

```properties
kotlin.compiler.plugin.devkit.minCliVersion=2.4.20
kotlin.compiler.plugin.devkit.minIdeaVersion=262
kotlin.compiler.plugin.devkit.includeIdeaRc=true
kotlin.compiler.plugin.devkit.includeIdeaEap=true
```

That produced four test tasks, all green with our plugin unchanged:

| Task | Compiler |
|---|---|
| `k2420Test` | Kotlin 2.4.20 |
| `k2420_dev_6724Test` | 2.4.20-dev-6724, the compiler IntelliJ 2026.2.0.1 analyzes with |
| `k2420_ij262_34Test`, `k2420_ij262_52Test` | IntelliJ 2026.2 builds |

**Tests as data files.** A test is a `.kt` file with a `box()` function returning `"OK"`; the framework records FIR and
IR dumps as golden files. The dump shows the plugin's effect directly:

```
public final class Rectangular : R|kotlin/Any|, R|Sized|
```

**Version differences are small.** Building against 2.5.0-dev needed one shim: `KtSourceElement.fakeElement` became a
member function instead of a top-level extension, so only the import differs. With `versionHierarchy { splitDev(2, 5) }`
the fix is an `expect fun KtSourceElement.pluginGenerated()` in `commonMain` and two three-line actuals
(`SourceElements.kt`). Everything else compiled unchanged.

**2.5 is blocked by the DevKit, not by us.** With the shim in place, the 2.5.0-dev test fails inside the DevKit's own
infrastructure: `NoSuchMethodError: IrDiagnosticsHandler.<init>(TestServices)` from
`BackportedJvmBlackBoxCodegenTestBase`. The DevKit (0.0.3-dev-66e2b55) hasn't caught up with that compiler build, so
`useLatestDev` stays off.

## What is left to migrate

- **`:sample` doesn't build on this branch.** It wires the plugin with
  `kotlinCompilerPluginClasspath(project(":structural-compiler-plugin"))`, but a DevKit plugin loads its version-specific
  implementation through the Gradle side. The fix is to migrate `:structural-gradle-plugin` to
  `pluginDevKit("gradle-plugin")` and `DevKitSupportPlugin(PluginInfo.PLUGIN_INFO)`, then have the sample apply that
  plugin.
- **The 64 existing tests** still run as a plain JVM source set (`test`, kotlin-compile-testing). They pass, but the
  framework's box and diagnostics tests are the DevKit's way, and IDE-mode tests are the reason to switch.
- **Publishing** needs re-checking: the DevKit publishes a variant per compiler version, which changes what the Gradle
  plugin points at.
- **The IntelliJ plugin question is open.** The DevKit's own IntelliJ plugin is a test-authoring aid and does not load
  third-party compiler plugins, so it doesn't obviously replace `:structural-intellij-plugin`. Asked upstream; the answer
  decides whether that module can go.

## Recommendation

Adopt it. The reasons, in order:

1. IDE-mode tests against the exact compilers IntelliJ uses. Both bugs that only appeared in the editor (enum supertypes,
   missing `override`) are exactly what those tests catch.
2. Support for several Kotlin versions, which is what "0.1.0-kotlin-2.4.20" exists to apologise for.
3. The compatibility work looks small: one shim for 2.5 so far.

The cost is a dependency on an EAP DevKit and porting the tests.
