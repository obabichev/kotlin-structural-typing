@file:OptIn(SymbolInternals::class)

package com.obabichev.structural.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.SupertypeSupplier
import org.jetbrains.kotlin.fir.resolve.TypeResolutionConfiguration
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.typeResolver
import org.jetbrains.kotlin.fir.scopes.createImportingScopes
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.fir.types.FirImplicitTypeRef
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildUserTypeRef
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.impl.FirQualifierPartImpl
import org.jetbrains.kotlin.fir.types.impl.FirTypeArgumentListImpl
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * How types of declarations are found and compared, which depends on the compiler phase. Each type belongs to the class
 * that declares it, whose file decides how it resolves. Null means the type isn't known (yet) or doesn't resolve.
 */
internal interface TypeLookup {
    fun returnType(declaration: FirCallableDeclaration, owner: FirRegularClassSymbol): ConeKotlinType?

    fun parameterType(parameter: FirValueParameter, owner: FirRegularClassSymbol): ConeKotlinType?

    fun superTypes(symbol: FirRegularClassSymbol): List<ConeKotlinType>

    fun isSubtype(actual: ConeKotlinType, expected: ConeKotlinType): Boolean

    fun isEqual(first: ConeKotlinType, second: ConeKotlinType): Boolean
}

/**
 * After supertype resolution: explicit types are resolved (inferred ones once bodies are), and the compiler's checker is
 * safe to use.
 *
 * Types are read through symbols rather than from declarations. IntelliJ resolves each declaration on demand, so asking
 * a symbol resolves it if needed; reading the declaration directly would see an unresolved type and find no match.
 */
internal class ResolvedTypes(private val session: FirSession) : TypeLookup {
    override fun returnType(declaration: FirCallableDeclaration, owner: FirRegularClassSymbol): ConeKotlinType? =
        runCatching { declaration.symbol.resolvedReturnType }.getOrNull()?.takeUnless { it is ConeErrorType }

    override fun parameterType(parameter: FirValueParameter, owner: FirRegularClassSymbol): ConeKotlinType? =
        runCatching { parameter.symbol.resolvedReturnType }.getOrNull()?.takeUnless { it is ConeErrorType }

    override fun superTypes(symbol: FirRegularClassSymbol): List<ConeKotlinType> =
        runCatching { symbol.resolvedSuperTypes }.getOrDefault(emptyList())

    override fun isSubtype(actual: ConeKotlinType, expected: ConeKotlinType): Boolean =
        AbstractTypeChecker.isSubtypeOf(session.typeContext, actual, expected)

    override fun isEqual(first: ConeKotlinType, second: ConeKotlinType): Boolean =
        AbstractTypeChecker.equalTypes(session.typeContext, first, second)
}

/**
 * During supertype resolution. Only explicitly written types can be known, each resolved with the imports of the file
 * declaring it, and types of the class being processed with the compiler's resolver for it (which also sees nested
 * classes).
 *
 * Declarations that aren't from source -- an interface published by a dependency -- are read through their symbols
 * instead. Their type references are only resolved on demand when IntelliJ analyzes code, so reading them directly
 * finds nothing and the class silently doesn't match.
 *
 * The compiler's type checker must not be used here: it would compute and cache supertypes of classes the compiler
 * hasn't processed yet, and later checks in unrelated code would use the incomplete result. Types are compared
 * structurally instead, walking declared supertypes for subtyping.
 */
