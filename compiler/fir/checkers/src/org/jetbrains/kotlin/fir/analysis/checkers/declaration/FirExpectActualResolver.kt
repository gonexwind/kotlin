/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.PrivateForInline
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.*
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.calls.fullyExpandedClass
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.resolve.substitution.chain
import org.jetbrains.kotlin.fir.scopes.*
import org.jetbrains.kotlin.fir.scopes.impl.FirPackageMemberScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.ConeClassifierLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.multiplatform.ExpectActualCompatibility
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.model.TypeConstructorMarker
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.keysToMap

object FirExpectActualResolver {
    @OptIn(ExperimentalStdlibApi::class)
    fun findExpectForActual(
        actualDeclaration: FirMemberDeclaration,
        useSiteSession: FirSession,
        scopeSession: ScopeSession
    ): Map<ExpectActualCompatibility<FirBasedSymbol<*>>, List<FirBasedSymbol<*>>>? {
        return when (actualDeclaration) {
            is FirCallableMemberDeclaration -> {
                val callableId = actualDeclaration.symbol.callableId
                val classId = callableId.classId
                var parentSubstitutor: ConeSubstitutor? = null
                var expectContainingClass: FirRegularClass? = null
                var actualContainingClass: FirRegularClass? = null
                val candidates = when {
                    classId != null -> {
                        expectContainingClass = useSiteSession.dependenciesSymbolProvider.getClassLikeSymbolByFqName(classId)?.fir?.let {
                            it.fullyExpandedClass(it.moduleData.session)
                        }
                        actualContainingClass = useSiteSession.symbolProvider.getClassLikeSymbolByFqName(classId)
                            ?.fir
                            ?.fullyExpandedClass(useSiteSession)

                        val expectTypeParameters = expectContainingClass?.typeParameters.orEmpty()
                        val actualTypeParameters = actualContainingClass
                            ?.typeParameters
                            .orEmpty()
                        parentSubstitutor = createTypeParameterSubstitutor(expectTypeParameters, actualTypeParameters, useSiteSession)
                        when (actualDeclaration) {
                            is FirConstructor -> expectContainingClass?.getConstructors(scopeSession)
                            else -> expectContainingClass?.getMembers(callableId.callableName, scopeSession)
                        }.orEmpty()
                    }
                    callableId.isLocal -> return null
                    else -> {
                        val scope = FirPackageMemberScope(callableId.packageName, useSiteSession, useSiteSession.dependenciesSymbolProvider)
                        mutableListOf<FirCallableMemberDeclaration>().apply {
                            scope.processFunctionsByName(callableId.callableName) { add(it.fir) }
                            scope.processPropertiesByName(callableId.callableName) { addIfNotNull(it.fir as? FirCallableMemberDeclaration) }
                        }
                    }
                }
                candidates.filter { expectDeclaration ->
                    actualDeclaration.symbol != expectDeclaration.symbol && expectDeclaration.isExpect
                }.groupBy(
                    keySelector = { expectDeclaration ->
                        areCompatibleCallables(
                            expectDeclaration,
                            actualDeclaration,
                            useSiteSession,
                            parentSubstitutor,
                            expectContainingClass,
                            actualContainingClass
                        )
                    },
                    valueTransform = { it.symbol }
                )
            }
            is FirClassLikeDeclaration -> {
                val expectClass =
                    useSiteSession.dependenciesSymbolProvider
                        .getClassLikeSymbolByFqName(actualDeclaration.symbol.classId)
                        ?.fir as? FirRegularClass
                        ?: return null
                val compatibility = areCompatibleClassifiers(expectClass, actualDeclaration, useSiteSession, scopeSession)
                mapOf(compatibility to listOf(expectClass.symbol))
            }
            else -> null
        }
    }

