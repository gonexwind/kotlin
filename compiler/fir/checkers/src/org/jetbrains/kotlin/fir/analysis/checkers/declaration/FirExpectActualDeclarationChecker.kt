/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.constructors
import org.jetbrains.kotlin.fir.declarations.utils.expandedConeType
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.toSymbol
import org.jetbrains.kotlin.resolve.multiplatform.ExpectActualCompatibility
import org.jetbrains.kotlin.resolve.multiplatform.ExpectActualCompatibility.*
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance

@Suppress("DuplicatedCode")
object FirExpectActualDeclarationChecker : FirBasicDeclarationChecker() {
    override fun check(declaration: FirDeclaration, context: CheckerContext, reporter: DiagnosticReporter) {
        if (!context.session.languageVersionSettings.supportsFeature(LanguageFeature.MultiPlatformProjects)) return
        if (declaration !is FirMemberDeclaration) return
        if (declaration.isActual) {
            checkActualDeclarationHasExpected(declaration, context, reporter)
        }
    }

    private fun <T> checkActualDeclarationHasExpected(
        declaration: T,
        context: CheckerContext,
        reporter: DiagnosticReporter,
        checkActual: Boolean = true
    ) where T : FirDeclaration, T : FirMemberDeclaration {
        val scopeSession = ScopeSession()
        val compatibilityToMembersMap = FirExpectActualResolver.findExpectForActual(declaration, context.session, scopeSession) ?: return
        val symbol = declaration.symbol
        val session = context.session

        checkAmbiguousExpects(declaration, compatibilityToMembersMap, symbol, context, reporter)

        val source = declaration.source
        if (!declaration.isActual) {
            if (compatibilityToMembersMap.allStrongIncompatibilities()) return

            if (Compatible in compatibilityToMembersMap) {
                if (checkActual && requireActualModifier(declaration, session)) {
                    reporter.reportOn(source, FirErrors.ACTUAL_MISSING, context)
                }
                return
            }
        }

        val singleIncompatibility = compatibilityToMembersMap.keys.singleOrNull()
        when {
            singleIncompatibility is Incompatible.ClassScopes -> {
                assert(declaration is FirRegularClass || declaration is FirTypeAlias) {
                    "Incompatible.ClassScopes is only possible for a class or a typealias: $declaration"
                }

                // Do not report "expected members have no actual ones" for those expected members, for which there's a clear
                // (albeit maybe incompatible) single actual suspect, declared in the actual class.
                // This is needed only to reduce the number of errors. Incompatibility errors for those members will be reported
                // later when this checker is called for them
                fun hasSingleActualSuspect(
                    expectedWithIncompatibility: Pair<FirBasedSymbol<*>, Map<Incompatible<FirBasedSymbol<*>>, Collection<FirBasedSymbol<*>>>>
                ): Boolean {
                    val (expectedMember, incompatibility) = expectedWithIncompatibility
                    val actualMember = incompatibility.values.singleOrNull()?.singleOrNull()
                    return actualMember != null &&
                            actualMember.isExplicitActualDeclaration() &&
                            !incompatibility.allStrongIncompatibilities() &&
                            FirExpectActualResolver.findExpectForActual(
                                actualMember.fir as FirMemberDeclaration,
                                session,
                                scopeSession
                            )?.values?.singleOrNull()?.singleOrNull() == expectedMember
                }

                val nonTrivialUnfulfilled = singleIncompatibility.unfulfilled.filterNot(::hasSingleActualSuspect)

                if (nonTrivialUnfulfilled.isNotEmpty()) {
                    reporter.reportOn(source, FirErrors.NO_ACTUAL_CLASS_MEMBER_FOR_EXPECTED_CLASS, symbol, nonTrivialUnfulfilled, context)
                }
            }

            Compatible !in compatibilityToMembersMap -> {
                reporter.reportOn(source, FirErrors.ACTUAL_WITHOUT_EXPECT, symbol, compatibilityToMembersMap.cast(), context)
            }

            else -> {
                val expected = compatibilityToMembersMap[Compatible]!!.first()
                if (expected is FirRegularClass && expected.classKind == ClassKind.ANNOTATION_CLASS) {
                    val klass = declaration.expandedClass(session)
                    val actualConstructor = klass?.declarations?.firstIsInstance<FirConstructor>()
                    val expectedConstructor = expected.constructors.singleOrNull()
                    if (expectedConstructor != null && actualConstructor != null) {
                        checkAnnotationConstructors(source, expectedConstructor, actualConstructor, context, reporter)
                    }
                }
            }
        }
    }

    private fun Map<ExpectActualCompatibility<FirBasedSymbol<*>>, Collection<FirMemberDeclaration>>.mapValuesToSymbols(): Map<Incompatible<FirMemberDeclaration>, Collection<FirBasedSymbol<*>>> {
        require(keys.all { it is Incompatible })
        @Suppress("UNCHECKED_CAST")
        val result = this as Map<Incompatible<FirMemberDeclaration>, Collection<FirMemberDeclaration>>
        return result.mapValues { (_, value) ->
            value.map { it.symbol }
        }
    }

    private val FirMemberDeclaration.symbol: FirBasedSymbol<*>
        get() = (this as FirDeclaration).symbol

