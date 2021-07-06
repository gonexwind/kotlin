/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.resolve.SessionHolder
import org.jetbrains.kotlin.fir.resolve.toFirRegularClass
import org.jetbrains.kotlin.fir.scopes.FirTypeScope
import org.jetbrains.kotlin.fir.scopes.getDirectOverriddenFunctions
import org.jetbrains.kotlin.fir.scopes.getDirectOverriddenProperties
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

private val JAVA_DEPRECATED = FqName("java.lang.Deprecated")
private val HIDDEN_SINCE_NAME = Name.identifier("hiddenSince")
private val ERROR_SINCE_NAME = Name.identifier("errorSince")
private val WARNING_SINCE_NAME = Name.identifier("warningSince")
private val MESSAGE_NAME = Name.identifier("message")
private val LEVEL_NAME = Name.identifier("level")

private val JAVA_ORIGINS = setOf(FirDeclarationOrigin.Java, FirDeclarationOrigin.Enhancement)

fun <T : FirAnnotatedDeclaration> T.getDeprecation(callSite: FirElement?, sessionHolder: SessionHolder): Deprecation? {
    val deprecationInfos = mutableListOf<Deprecation>()
    when (this) {
        is FirProperty ->
            if (callSite is FirVariableAssignment) {
                deprecationInfos.addIfNotNull(
                    getDeprecationForCallSite(sessionHolder, AnnotationUseSiteTarget.PROPERTY_SETTER, AnnotationUseSiteTarget.PROPERTY)
                )
            } else {
                deprecationInfos.addIfNotNull(
                    getDeprecationForCallSite(sessionHolder, AnnotationUseSiteTarget.PROPERTY_GETTER, AnnotationUseSiteTarget.PROPERTY)
                )
            }
        else -> deprecationInfos.addIfNotNull(getDeprecationForCallSite(sessionHolder))
    }

    return deprecationInfos.firstOrNull()
}

private object FirDeprecationInfoKey : FirDeclarationDataKey(), NotCopyableDataKeyMarker

private var FirAnnotatedDeclaration.deprecationInfoValue: DeprecationsPerUseSite? by FirDeclarationDataRegistry.data(
    FirDeprecationInfoKey
)

private fun FirAnnotationContainer.getDeprecationInfos(
    sessionHolder: SessionHolder,
    visited: Set<FirAnnotationContainer> = emptySet()
): DeprecationsPerUseSite {
    if (visited.contains(this)) return EmptyDeprecationsPerUseSite //break circle for cyclic inheritances
    val ownDeprecation = getOwnDeprecationInfo(sessionHolder.session.languageVersionSettings.apiVersion)
    val inheritedDeprecation = when (this) {
        is FirSimpleFunction -> {
            val scope = containingScope(sessionHolder) ?: return ownDeprecation
            val deprecations = scope.getDirectOverriddenFunctions(symbol).map { sym ->
                sym.originalOrSelf().fir.calculateOrGetDeprecations(sessionHolder)
            }
            deprecations.reduceOrNull(DeprecationsPerUseSite::combineMin)
        }
        is FirProperty -> {
            val scope = containingScope(sessionHolder) ?: return ownDeprecation
            scope.getDirectOverriddenProperties(symbol).map { sym ->
                sym.fir.calculateOrGetDeprecations(sessionHolder)
            }.reduceOrNull(DeprecationsPerUseSite::combineMin)
        }
        else -> null
    }
    return if (inheritedDeprecation != null) ownDeprecation.combinePreferLeft(inheritedDeprecation.inheritableOnly()) else ownDeprecation
}

private fun FirCallableMemberDeclaration.containingScope(sessionHolder: SessionHolder): FirTypeScope? {
    val containingClass = containingClass()?.toFirRegularClass(sessionHolder.session) ?: return null
    return containingClass.unsubstitutedScope(sessionHolder.session, sessionHolder.scopeSession, false)
}

fun FirAnnotatedDeclaration.calculateOrGetDeprecations(
    sessionHolder: SessionHolder,
    visited: Set<FirAnnotationContainer> = emptySet()
): DeprecationsPerUseSite {
    val result = when (this) {
        is FirCallableDeclaration -> {
            if (deprecation == null) {
                replaceDeprecation(getDeprecationInfos(sessionHolder, visited))
            }
            deprecation
        }
        is FirClassLikeDeclaration -> {
            if (deprecation == null) {
                replaceDeprecation(getDeprecationInfos(sessionHolder, visited))
            }
            deprecation
        }

        else -> null
    }
    return result ?: EmptyDeprecationsPerUseSite
}


private fun <T : FirAnnotatedDeclaration> T.getDeprecationForCallSite(
    sessionHolder: SessionHolder,
    vararg sites: AnnotationUseSiteTarget
): Deprecation? = calculateOrGetDeprecations(sessionHolder).forUseSite(*sites)