    private fun areCompatibleClassifiers(
        expectClass: FirRegularClass,
        actualClassLike: FirClassLikeDeclaration,
        actualSession: FirSession,
        scopeSession: ScopeSession
    ): ExpectActualCompatibility<FirBasedSymbol<*>> {
        // Can't check FQ names here because nested expected class may be implemented via actual typealias's expansion with the other FQ name
        assert(expectClass.symbol.classId.shortClassName == actualClassLike.symbol.classId.shortClassName) { "This function should be invoked only for declarations with the same name: $expectClass, $actualClassLike" }

        val actualClass = when (actualClassLike) {
            is FirRegularClass -> actualClassLike
            is FirTypeAlias -> actualClassLike.expandedTypeRef.coneType.fullyExpandedType(actualSession)
                .toSymbol(actualSession)?.fir as? FirRegularClass
                ?: return ExpectActualCompatibility.Compatible // do not report extra error on erroneous typealias
            else -> throw IllegalArgumentException("Incorrect actual classifier for $expectClass: $actualClassLike")
        }

        if (expectClass.classKind != actualClass.classKind) return ExpectActualCompatibility.Incompatible.ClassKind

        if (!equalBy(expectClass, actualClass) { listOf(it.isCompanion, it.isInner, it.isInline /*|| it.isValue*/) }) {
            return ExpectActualCompatibility.Incompatible.ClassModifiers
        }

        val expectTypeParameterRefs = expectClass.typeParameters
        val actualTypeParameterRefs = actualClass.typeParameters
        if (expectTypeParameterRefs.size != actualTypeParameterRefs.size) {
            return ExpectActualCompatibility.Incompatible.TypeParameterCount
        }

        if (!areCompatibleModalities(expectClass.modality, actualClass.modality)) {
            return ExpectActualCompatibility.Incompatible.Modality
        }

        if (expectClass.visibility != actualClass.visibility) {
            return ExpectActualCompatibility.Incompatible.Visibility
        }

        val substitutor = createTypeParameterSubstitutor(expectTypeParameterRefs, actualTypeParameterRefs, actualSession)

        val expectSession = expectClass.moduleData.session
        areCompatibleTypeParameters(expectTypeParameterRefs, actualTypeParameterRefs, actualSession, expectSession, substitutor).let {
            if (it != ExpectActualCompatibility.Compatible) {
                return it
            }
        }

        // Subtract kotlin.Any from supertypes because it's implicitly added if no explicit supertype is specified,
        // and not added if an explicit supertype _is_ specified
        val expectSupertypes = expectClass.superConeTypes.filterNot { it.classId == actualSession.builtinTypes.anyType.id }
        val actualSupertypes = actualClass.superConeTypes.filterNot { it.classId == actualSession.builtinTypes.anyType.id }
        if (
            expectSupertypes.map(substitutor::substituteOrSelf).any { expectSupertype ->
                actualSupertypes.none { actualSupertype ->
                    areCompatibleTypes(expectSupertype, actualSupertype, expectSession, actualSession)
                }
            }
        ) {
            return ExpectActualCompatibility.Incompatible.Supertypes
        }

        areCompatibleClassScopes(expectClass, actualClass, actualSession, scopeSession, substitutor).let {
            if (it != ExpectActualCompatibility.Compatible) {
                return it
            }
        }

        return ExpectActualCompatibility.Compatible
    }

