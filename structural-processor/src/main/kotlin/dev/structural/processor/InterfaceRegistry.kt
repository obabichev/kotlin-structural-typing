package dev.structural.processor

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

class RequiredProperty(val name: String, val type: KSType, val mutable: Boolean)

class StructuralInterface(val declaration: KSClassDeclaration, val properties: List<RequiredProperty>) {
    val name: String get() = declaration.fqName
}

/** Validates @Structural interfaces once and caches the result (null for invalid ones). */
class InterfaceRegistry(private val logger: KSPLogger) {
    private val cache = mutableMapOf<String, StructuralInterface?>()

    fun get(declaration: KSClassDeclaration): StructuralInterface? {
        val name = declaration.fqName
        if (name !in cache) cache[name] = build(declaration)
        return cache[name]
    }

    private fun build(declaration: KSClassDeclaration): StructuralInterface? {
        val name = declaration.fqName
        if (declaration.classKind != ClassKind.INTERFACE) {
            logger.error("[structural] @Structural can only be applied to interfaces: $name", declaration)
            return null
        }
        if (declaration.typeParameters.isNotEmpty()) {
            logger.error("[structural] @Structural interface $name must not have type parameters", declaration)
            return null
        }
        val abstractFunctions = declaration.getAllFunctions().filter { it.isAbstract }.map { it.simpleName.asString() }.toList()
        if (abstractFunctions.isNotEmpty()) {
            logger.error(
                "[structural] @Structural interface $name must not declare abstract functions: ${abstractFunctions.joinToString()}",
                declaration,
            )
            return null
        }
        val self = declaration.asStarProjectedType()
        val properties = declaration.getAllProperties()
            .filter { it.isAbstract() }
            .map { RequiredProperty(it.simpleName.asString(), it.asMemberOf(self), it.isMutable) }
            .toList()
        return StructuralInterface(declaration, properties)
    }
}
