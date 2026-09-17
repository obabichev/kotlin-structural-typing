package dev.structural.processor

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/** A supported function whose parameter at [slot], or extension receiver for [RECEIVER], is the @Structural interface [iface]. */
class StructuralFunction(val declaration: KSFunctionDeclaration, val slot: Int, val iface: StructuralInterface) {
    val owner: KSClassDeclaration? get() = declaration.parentDeclaration as? KSClassDeclaration

    companion object {
        /** [slot] of a function whose extension receiver is the @Structural interface. */
        const val RECEIVER = -1
    }
}

/** Supported functions that differ only in the structural position's type, plus all declared types at that position. */
class FunctionGroup(
    val memberScope: Boolean,
    val functions: List<StructuralFunction>,
    val declaredTypes: List<KSClassDeclaration>,
) {
    val name: String get() = functions.first().declaration.fqName
}

class FunctionCollection(
    val groups: List<FunctionGroup>,
    /** Valid @Structural interfaces used in the receiver or parameter types of any function, supported or not. */
    val referencedInterfaces: List<StructuralInterface>,
)

/** Walks all functions of a module once; create a new collector for each [collect] call. */
class FunctionCollector(private val interfaces: InterfaceRegistry, private val logger: KSPLogger) {
    private data class GroupKey(val scope: String, val slot: Int, val shape: List<String>)

    private val scopes = mutableMapOf<String, MutableList<KSFunctionDeclaration>>()
    private val supported = mutableListOf<StructuralFunction>()
    private val referenced = linkedMapOf<String, StructuralInterface>()

    fun collect(files: List<KSFile>): FunctionCollection {
        files.forEach { walk(it.declarations) }

        val groups = supported
            .groupBy { GroupKey(scopeOf(it.declaration), it.slot, shapeOf(it.declaration, it.slot)) }
            .map { (key, functions) ->
                val declared = scopes.getValue(key.scope)
                    .filter { shapeOf(it, key.slot) == key.shape }
                    .mapNotNull { typeAt(it, key.slot)?.declaration as? KSClassDeclaration }
                FunctionGroup(memberScope = functions.first().owner != null, functions = functions, declaredTypes = declared)
            }
        return FunctionCollection(groups, referenced.values.toList())
    }

    private fun walk(declarations: Sequence<KSDeclaration>) {
        for (declaration in declarations) {
            when (declaration) {
                is KSClassDeclaration -> walk(declaration.declarations)
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
        val mentioned = (listOfNotNull(receiver) + parameterTypes).flatMap { it.structuralDeclarations() }
        mentioned.forEach { declaration -> interfaces.get(declaration)?.let { referenced.putIfAbsent(it.name, it) } }

        if (mentioned.isEmpty()) return null

        val reason = unsupportedReason(function, receiver, parameterTypes)
        if (reason != null) {
            // Info, not a warning: nobody opted in, and callers can still use adapters.
            logger.info("[structural] Skipping ${function.signature()}: $reason", function)
            return null
        }
        val slot = if (receiver?.isStructural() == true) StructuralFunction.RECEIVER else parameterTypes.indexOfFirst { it.isStructural() }
        val iface = interfaces.get(typeAt(function, slot)!!.declaration as KSClassDeclaration) ?: return null
        return StructuralFunction(function, slot, iface)
    }

    private fun unsupportedReason(function: KSFunctionDeclaration, receiver: KSType?, parameterTypes: List<KSType>): String? {
        val owner = function.parentDeclaration as? KSClassDeclaration
        val types = listOfNotNull(receiver) + parameterTypes
        return when {
            types.any { it.hasStructuralTypeArgument() } -> "a @Structural interface nested in a parameter type"
            types.count { it.isStructural() } > 1 -> "more than one @Structural parameter"
            receiver?.isStructural() == true && owner != null -> "member extension function with a @Structural receiver"
            function.parameters.withIndex().any { (index, parameter) -> parameter.hasDefault && !parameterTypes[index].isStructural() } ->
                "parameters with default values"
            function.typeParameters.isNotEmpty() -> "type parameters"
            Modifier.INLINE in function.modifiers -> "inline function"
            function.isExpect || function.isActual -> "expect/actual function"
            owner != null && (owner.typeParameters.isNotEmpty() || Modifier.INNER in owner.modifiers) ->
                "member of a generic or inner class"
            !function.effectiveVisibility().isUsableFromGeneratedCode() -> "private or protected visibility"
            else -> null
        }
    }

    private fun scopeOf(function: KSFunctionDeclaration): String =
        (function.parentDeclaration as? KSClassDeclaration)?.let { "member ${it.fqName}" }
            ?: "package ${function.packageName.asString()}"

    private fun typeAt(function: KSFunctionDeclaration, slot: Int): KSType? =
        if (slot == StructuralFunction.RECEIVER) function.extensionReceiver?.resolve()
        else function.parameters.getOrNull(slot)?.type?.resolve()

    /** Name, receiver and parameter types, with the structural position blanked out; empty if [slot] doesn't exist. */
    private fun shapeOf(function: KSFunctionDeclaration, slot: Int): List<String> {
        if (typeAt(function, slot) == null) return emptyList()
        val receiver = if (slot == StructuralFunction.RECEIVER) "_" else function.extensionReceiver?.resolve()?.key() ?: "-"
        val parameters = function.parameters.mapIndexed { index, parameter ->
            val prefix = if (parameter.isVararg) "vararg " else ""
            if (index == slot) "${prefix}_" else prefix + parameter.type.resolve().key()
        }
        return listOf(function.simpleName.asString(), receiver) + parameters
    }

    private fun KSFunctionDeclaration.signature(): String =
        "$fqName(${parameters.joinToString { "${it.name?.asString()}: ${it.type.resolve().display()}" }})"
}