    private fun areCompatibleClassScopes(
        expectClass: FirRegularClass,
        actualClass: FirRegularClass,
        actualSession: FirSession,
        scopeSession: ScopeSession,
        substitutor: ConeSubstitutor
    ): ExpectActualCompatibility<FirBasedSymbol<*>> {
        val unfulfilled =
            mutableListOf<Pair<FirBasedSymbol<*>, Map<ExpectActualCompatibility.Incompatible<FirBasedSymbol<*>>, MutableCollection<FirBasedSymbol<*>>>>>()

        val allActualMembers = actualClass.getMembers(scopeSession, actualSession)
        val actualMembersByName = allActualMembers.groupBy { it.name }
        val actualConstructors = allActualMembers.filterIsInstance<FirConstructor>()

        outer@ for (expectMember in expectClass.getMembers(scopeSession)) {
            // if (expectMember is CallableMemberDescriptor && !expectMember.kind.isReal) continue

            val actualMembers = when (expectMember) {
                is FirConstructor -> actualConstructors
                else -> actualMembersByName[expectMember.name]?.filter { actualMember ->
                    expectMember is FirRegularClass && actualMember is FirRegularClass ||
                            expectMember is FirCallableMemberDeclaration && actualMember is FirCallableMemberDeclaration
                }.orEmpty()
            }

            val mapping = actualMembers.keysToMap { actualMember ->
                when (expectMember) {
                    is FirCallableMemberDeclaration ->
                        areCompatibleCallables(
                            expectMember,
                            actualMember as FirCallableMemberDeclaration,
                            actualSession,
                            substitutor,
                            expectClass,
                            actualClass
                        )
                    is FirRegularClass ->
                        areCompatibleClassifiers(expectMember, actualMember as FirRegularClass, actualSession, scopeSession)
                    else -> throw UnsupportedOperationException("Unsupported declaration: $expectMember ($actualMembers)")
                }
            }
            if (mapping.values.any { it == ExpectActualCompatibility.Compatible }) continue

            val incompatibilityMap =
                mutableMapOf<ExpectActualCompatibility.Incompatible<FirBasedSymbol<*>>, MutableCollection<FirBasedSymbol<*>>>()
            for ((declaration, compatibility) in mapping) {
                when (compatibility) {
                    ExpectActualCompatibility.Compatible -> continue@outer
                    is ExpectActualCompatibility.Incompatible -> incompatibilityMap.getOrPut(compatibility) { SmartList() }.add(declaration.symbol)
                }
            }

            unfulfilled.add(expectMember.symbol to incompatibilityMap)
        }

        if (expectClass.classKind == ClassKind.ENUM_CLASS) {
            val expectEntries = expectClass.collectEnumEntries().map { it.name }
            val actualEntries = actualClass.collectEnumEntries().map { it.name }

            if (!actualEntries.containsAll(expectEntries)) {
                return ExpectActualCompatibility.Incompatible.EnumEntries
            }
        }

        // TODO: check static scope?

        if (unfulfilled.isEmpty()) return ExpectActualCompatibility.Compatible

        return ExpectActualCompatibility.Incompatible.ClassScopes(unfulfilled)
    }

