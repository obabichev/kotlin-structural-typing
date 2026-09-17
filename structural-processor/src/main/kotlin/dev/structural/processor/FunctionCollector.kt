package dev.structural.processor

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/** A supported function whose parameter at [slot] is the @Structural interface [iface]. */
class StructuralFunction(val declaration: KSFunctionDeclaration, val slot: Int, val iface: StructuralInterface) {
    val owner: KSClassDeclaration? get() = declaration.parentDeclaration as? KSClassDeclaration
}

/** Supported functions that differ only in the structural parameter's type, plus all declared types at that position. */
class FunctionGroup(
    val memberScope: Boolean,
    val functions: List<StructuralFunction>,
    val declaredTypes: List<KSClassDeclaration>,
) {
    val name: String get() = functions.first().declaration.fqName
}

class FunctionCollector(private val interfaces: InterfaceRegistry, private val logger: KSPLogger) {
    private data class GroupKey(val scope: String, val slot: Int, val shape: List<String>)

    fun collect(files: List<KSFile>): List<FunctionGroup> {
        val scopes = mutableMapOf<String, MutableList<KSFunctionDeclaration>>()
        val supported = mutableListOf<StructuralFunction>()
        files.forEach { walk(it.declarations, scopes, supported) }

        return supported
            .groupBy { GroupKey(scopeOf(it.declaration), it.slot, shapeOf(it.declaration, it.slot)) }
            .map { (key, functions) ->
                val declared = scopes.getValue(key.scope)
                    .filter { it.parameters.size > key.slot && shapeOf(it, key.slot) == key.shape }
                    .mapNotNull { it.parameters[key.slot].type.resolve().declaration as? KSClassDeclaration }
                FunctionGroup(memberScope = functions.first().owner != null, functions = functions, declaredTypes = declared)
            }
    }

    private fun walk(
        declarations: Sequence<KSDeclaration>,
        scopes: MutableMap<String, MutableList<KSFunctionDeclaration>>,
        supported: MutableList<StructuralFunction>,
    ) {
        for (declaration in declarations) {
            when (declaration) {
                is KSClassDeclaration -> walk(declaration.declarations, scopes, supported)
                is KSFunctionDeclaration -> if (!declaration.isConstructor()) {
                    scopes.getOrPut(scopeOf(declaration)) { mutableListOf() } += declaration
                    inspect(declaration)?.let(supported::add)
                }
                else -> Unit
            }
        }
    }

    private fun inspect(function: KSFunctionDeclaration): StructuralFunction? {
        val receiver = function.extensionReceiver?.resolve()
        val parameterTypes = function.parameters.map { it.type.resolve() }
        val mentionsStructural = (listOfNotNull(receiver) + parameterTypes).any { it.isStructural() || it.hasStructuralTypeArgument() }
        if (!mentionsStructural) return null

        val reason = unsupportedReason(function, receiver, parameterTypes)
        if (reason != null) {
            logger.warn("[structural] Skipping ${function.signature()}: $reason", function)
            return null
        }
        val slot = parameterTypes.indexOfFirst { it.isStructural() }
        val iface = interfaces.get(parameterTypes[slot].declaration as KSClassDeclaration) ?: return null
        return StructuralFunction(function, slot, iface)
    }

    private fun unsupportedReason(function: KSFunctionDeclaration, receiver: KSType?, parameterTypes: List<KSType>): String? {
        val owner = function.parentDeclaration as? KSClassDeclaration
        val structuralSlots = parameterTypes.indices.filter { parameterTypes[it].isStructural() }
        return when {
            receiver?.isStructural() == true -> "a @Structural interface as extension receiver"
            (listOfNotNull(receiver) + parameterTypes).any { it.hasStructuralTypeArgument() } ->
                "a @Structural interface nested in a parameter type"
            structuralSlots.size > 1 -> "more than one @Structural parameter"
            parameterTypes[structuralSlots.single()].isMarkedNullable -> "nullable @Structural parameter"
            function.parameters.any { it.isVararg } -> "vararg parameters"
            function.parameters.withIndex().any { (index, parameter) -> index !in structuralSlots && parameter.hasDefault } ->
                "parameters with default values"
            function.typeParameters.isNotEmpty() -> "type parameters"
            Modifier.INLINE in function.modifiers -> "inline function"
            function.isExpect || function.isActual -> "expect/actual function"
            owner?.isCompanionObject == true -> "companion object member"
            owner != null && (owner.typeParameters.isNotEmpty() || Modifier.INNER in owner.modifiers) ->
                "member of a generic or inner class"
            !function.effectiveVisibility().isUsableFromGeneratedCode() -> "private or protected visibility"
            else -> null
        }
    }

    private fun scopeOf(function: KSFunctionDeclaration): String =
        (function.parentDeclaration as? KSClassDeclaration)?.let { "member ${it.fqName}" }
            ?: "package ${function.packageName.asString()}"

    /** Name, receiver and parameter types, with the structural position blanked out. */
    private fun shapeOf(function: KSFunctionDeclaration, slot: Int): List<String> =
        listOf(function.simpleName.asString(), function.extensionReceiver?.resolve()?.key() ?: "-") +
            function.parameters.mapIndexed { index, parameter -> if (index == slot) "_" else parameter.type.resolve().key() }

    private fun KSFunctionDeclaration.signature(): String =
        "$fqName(${parameters.joinToString { "${it.name?.asString()}: ${it.type.resolve().display()}" }})"
}
