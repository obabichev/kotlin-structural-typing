plugins {
    pluginDevKit("compiler-plugin")
}

pluginDevKit {
    // Kotlin 2.5 turned KtSourceElement.fakeElement from a top-level extension into a member, so the source sets split
    // there; see SourceElements.kt.
    versionHierarchy {
        splitDev(2, 5)
    }
    componentRegistrar = "com.obabichev.structural.compiler.StructuralComponentRegistrar"
    commandLineProcessor = "com.obabichev.structural.compiler.StructuralCommandLineProcessor"
}