    @Suppress("warnings")
    private fun areCompatibleCallables(
        expectDeclaration: FirCallableMemberDeclaration,
        actualDeclaration: FirCallableMemberDeclaration,
        actualSession: FirSession,
        parentSubstitutor: ConeSubstitutor?,
        expectContainingClass: FirRegularClass?,
        actualContainingClass: FirRegularClass?,
    ): ExpectActualCompatibility<FirBasedSymbol<*>> {
        assert(
            (expectDeclaration is FirConstructor && actualDeclaration is FirConstructor) ||
                    expectDeclaration.symbol.callableId.callableName == actualDeclaration.symbol.callableId.callableName
        ) {
            "This function should be invoked only for declarations with the same name: $expectDeclaration, $actualDeclaration"
        }
        assert((expectDeclaration.dispatchReceiverType == null) == (actualDeclaration.dispatchReceiverType == null)) {
            "This function should be invoked only for declarations in the same kind of container (both members or both top level): $expectDeclaration, $actualDeclaration"
        }

        val expectSession = expectDeclaration.moduleData.session

        if (
            expectDeclaration is FirConstructor &&
            actualDeclaration is FirConstructor &&
            expectContainingClass?.classKind == ClassKind.ENUM_CLASS &&
            actualContainingClass?.classKind == ClassKind.ENUM_CLASS
        ) {
            return ExpectActualCompatibility.Compatible
        }

        if (
            expectDeclaration is FirSimpleFunction && actualDeclaration !is FirSimpleFunction ||
            expectDeclaration !is FirSimpleFunction && actualDeclaration is FirSimpleFunction
        ) return ExpectActualCompatibility.Incompatible.CallableKind

        val expectedReceiverType = expectDeclaration.receiverTypeRef
        val actualReceiverType = actualDeclaration.receiverTypeRef
        if ((expectedReceiverType != null) != (actualReceiverType != null)) {
            return ExpectActualCompatibility.Incompatible.ParameterShape
        }

        val expectedValueParameters = expectDeclaration.valueParameters
        val actualValueParameters = actualDeclaration.valueParameters
        if (!valueParametersCountCompatible(expectDeclaration, actualDeclaration, expectedValueParameters, actualValueParameters)) {
            return ExpectActualCompatibility.Incompatible.ParameterCount
        }

        val expectedTypeParameters = expectDeclaration.typeParameters
        val actualTypeParameters = actualDeclaration.typeParameters
        if (expectedTypeParameters.size != actualTypeParameters.size) {
            return ExpectActualCompatibility.Incompatible.TypeParameterCount
        }

        val substitutor = createTypeParameterSubstitutor(expectedTypeParameters, actualTypeParameters, actualSession, parentSubstitutor)

        if (
            !areCompatibleTypeLists(
                expectedValueParameters.toTypeList(substitutor),
                actualValueParameters.toTypeList(ConeSubstitutor.Empty),
                expectSession,
                actualSession
            ) ||
            !areCompatibleTypes(
                expectedReceiverType?.coneType?.let { substitutor.substituteOrSelf(it) },
                actualReceiverType?.coneType,
                expectSession,
                actualSession
            )
        ) {
            return ExpectActualCompatibility.Incompatible.ParameterTypes
        }
        if (
            !areCompatibleTypes(
                substitutor.substituteOrSelf(expectDeclaration.returnTypeRef.coneType),
                actualDeclaration.returnTypeRef.coneType,
                expectSession,
                actualSession
            )
        ) {
            return ExpectActualCompatibility.Incompatible.ReturnType
        }

        // TODO: implement hasStableParameterNames calculation
        // if (actualDeclaration.hasStableParameterNames() && !equalsBy(expectedValueParameters, actualValueParameters, ValueParameterDescriptor::getName)) return Incompatible.ParameterNames

        if (!equalsBy(expectedTypeParameters, actualTypeParameters) { it.symbol.name }) {
            return ExpectActualCompatibility.Incompatible.TypeParameterNames
        }

        if (
            !areCompatibleModalities(
                expectDeclaration.modality,
                actualDeclaration.modality,
                (expectDeclaration.dispatchReceiverType?.toSymbol(expectSession)?.fir as? FirRegularClass)?.modality,
                actualContainingClass?.modality
            )
        ) {
            return ExpectActualCompatibility.Incompatible.Modality
        }

        if (!areDeclarationsWithCompatibleVisibilities(expectDeclaration.status, actualDeclaration.status)) {
            return ExpectActualCompatibility.Incompatible.Visibility
        }

        areCompatibleTypeParameters(expectedTypeParameters, actualTypeParameters, actualSession, expectSession, substitutor).let {
            if (it != ExpectActualCompatibility.Compatible) {
                return it
            }
        }

        if (!equalsBy(expectedValueParameters, actualValueParameters) { it.isVararg }) {
            return ExpectActualCompatibility.Incompatible.ValueParameterVararg
        }

        // Adding noinline/crossinline to parameters is disallowed, except if the expected declaration was not inline at all
        if (expectDeclaration is FirSimpleFunction && expectDeclaration.isInline) {
            if (expectedValueParameters.indices.any { i -> !expectedValueParameters[i].isNoinline && actualValueParameters[i].isNoinline }) {
                return ExpectActualCompatibility.Incompatible.ValueParameterNoinline
            }
            if (expectedValueParameters.indices.any { i -> !expectedValueParameters[i].isCrossinline && actualValueParameters[i].isCrossinline }) {
                return ExpectActualCompatibility.Incompatible.ValueParameterCrossinline
            }
        }

        when {
            expectDeclaration is FirSimpleFunction && actualDeclaration is FirSimpleFunction -> areCompatibleFunctions(
                expectDeclaration,
                actualDeclaration
            ).let { if (it != ExpectActualCompatibility.Compatible) return it }

            expectDeclaration is FirConstructor && actualDeclaration is FirConstructor -> areCompatibleFunctions(
                expectDeclaration,
                actualDeclaration
            ).let { if (it != ExpectActualCompatibility.Compatible) return it }

            expectDeclaration is FirProperty && actualDeclaration is FirProperty -> areCompatibleProperties(
                expectDeclaration,
                actualDeclaration
            ).let { if (it != ExpectActualCompatibility.Compatible) return it }

            else -> throw AssertionError("Unsupported declarations: $expectDeclaration, $actualDeclaration")
        }

        return ExpectActualCompatibility.Compatible
    }