private fun FirAnnotationCall.getStringArgument(name: Name): String? =
    findArgumentByName(name)?.let { expression ->
        expression.safeAs<FirConstExpression<*>>()?.value.safeAs()
    }

private fun FirAnnotationCall.getVersionFromArgument(name: Name): ApiVersion? =
    getStringArgument(name)?.let { ApiVersion.parse(it) }

private fun FirAnnotationCall.getDeprecationLevel(): DeprecationLevelValue? {
    //take last because Annotation might be not resolved yet and arguments passed without explicit names
    val arg = findArgumentByName(LEVEL_NAME) ?: arguments.lastOrNull()
    return arg?.let { argument ->
        val targetExpression = argument as? FirQualifiedAccessExpression ?: return null
        val targetName = (targetExpression.calleeReference as? FirNamedReference)?.name?.asString() ?: return null
        DeprecationLevelValue.values().find { it.name == targetName }
    }
}


fun FirAnnotationContainer.getOwnDeprecationInfo(currentVersion: ApiVersion): DeprecationsPerUseSite {
    val deprecationByUseSite = mutableMapOf<AnnotationUseSiteTarget?, Deprecation>()
    val fromJava = JAVA_ORIGINS.contains(this.safeAs<FirDeclaration>()?.origin)
    annotations.extractDeprecationInfoPerUseSite(currentVersion, fromJava).toMap(deprecationByUseSite)

    if (this is FirProperty) {
        (/*setter?.deprecation ?:*/ setter?.getOwnDeprecationInfo(currentVersion))?.all
            ?.let { deprecationByUseSite.put(AnnotationUseSiteTarget.PROPERTY_SETTER, it) }

        (/*getter?.deprecation ?:*/ getter?.getOwnDeprecationInfo(currentVersion))?.all
            ?.let { deprecationByUseSite.put(AnnotationUseSiteTarget.PROPERTY_GETTER, it) }
    }

    if (deprecationByUseSite.isEmpty()) return EmptyDeprecationsPerUseSite

    @Suppress("UNCHECKED_CAST")
    val specificCallSite = deprecationByUseSite.filterKeys { it != null } as Map<AnnotationUseSiteTarget, Deprecation>
    return DeprecationsPerUseSite(
        deprecationByUseSite[null],
        specificCallSite.takeIf { it.isNotEmpty() }
    )
}

fun List<FirAnnotationCall>.getOwnDeprecationInfoFromAnnotations(currentVersion: ApiVersion, fromJava: Boolean): DeprecationsPerUseSite {
    val deprecationByUseSite = extractDeprecationInfoPerUseSite(currentVersion, fromJava).toMap()
    //todo remove copy-paste
    if (deprecationByUseSite.isEmpty()) return EmptyDeprecationsPerUseSite

    @Suppress("UNCHECKED_CAST")
    val specificCallSite = deprecationByUseSite.filterKeys { it != null } as Map<AnnotationUseSiteTarget, Deprecation>
    return DeprecationsPerUseSite(
        deprecationByUseSite[null],
        specificCallSite.takeIf { it.isNotEmpty() }
    )
}

private fun List<FirAnnotationCall>.extractDeprecationInfoPerUseSite(
    currentVersion: ApiVersion,
    fromJava: Boolean
): List<Pair<AnnotationUseSiteTarget?, Deprecation>> {
    val annotations = getAnnotationsByFqName(StandardNames.FqNames.deprecated).map { it to false } +
            getAnnotationsByFqName(JAVA_DEPRECATED).map { it to true }
    return annotations.mapNotNull { (deprecated, fromJavaAnnotation) ->
        val deprecationLevel = deprecated.getDeprecationLevel() ?: DeprecationLevelValue.WARNING
        val deprecatedSinceKotlin = getAnnotationsByFqName(StandardNames.FqNames.deprecatedSinceKotlin).firstOrNull()

        fun levelApplied(name: Name, level: DeprecationLevelValue): DeprecationLevelValue? {
            deprecatedSinceKotlin?.getVersionFromArgument(name)?.takeIf { it <= currentVersion }?.let { return level }
            return level.takeIf { deprecatedSinceKotlin == null && level == deprecationLevel }
        }

        val appliedLevel = (levelApplied(HIDDEN_SINCE_NAME, DeprecationLevelValue.HIDDEN)
            ?: levelApplied(ERROR_SINCE_NAME, DeprecationLevelValue.ERROR)
            ?: levelApplied(WARNING_SINCE_NAME, DeprecationLevelValue.WARNING))

        appliedLevel?.let {
            val inheritable = !fromJavaAnnotation && !fromJava
            deprecated.useSiteTarget to Deprecation(it, inheritable, deprecated.getStringArgument(MESSAGE_NAME))
        }
    }
}

