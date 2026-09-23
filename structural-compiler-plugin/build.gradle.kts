plugins {
    pluginDevKit("compiler-plugin")
}

pluginDevKit {
    // Where the compiler API changed, the source sets split and SourceElements.kt has one actual per range.
    versionHierarchy {
        // KtFakeSourceElementKind.PluginGenerated gained a Default in 2.4.20; before that it was the kind itself.
        // By version rather than by name: every 2.4.20 build, including the dev and IDE ones, has the new form.
        // The predicate selects the "pre" side, so it describes the compilers that still have the old form.
        split("2420") { it.major == 2 && it.minor == 4 && it.patch < 20 }
    }
    componentRegistrar = "com.obabichev.structural.compiler.StructuralComponentRegistrar"
    commandLineProcessor = "com.obabichev.structural.compiler.StructuralCommandLineProcessor"
}