internal class SupertypePhaseTypes(
    private val session: FirSession,
    private val fileScopes: FileScopes,
    private val currentClass: FirRegularClassSymbol? = null,
    private val currentClassResolver: FirSupertypeGenerationExtension.TypeResolveService? = null,
) : TypeLookup {
    override fun returnType(declaration: FirCallableDeclaration, owner: FirRegularClassSymbol): ConeKotlinType? =
        resolve(declaration.returnTypeRef, owner) ?: declaration.symbol.typeFromAnotherModule(owner)

    override fun parameterType(parameter: FirValueParameter, owner: FirRegularClassSymbol): ConeKotlinType? =
        resolve(parameter.returnTypeRef, owner) ?: parameter.symbol.typeFromAnotherModule(owner)

    /**
     * The type of a member of a class this module only depends on. Asking a symbol resolves it if needed, which must
     * not be done to the module's own declarations while supertypes are being decided -- their types may be inferred,
     * and forcing them would resolve in the wrong order. Another module's declarations are already past that point.
     *
     * IntelliJ needs this: it keeps each module's sources in their own session, so a @Structural interface from another
     * module of the project is a source declaration whose types this session's scopes can't resolve. For the
     * command line compiler the same interface is a class file and its types are read straight off it.
     */
    private fun FirCallableSymbol<*>.typeFromAnotherModule(owner: FirRegularClassSymbol): ConeKotlinType? {
        // A class this session compiles has a file here; anything else belongs to a dependency.
        if (session.firProvider.getFirClassifierContainerFileIfAny(owner.classId) != null) return null
        return runCatching { resolvedReturnType }.getOrNull()?.takeUnless { it is ConeErrorType }
    }

    override fun superTypes(symbol: FirRegularClassSymbol): List<ConeKotlinType> =
        if (symbol.origin == FirDeclarationOrigin.Source) symbol.fir.superTypeRefs.mapNotNull { resolve(it, symbol) }
        else runCatching { symbol.resolvedSuperTypes }.getOrDefault(emptyList())

    private fun resolve(typeRef: FirTypeRef, owner: FirRegularClassSymbol): ConeKotlinType? {
        val type = when {
            typeRef is FirResolvedTypeRef -> typeRef.coneType
            typeRef is FirImplicitTypeRef -> null
            typeRef is FirUserTypeRef ->
                typeRef.typeParameterOf(owner)
                    ?: typeRef.withArgumentsResolved(owner)
                    ?: resolveUserType(typeRef, owner)
            else -> fileScopes.resolve(typeRef, owner)
        }
        return type?.takeUnless { it is ConeErrorType }
    }

    private fun resolveUserType(typeRef: FirUserTypeRef, owner: FirRegularClassSymbol): ConeKotlinType? =
        if (owner == currentClass && currentClassResolver != null) {
            currentClassResolver.resolveUserType(typeRef).coneType
        } else {
            fileScopes.resolve(typeRef, owner)
        }

    /**
     * A type written with arguments, such as `Comparable<String>` or `Iterator<T>`, resolved a piece at a time: the
     * compiler's resolver expects the arguments to be resolved already and asserts when they are not, which is the
     * normal state of a file it hasn't processed yet. Resolving them here instead makes the answer the same whatever
     * order the files are in.
     */
    private fun FirUserTypeRef.withArgumentsResolved(owner: FirRegularClassSymbol): ConeKotlinType? {
        val part = qualifier.lastOrNull() ?: return null
        val argumentRefs = part.typeArgumentList.typeArguments
        if (argumentRefs.isEmpty()) return null
        val bare = buildUserTypeRef {
            source = this@withArgumentsResolved.source
            isMarkedNullable = this@withArgumentsResolved.isMarkedNullable
            qualifier.addAll(this@withArgumentsResolved.qualifier.map { FirQualifierPartImpl(it.source, it.name, FirTypeArgumentListImpl(it.source)) })
        }
        // The qualifier alone names a generic class, which only resolves when bare types are allowed.
        val classId = fileScopes.resolve(bare, owner, bareTypesAllowed = true)?.classId ?: return null
        val arguments = argumentRefs.map { argument ->
            val ref = (argument as? FirTypeProjectionWithVariance)?.takeIf { it.variance == Variance.INVARIANT }?.typeRef
                ?: return null
            resolve(ref, owner) ?: return null
        }
        return classId.constructClassLikeType(arguments.toTypedArray(), isMarkedNullable)
    }

    /**
     * `T` in a member of a generic interface. Import scopes can't resolve a type parameter, so it is matched by name
     * against the declaring class's own parameters; the substitution of the @Structural interface replaces it next.
     */
    private fun FirUserTypeRef.typeParameterOf(owner: FirRegularClassSymbol): ConeKotlinType? {
        val name = qualifier.singleOrNull()?.name ?: return null
        val parameter = owner.fir.typeParameters.firstOrNull { it.symbol.name == name } ?: return null
        return ConeTypeParameterTypeImpl(parameter.symbol.toLookupTag(), isMarkedNullable = isMarkedNullable)
    }

    override fun isEqual(first: ConeKotlinType, second: ConeKotlinType): Boolean {
        val a = first.lowerBoundIfFlexible() as? ConeClassLikeType ?: return false
        val b = second.lowerBoundIfFlexible() as? ConeClassLikeType ?: return false
        return a.isMarkedNullable == b.isMarkedNullable && sameClassAndArguments(a, b)
    }

    /** Plain class types by their declared supertypes; types with arguments only when their arguments are equal. */
    override fun isSubtype(actual: ConeKotlinType, expected: ConeKotlinType): Boolean {
        val a = actual.lowerBoundIfFlexible() as? ConeClassLikeType ?: return false
        val e = expected.lowerBoundIfFlexible() as? ConeClassLikeType ?: return false
        if (a.isMarkedNullable && !e.isMarkedNullable) return false
        if (a.typeArguments.isNotEmpty() || e.typeArguments.isNotEmpty()) return sameClassAndArguments(a, e)
        val target = e.classId
        if (target == StandardClassIds.Any) return true
        return inheritsFrom(a.classId, target, mutableSetOf())
    }

    private fun sameClassAndArguments(a: ConeClassLikeType, b: ConeClassLikeType): Boolean =
        a.classId == b.classId &&
            a.typeArguments.size == b.typeArguments.size &&
            a.typeArguments.zip(b.typeArguments).all { (x, y) ->
                val xType = (x as? ConeKotlinTypeProjection)?.type
                val yType = (y as? ConeKotlinTypeProjection)?.type
                x.kind == y.kind && (xType == null && yType == null || xType != null && yType != null && isEqual(xType, yType))
            }

    private fun inheritsFrom(classId: ClassId, target: ClassId, visited: MutableSet<ClassId>): Boolean {
        if (classId == target) return true
        if (!visited.add(classId)) return false
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return false
        return symbol.fir.superTypeRefs.any { superTypeRef ->
            val superClassId = resolve(superTypeRef, symbol)?.classId
            superClassId != null && inheritsFrom(superClassId, target, visited)
        }
    }
}