    @Suppress("UNUSED_PARAMETER")
    private fun checkAnnotationConstructors(
        source: FirSourceElement?,
        expected: FirConstructor,
        actual: FirConstructor,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        for (expectedValueParameter in expected.valueParameters) {
            // Actual parameter with the same name is guaranteed to exist because this method is only called for compatible annotations
            val actualValueDescriptor = actual.valueParameters.first { it.name == expectedValueParameter.name }

            if (expectedValueParameter.defaultValue != null && actualValueDescriptor.defaultValue != null) {
//              TODO
//                val expectedParameter =
//                    DescriptorToSourceUtils.descriptorToDeclaration(expectedValueParameter) as? KtParameter ?: continue
//
//                val expectedValue = trace.bindingContext.get(BindingContext.COMPILE_TIME_VALUE, expectedParameter.defaultValue)
//                    ?.toConstantValue(expectedValueParameter.type)
//
//                val actualValue =
//                    getActualAnnotationParameterValue(actualValueDescriptor, trace.bindingContext, expectedValueParameter.type)
//                if (expectedValue != actualValue) {
//                    val ktParameter = DescriptorToSourceUtils.descriptorToDeclaration(actualValueDescriptor)
//                    val target = (ktParameter as? KtParameter)?.defaultValue ?: (reportOn as? KtTypeAlias)?.nameIdentifier ?: reportOn
//                    trace.report(Errors.ACTUAL_ANNOTATION_CONFLICTING_DEFAULT_ARGUMENT_VALUE.on(target, actualValueDescriptor))
//                }
            }
        }
    }


    private fun checkAmbiguousExpects(
        actualDeclaration: FirMemberDeclaration,
        compatibility: Map<ExpectActualCompatibility<FirBasedSymbol<*>>, List<FirBasedSymbol<*>>>,
        symbol: FirBasedSymbol<*>,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val filesWithAtLeastWeaklyCompatibleExpects = compatibility.asSequence()
            .filter { (compatibility, _) ->
                compatibility.isCompatibleOrWeakCompatible()
            }
            .map { (_, members) -> members }
            .flatten()
            .map { it.fir.moduleData }
            .sortedBy { it.name.asString() }
            .toList()

        if (filesWithAtLeastWeaklyCompatibleExpects.size > 1) {
            reporter.reportOn(
                actualDeclaration.source,
                FirErrors.AMBIGUOUS_EXPECTS,
                symbol,
                filesWithAtLeastWeaklyCompatibleExpects,
                context
            )
        }
    }

    fun Map<out ExpectActualCompatibility<*>, *>.allStrongIncompatibilities(): Boolean {
        return keys.all { it is Incompatible && it.kind == IncompatibilityKind.STRONG }
    }

    private fun ExpectActualCompatibility<FirBasedSymbol<*>>.isCompatibleOrWeakCompatible(): Boolean {
        return this is Compatible ||
                this is Incompatible && kind == IncompatibilityKind.WEAK
    }

    // we don't require `actual` modifier on
    //  - annotation constructors, because annotation classes can only have one constructor
    //  - inline class primary constructors, because inline class must have primary constructor
    //  - value parameter inside primary constructor of inline class, because inline class must have one value parameter
    private fun requireActualModifier(declaration: FirMemberDeclaration, session: FirSession): Boolean {
        return !declaration.isAnnotationConstructor(session) &&
                !declaration.isPrimaryConstructorOfInlineClass(session) &&
                !isUnderlyingPropertyOfInlineClass(declaration)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isUnderlyingPropertyOfInlineClass(declaration: FirMemberDeclaration): Boolean {
        // TODO
        // return declaration is PropertyDescriptor && declaration.isUnderlyingPropertyOfInlineClass()
        return false
    }

    private fun FirBasedSymbol<*>.isExplicitActualDeclaration(): Boolean {
//        return when (this) {
//            is FirConstructor -> DescriptorToSourceUtils.getSourceFromDescriptor(this) is KtConstructor<*>
//            is FirCallableMemberDeclaration<*> -> kind == CallableMemberDescriptor.Kind.DECLARATION
//            else -> true
//        }
        return true
    }
}

fun FirMemberDeclaration.isAnnotationConstructor(session: FirSession): Boolean {
    if (this !is FirConstructor) return false
    return getConstructedClass(session)?.classKind == ClassKind.ANNOTATION_CLASS
}

fun FirMemberDeclaration.isEnumConstructor(session: FirSession): Boolean {
    if (this !is FirConstructor) return false
    return getConstructedClass(session)?.classKind == ClassKind.ENUM_CLASS
}

fun FirMemberDeclaration.isPrimaryConstructorOfInlineClass(session: FirSession): Boolean {
    if (this !is FirConstructor) return false
    return getConstructedClass(session)?.isInlineOrValueClass() == true && this.isPrimary
}

fun FirConstructor.getConstructedClass(session: FirSession): FirRegularClass? {
    return returnTypeRef.coneType
        .fullyExpandedType(session)
        .toSymbol(session)
        ?.fir as? FirRegularClass
}

fun FirDeclaration.expandedClass(session: FirSession): FirRegularClass? {
    return when (this) {
        is FirTypeAlias -> expandedConeType?.fullyExpandedType(session)?.toSymbol(session)?.fir as? FirRegularClass
        is FirRegularClass -> this
        else -> null
    }
}
