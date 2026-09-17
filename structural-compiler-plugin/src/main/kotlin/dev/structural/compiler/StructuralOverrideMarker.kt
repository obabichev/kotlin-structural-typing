@file:OptIn(SymbolInternals::class)

package dev.structural.compiler

import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.extensions.transform
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType

/**
 * Marks properties as `override` when they implement a @Structural interface added by [StructuralSupertypeGenerator].
 * Supertypes written in source are left alone, so a missing `override` there is still a normal compiler error.
 */
class StructuralOverrideMarker(session: FirSession) : FirStatusTransformerExtension(session) {
    private val structuralInterfaces by lazy { session.structuralInterfaces().associateBy { it.classId } }

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(STRUCTURAL_PREDICATE)
    }

    override fun needTransformStatus(declaration: FirDeclaration): Boolean =
        declaration is FirProperty && !declaration.status.isOverride

    override fun transformStatus(
        status: FirDeclarationStatus,
        property: FirProperty,
        containingClass: FirClassLikeSymbol<*>?,
        isLocal: Boolean,
    ): FirDeclarationStatus {
        val klass = containingClass as? FirRegularClassSymbol ?: return status
        val implemented = klass.fir.superTypeRefs
            .filter { it.source?.kind !is KtRealSourceElementKind }
            .mapNotNull { (it as? FirResolvedTypeRef)?.coneType?.classId }
            .mapNotNull { structuralInterfaces[it] }
        val required = implemented.flatMap { iface -> requiredProperties(iface).map { it.name } }
        return if (property.name in required) status.transform { isOverride = true } else status
    }
}