    private fun createTypeParameterSubstitutor(
        expectedTypeParameters: List<FirTypeParameterRef>,
        actualTypeParameters: List<FirTypeParameterRef>,
        useSiteSession: FirSession,
        parentSubstitutor: ConeSubstitutor? = null
    ): ConeSubstitutor {
        val substitution = expectedTypeParameters.zip(actualTypeParameters).associate { (expectedParameterRef, actualParameterRef) ->
            expectedParameterRef.symbol to actualParameterRef.symbol.toLookupTag().constructType(emptyArray(), isNullable = false)
        }
        val substitutor = ConeSubstitutorByMap(
            substitution,
            useSiteSession
        )
        if (parentSubstitutor == null) {
            return substitutor
        }
        return substitutor.chain(parentSubstitutor)
    }

    private fun valueParametersCountCompatible(
        expectDeclaration: FirCallableMemberDeclaration,
        actualDeclaration: FirCallableMemberDeclaration,
        expectValueParameters: List<FirValueParameter>,
        actualValueParameters: List<FirValueParameter>
    ): Boolean {
        if (expectValueParameters.size == actualValueParameters.size) return true

        return if (
            expectDeclaration.isAnnotationConstructor(expectDeclaration.moduleData.session) &&
            actualDeclaration.isAnnotationConstructor(actualDeclaration.moduleData.session)
        ) {
            expectValueParameters.isEmpty() && actualValueParameters.all { it.defaultValue != null }
        } else {
            false
        }
    }

    private fun areCompatibleTypeLists(
        expectedTypes: List<ConeKotlinType?>,
        actualTypes: List<ConeKotlinType?>,
        expectSession: FirSession,
        actualSession: FirSession
    ): Boolean {
        for (i in expectedTypes.indices) {
            if (!areCompatibleTypes(expectedTypes[i], actualTypes[i], expectSession, actualSession)) {
                return false
            }
        }
        return true
    }

    private fun areCompatibleTypes(
        expectedType: ConeKotlinType?,
        actualType: ConeKotlinType?,
        expectSession: FirSession,
        actualSession: FirSession
    ): Boolean {
        if (expectedType == null) return actualType == null
        if (actualType == null) return false

        val typeCheckerContext = ConeInferenceContextForExpectActual(expectSession, actualSession).newBaseTypeCheckerContext(
            errorTypesEqualToAnything = false,
            stubTypesEqualToAnything = true
        )
        return AbstractTypeChecker.equalTypes(
            typeCheckerContext,
            expectedType,
            actualType
        )
    }

    private fun areCompatibleModalities(
        expectModality: Modality?,
        actualModality: Modality?,
        expectContainingClassModality: Modality? = null,
        actualContainingClassModality: Modality? = null
    ): Boolean {
        val result = (expectModality == actualModality) ||
                (expectModality == Modality.FINAL && (actualModality == Modality.OPEN || actualModality == Modality.ABSTRACT))
        if (result) return true
        if (expectContainingClassModality == null || actualContainingClassModality == null) return result
        return expectModality == expectContainingClassModality && actualModality == actualContainingClassModality
    }

    private fun areDeclarationsWithCompatibleVisibilities(
        expectStatus: FirDeclarationStatus,
        actualStatus: FirDeclarationStatus
    ): Boolean {
        val compare = Visibilities.compare(expectStatus.visibility, actualStatus.visibility)
        return if (expectStatus.modality != Modality.FINAL) {
            // For overridable declarations visibility should match precisely, see KT-19664
            compare == 0
        } else {
            // For non-overridable declarations actuals are allowed to have more permissive visibility
            compare != null && compare <= 0
        }
    }