/** Resolves type references with the importing scopes of the file declaring a class, like the compiler's supertype resolver. */
internal class FileScopes(private val session: FirSession) {
    private val scopeSession = ScopeSession()
    private val configurations = HashMap<ClassId, TypeResolutionConfiguration?>()

    /**
     * Resolving can throw rather than fail: the compiler's resolver expects a type's arguments to be resolved already,
     * and asks for them with an assertion. Reaching a reference before the file declaring it has been processed is
     * normal here, so a failure means "not known yet", never a broken build.
     */
    fun resolve(
        typeRef: FirTypeRef,
        owner: FirRegularClassSymbol,
        bareTypesAllowed: Boolean = false,
    ): ConeKotlinType? = runCatching { resolveOrThrow(typeRef, owner, bareTypesAllowed) }.getOrNull()

    private fun resolveOrThrow(
        typeRef: FirTypeRef,
        owner: FirRegularClassSymbol,
        bareTypesAllowed: Boolean,
    ): ConeKotlinType? {
        val configuration = configurations.getOrPut(owner.classId) { configurationFor(owner) } ?: return null
        return session.typeResolver.resolveType(
            typeRef,
            configuration,
            bareTypesAllowed,
            /* isOperandOfIsOperator = */ false,
            /* resolveDeprecations = */ false,
            ResolvedSupertypesOnly,
            /* expandTypeAliases = */ true,
        ).type
    }

    private fun configurationFor(owner: FirRegularClassSymbol): TypeResolutionConfiguration? {
        val file = session.firProvider.getFirClassifierContainerFileIfAny(owner.classId) ?: return null
        val scopes = createImportingScopes(file, session, scopeSession).asReversed()
        return TypeResolutionConfiguration(scopes, listOf(owner.fir), file)
    }
}

/** Supplies only supertypes that are already resolved, so resolving a type never triggers supertype computation. */
private object ResolvedSupertypesOnly : SupertypeSupplier() {
    override fun forClass(firClass: FirClass, useSiteSession: FirSession): List<ConeClassLikeType> =
        firClass.superTypeRefs.mapNotNull { (it as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType }

    override fun expansionForTypeAlias(typeAlias: FirTypeAlias, useSiteSession: FirSession): ConeClassLikeType? =
        (typeAlias.expandedTypeRef as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType
}
