/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.FirSymbolOwner
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.toRegularClass
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

object FirDeprecationChecker : FirBasicExpressionChecker() {

    override fun check(expression: FirStatement, context: CheckerContext, reporter: DiagnosticReporter) {
        val resolvable = expression as? FirResolvable ?: return
        val reference = resolvable.calleeReference as? FirResolvedNamedReference ?: return
        val referencedFir = reference.resolvedSymbol.fir
        if (referencedFir !is FirAnnotatedDeclaration) return

        reportDeprecationIfNeeded(expression.source, referencedFir, context, reporter)
    }

    internal fun <T> reportDeprecationIfNeeded(
        source: FirSourceElement?,
        referencedFir: T,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) where T : FirAnnotatedDeclaration, T : FirSymbolOwner<*> {
        val deprecation = getWorstDeprecation(referencedFir, context) ?: return
        val diagnostic = when (deprecation.level) {
            DeprecationLevelValue.ERROR, DeprecationLevelValue.HIDDEN -> FirErrors.DEPRECATION_ERROR
            DeprecationLevelValue.WARNING -> FirErrors.DEPRECATION
        }
        reporter.reportOn(source, diagnostic, referencedFir.symbol, deprecation.message ?: "", context)
    }

    private fun <T : FirAnnotatedDeclaration> getWorstDeprecation(fir: T, context: CheckerContext): AppliedDeprecation? {
        val currentVersion = context.session.languageVersionSettings.apiVersion
        val deprecationInfos = listOfNotNull(
            fir.getDeprecationInfoCached(),
            fir.safeAs<FirConstructor>()?.returnTypeRef?.toRegularClass(context.session)?.getDeprecationInfoCached()
        )
        return deprecationInfos.mapNotNull { it.shouldApply(currentVersion) }.maxOrNull()
    }

}