    private fun areCompatibleTypeParameters(
        expectTypeParameterRefs: List<FirTypeParameterRef>,
        actualTypeParameterRefs: List<FirTypeParameterRef>,
        actualSession: FirSession,
        expectSession: FirSession,
        substitutor: ConeSubstitutor
    ): ExpectActualCompatibility<FirBasedSymbol<*>> {
        for (i in expectTypeParameterRefs.indices) {
            val expectBounds = expectTypeParameterRefs[i].symbol.fir.bounds.map { it.coneType }
            val actualBounds = actualTypeParameterRefs[i].symbol.fir.bounds.map { it.coneType }
            if (
                expectBounds.size != actualBounds.size ||
                !areCompatibleTypeLists(expectBounds.map(substitutor::substituteOrSelf), actualBounds, expectSession, actualSession)
            ) {
                return ExpectActualCompatibility.Incompatible.TypeParameterUpperBounds
            }
        }

        if (!equalsBy(expectTypeParameterRefs, actualTypeParameterRefs) { it.symbol.fir.variance }) {
            return ExpectActualCompatibility.Incompatible.TypeParameterVariance
        }

        // Removing "reified" from an expected function's type parameter is fine
        if (
            expectTypeParameterRefs.indices.any { i ->
                !expectTypeParameterRefs[i].symbol.fir.isReified && actualTypeParameterRefs[i].symbol.fir.isReified
            }
        ) {
            return ExpectActualCompatibility.Incompatible.TypeParameterReified
        }

        return ExpectActualCompatibility.Compatible
    }

    private fun areCompatibleFunctions(
        expectFunction: FirMemberDeclaration,
        actualFunction: FirMemberDeclaration
    ): ExpectActualCompatibility<FirBasedSymbol<*>> {
        if (!equalBy(expectFunction, actualFunction) { f -> f.isSuspend }) {
            return ExpectActualCompatibility.Incompatible.FunctionModifiersDifferent
        }

        if (
            expectFunction.isExternal && !actualFunction.isExternal ||
            expectFunction.isInfix && !actualFunction.isInfix ||
            expectFunction.isInline && !actualFunction.isInline ||
            expectFunction.isOperator && !actualFunction.isOperator ||
            expectFunction.isTailRec && !actualFunction.isTailRec
        ) {
            return ExpectActualCompatibility.Incompatible.FunctionModifiersNotSubset
        }

        return ExpectActualCompatibility.Compatible
    }

    private fun areCompatibleProperties(a: FirProperty, b: FirProperty): ExpectActualCompatibility<FirBasedSymbol<*>> {
        if (!equalBy(a, b) { p -> p.isVar }) {
            return ExpectActualCompatibility.Incompatible.PropertyKind
        }
        if (!equalBy(a, b) { p -> listOf(p.isConst, p.isLateInit) }) {
            return ExpectActualCompatibility.Incompatible.PropertyModifiers
        }
        return ExpectActualCompatibility.Compatible
    }

    // ---------------------------------------- Utils ----------------------------------------

    private class ConeInferenceContextForExpectActual(val expectSession: FirSession, val actualSession: FirSession) : ConeInferenceContext {
        override val session: FirSession
            get() = actualSession

        override fun areEqualTypeConstructors(c1: TypeConstructorMarker, c2: TypeConstructorMarker): Boolean {
            if (c1 !is ConeClassifierLookupTag || c2 !is ConeClassifierLookupTag) {
                return c1 == c2
            }
            return isExpectedClassAndActualTypeAlias(c1, c2) ||
                    isExpectedClassAndActualTypeAlias(c2, c1) ||
                    c1 == c2
        }

        // For example, expectedTypeConstructor may be the expected class kotlin.text.StringBuilder, while actualTypeConstructor
        // is java.lang.StringBuilder. For the purposes of type compatibility checking, we must consider these types equal here.
        // Note that the case of an "actual class" works as expected though, because the actual class by definition has the same FQ name
        // as the corresponding expected class, so their type constructors are equal as per AbstractClassTypeConstructor#equals
        private fun isExpectedClassAndActualTypeAlias(
            expectLookupTag: ConeClassifierLookupTag,
            actualLookupTag: ConeClassifierLookupTag
        ): Boolean {
            val expectDeclaration = expectLookupTag.toClassLikeDeclaration(expectSession) ?: return false
            val actualDeclaration = actualLookupTag.toClassLikeDeclaration(actualSession) ?: return false

            if (!expectDeclaration.isExpect) return false
            val expectClassId = when (expectDeclaration) {
                is FirRegularClass -> expectDeclaration.symbol.classId
                is FirTypeAlias -> expectDeclaration.expandedTypeRef.coneType.classId
                else -> null
            } ?: return false
            return expectClassId == actualDeclaration.symbol.classId
        }

