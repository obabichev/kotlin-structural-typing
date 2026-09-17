package dev.structural.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility

internal const val STRUCTURAL_ANNOTATION = "dev.structural.Structural"

internal val KSDeclaration.fqName: String
    get() = qualifiedName?.asString() ?: simpleName.asString()

internal fun KSType.isStructural(): Boolean {
    val declaration = declaration as? KSClassDeclaration ?: return false
    return declaration.annotations.any {
        it.shortName.asString() == "Structural" &&
            it.annotationType.resolve().declaration.qualifiedName?.asString() == STRUCTURAL_ANNOTATION
    }
}

internal fun KSType.hasStructuralTypeArgument(): Boolean = arguments.any { argument ->
    val type = argument.type?.resolve() ?: return@any false
    type.isStructural() || type.hasStructuralTypeArgument()
}

/** Fully qualified rendering used to compare parameter types. */
internal fun KSType.key(): String = buildString {
    append(declaration.fqName)
    if (arguments.isNotEmpty()) {
        append(arguments.joinToString(",", "<", ">") { "${it.variance.name} ${it.type?.resolve()?.key() ?: "*"}" })
    }
    if (isMarkedNullable) append("?")
}

/** Short rendering used in messages. */
internal fun KSType.display(): String = buildString {
    append(declaration.simpleName.asString())
    if (arguments.isNotEmpty()) {
        append(arguments.joinToString(", ", "<", ">") { it.type?.resolve()?.display() ?: "*" })
    }
    if (isMarkedNullable) append("?")
}

/** PUBLIC or INTERNAL when every enclosing declaration allows it, otherwise the first restricting visibility. */
internal fun KSDeclaration.effectiveVisibility(): Visibility {
    var result = Visibility.PUBLIC
    var current: KSDeclaration? = this
    while (current != null) {
        when (val visibility = current.getVisibility()) {
            Visibility.PUBLIC -> Unit
            Visibility.INTERNAL -> result = Visibility.INTERNAL
            else -> return visibility
        }
        current = current.parentDeclaration
    }
    return result
}

internal fun Visibility.isUsableFromGeneratedCode(): Boolean = this == Visibility.PUBLIC || this == Visibility.INTERNAL

internal fun KSClassDeclaration.allSupertypeNames(): Set<String> =
    getAllSuperTypes().map { it.declaration.fqName }.toSet() + "kotlin.Any"
