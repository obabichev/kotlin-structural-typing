@file:OptIn(SymbolInternals::class)

package com.obabichev.structural.compiler

import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
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
 * Marks the properties and functions that implement a @Structural interface added by [StructuralSupertypeGenerator] as
 * `override`, matched by full signature so unrelated overloads stay untouched. Supertypes written in source are left
 * alone, so a missing `override` there is still a normal compiler error.
 */
class StructuralOverrideMarker(session: FirSession) : FirStatusTransformerExtension(session) {
    private val types = ResolvedTypes(session)
    private val structuralInterfaces by lazy { session.structuralInterfaces(types).associateBy { it.classId } }

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(STRUCTURAL_PREDICATE)
    }

    override fun needTransformStatus(declaration: FirDeclaration): Boolean =
        (declaration is FirProperty || declaration is FirNamedFunction) && !(declaration as FirCallableDeclaration).status.isOverride

    override fun transformStatus(
        status: FirDeclarationStatus,
        property: FirProperty,
        containingClass: FirClassLikeSymbol<*>?,
        isLocal: Boolean,
    ): FirDeclarationStatus = markIfImplementing(status, property, containingClass)

    override fun transformStatus(
        status: FirDeclarationStatus,
        function: FirNamedFunction,
        containingClass: FirClassLikeSymbol<*>?,
        isLocal: Boolean,
    ): FirDeclarationStatus = markIfImplementing(status, function, containingClass)

    private fun markIfImplementing(
        status: FirDeclarationStatus,
        declaration: FirCallableDeclaration,
        containingClass: FirClassLikeSymbol<*>?,
    ): FirDeclarationStatus {
        val klass = containingClass as? FirRegularClassSymbol ?: return status
        val member = Member(declaration, klass)
        val implements = klass.fir.superTypeRefs
            .filter { it.source?.kind !is KtRealSourceElementKind }
            .mapNotNull { (it as? FirResolvedTypeRef)?.coneType?.classId }
            .mapNotNull { structuralInterfaces[it] }
            .any { iface -> iface.requirements.any { session.satisfies(member, it, types) } }
        return if (implements) status.transform { isOverride = true } else status
    }
}
