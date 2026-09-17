@file:OptIn(DirectDeclarationsAccess::class)

package dev.structural.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType

/** Adds every @Structural interface that a class matches as a supertype of that class. */
class StructuralSupertypeGenerator(session: FirSession) : FirSupertypeGenerationExtension(session) {
    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(STRUCTURAL_PREDICATE)
    }

    override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean =
        declaration is FirRegularClass && declaration.classKind in MATCHABLE_CLASS_KINDS

    override fun computeAdditionalSupertypes(
        classLikeDeclaration: FirClassLikeDeclaration,
        resolvedSupertypes: List<FirResolvedTypeRef>,
        typeResolver: TypeResolveService,
    ): List<ConeKotlinType> {
        val klass = classLikeDeclaration as? FirRegularClass ?: return emptyList()
        val existing = resolvedSupertypes.mapNotNull { it.coneType.classId }.toSet()
        val ownProperties = klass.declarations.filterIsInstance<FirProperty>()
        val inheritedProperties = session.superclassProperties(resolvedSupertypes)
        // Inferred property types are resolved much later; such properties can't match (see InferredPropertyTypesChecker).
        val types = PropertyTypes { property ->
            when (val typeRef = property.returnTypeRef) {
                is FirResolvedTypeRef -> typeRef.coneType
                is FirUserTypeRef -> typeResolver.resolveUserType(typeRef).coneType
                else -> null
            }
        }
        return session.structuralInterfaces()
            .filter { it.classId !in existing && session.matches(ownProperties, inheritedProperties, it, types) }
            .map { it.classId.constructClassLikeType(emptyArray(), isMarkedNullable = false) }
    }
}
