package dev.structural.processor

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Modifier

/** Classes from the configured packages that generated overloads may accept. */
object Candidates {
    fun find(files: List<KSFile>, config: Config): List<KSClassDeclaration> =
        files
            .filter { config.includesPackage(it.packageName.asString()) }
            .flatMap { file -> file.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested) }
            .filter(::isCandidate)

    private fun withNested(declaration: KSClassDeclaration): Sequence<KSClassDeclaration> =
        sequenceOf(declaration) + declaration.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested)

    private fun isCandidate(declaration: KSClassDeclaration): Boolean =
        declaration.classKind != ClassKind.ANNOTATION_CLASS &&
            declaration.classKind != ClassKind.ENUM_ENTRY &&
            !declaration.isCompanionObject &&
            Modifier.INNER !in declaration.modifiers &&
            declaration.typeParameters.isEmpty() &&
            declaration.effectiveVisibility().isUsableFromGeneratedCode()
}
