package dev.structural.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/** Decides whether a class has the shape of a @Structural interface, using Kotlin override rules. */
object Matcher {
    fun implementsNominally(candidate: KSClassDeclaration, iface: StructuralInterface): Boolean =
        iface.declaration.asStarProjectedType().isAssignableFrom(candidate.asStarProjectedType())

    fun matchesStructurally(candidate: KSClassDeclaration, iface: StructuralInterface): Boolean {
        if (implementsNominally(candidate, iface)) return false
        val candidateType = candidate.asStarProjectedType()
        val properties = candidate.getAllProperties().associateBy { it.simpleName.asString() }
        return iface.properties.all { required ->
            val property = properties[required.name] ?: return false
            satisfies(property, candidateType, required)
        }
    }

    private fun satisfies(property: KSPropertyDeclaration, owner: KSType, required: RequiredProperty): Boolean {
        if (property.extensionReceiver != null) return false
        if (!property.getVisibility().isUsableFromGeneratedCode()) return false
        val actual = property.asMemberOf(owner)
        if (!required.mutable) return required.type.isAssignableFrom(actual)

        if (!property.isMutable) return false
        val setterModifiers = property.setter?.modifiers.orEmpty()
        if (Modifier.PRIVATE in setterModifiers || Modifier.PROTECTED in setterModifiers) return false
        return required.type.isAssignableFrom(actual) && actual.isAssignableFrom(required.type)
    }
}