        private fun ConeClassifierLookupTag.toClassLikeDeclaration(session: FirSession): FirClassLikeDeclaration? {
            val symbol = this.toSymbol(session) as? FirClassLikeSymbol<*> ?: return null
            return symbol.fir
        }
    }

    private fun List<FirValueParameter>.toTypeList(substitutor: ConeSubstitutor): List<ConeKotlinType> {
        return this.map { substitutor.substituteOrSelf(it.returnTypeRef.coneType) }
    }

    private val FirCallableMemberDeclaration.valueParameters: List<FirValueParameter>
        get() = (this as? FirFunction)?.valueParameters ?: emptyList()

    private inline fun <T, K> equalsBy(first: List<T>, second: List<T>, selector: (T) -> K): Boolean {
        for (i in first.indices) {
            if (selector(first[i]) != selector(second[i])) return false
        }

        return true
    }

    private inline fun <T, K> equalBy(first: T, second: T, selector: (T) -> K): Boolean =
        selector(first) == selector(second)

    private fun FirClass.getMembers(
        scopeSession: ScopeSession,
        session: FirSession = moduleData.session
    ): Collection<FirMemberDeclaration> {
        val scope = defaultType().scope(useSiteSession = session, scopeSession, FakeOverrideTypeCalculator.DoNothing)
            ?: return emptyList()
        return mutableListOf<FirMemberDeclaration>().apply {
            for (name in scope.getCallableNames()) {
                scope.getMembersTo(this, name)
            }
            for (name in declarations.mapNotNull { (it as? FirRegularClass)?.name }) {
                addIfNotNull(scope.getSingleClassifier(name)?.fir as? FirRegularClass)
            }
            getConstructorsTo(this, scope)
        }
    }

    private fun FirClass.getConstructors(
        scopeSession: ScopeSession,
        session: FirSession = moduleData.session
    ): Collection<FirConstructor> {
        return mutableListOf<FirConstructor>().apply {
            getConstructorsTo(this, unsubstitutedScope(session, scopeSession, withForcedTypeCalculator = false))
        }
    }

    private fun getConstructorsTo(destination: MutableList<in FirConstructor>, scope: FirTypeScope) {
        scope.getDeclaredConstructors().mapTo(destination) { it.fir }
    }

    private fun FirClass.getMembers(name: Name, scopeSession: ScopeSession): Collection<FirCallableMemberDeclaration> {
        val scope = defaultType().scope(useSiteSession = moduleData.session, scopeSession, FakeOverrideTypeCalculator.DoNothing)
            ?: return emptyList()
        return mutableListOf<FirCallableMemberDeclaration>().apply {
            scope.getMembersTo(this, name)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun FirTypeScope.getMembersTo(
        destination: MutableList<in FirCallableMemberDeclaration>,
        name: Name,
    ) {
        processFunctionsByName(name) { destination.add(it.fir) }
        processPropertiesByName(name) { symbol ->
            (symbol.fir as? FirCallableMemberDeclaration)?.let {
                destination.add(it)
            }
        }
    }

    private val FirMemberDeclaration.name: Name
        get() = (this as FirDeclaration).name

    private val FirDeclaration.name: Name
        get() = when (this) {
            is FirCallableMemberDeclaration -> symbol.callableId.callableName
            is FirRegularClass -> name
            else -> error("Should not be here")
        }

    @OptIn(PrivateForInline::class)
    inline val FirClassLikeDeclaration.isExpect: Boolean
        get() = extractStatus()?.isExpect == true

    @OptIn(PrivateForInline::class)
    inline val FirClassLikeDeclaration.isActual: Boolean
        get() = extractStatus()?.isActual == true

    @PrivateForInline
    @Suppress("NOTHING_TO_INLINE")
    inline fun FirClassLikeDeclaration.extractStatus(): FirDeclarationStatus? {
        return when (this) {
            is FirRegularClass -> status
            is FirTypeAlias -> status
            else -> null
        }
    }

    private val FirMemberDeclaration.symbol: FirBasedSymbol<*>
        get() = (this as FirDeclaration).symbol
}
