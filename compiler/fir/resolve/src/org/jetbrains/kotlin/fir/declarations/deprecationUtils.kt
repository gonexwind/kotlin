/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
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
import org.jetbrains.kotlin.utils.keysToMap
import org.jetbrains.kotlin.utils.keysToMapExceptNulls

private val JAVA_DEPRECATED = FqName("java.lang.Deprecated")
private val HIDDEN_SINCE_NAME = Name.identifier("hiddenSince")
private val ERROR_SINCE_NAME = Name.identifier("errorSince")
private val WARNING_SINCE_NAME = Name.identifier("warningSince")
private val MESSAGE_NAME = Name.identifier("message")
private val LEVEL_NAME = Name.identifier("level")

private object FirDeprecationInfoKey : FirDeclarationDataKey(), NotCopyableDataKeyMarker

private var FirAnnotatedDeclaration.deprecationInfoValue: DeprecationInfoForUseSites? by FirDeclarationDataRegistry.data(
    FirDeprecationInfoKey
)

private fun FirAnnotationContainer.getDeprecationInfos(
    sessionHolder: SessionHolder,
    visited: Set<FirAnnotationContainer> = emptySet()
): DeprecationInfoForUseSites {
    if (visited.contains(this)) return EmptyDeprecationInfosForUseSite //break circle for cyclic inheritances
    val ownDeprecation = getOwnDeprecationInfo(sessionHolder.session.languageVersionSettings.apiVersion)
    val inheritedDeprecation = when (this) {
        is FirClass<*> -> {
            if (ownDeprecation.isNotEmpty()) return ownDeprecation
            superConeTypes.collectFirstNotNull { superType ->
                superType.lookupTag
                    .toFirRegularClass(sessionHolder.session)
                    ?.getDeprecationInfosCached(sessionHolder, visited + this)
                    ?.takeIf { it.isNotEmpty() }
            }
        }
        is FirSimpleFunction -> {
            val scope = containingScope(sessionHolder) ?: return ownDeprecation
            val deprecations = scope.getDirectOverriddenFunctions(symbol).map { sym ->
                sym.originalOrSelf().fir.getDeprecationInfosCached(sessionHolder)
            }
            deprecations.reduceOrNull(DeprecationInfoForUseSites::combineMin)
        }
        is FirProperty -> {
            val scope = containingScope(sessionHolder) ?: return ownDeprecation
            scope.getDirectOverriddenProperties(symbol).map { sym ->
                sym.fir.getDeprecationInfosCached(sessionHolder)
            }.reduceOrNull(DeprecationInfoForUseSites::combineMin)
        }
        else -> null
    }
    return if (inheritedDeprecation != null) ownDeprecation.combinePreferLeft(inheritedDeprecation) else ownDeprecation
}

private fun FirCallableMemberDeclaration<*>.containingScope(sessionHolder: SessionHolder): FirTypeScope? {
    val containingClass = containingClass()?.toFirRegularClass(sessionHolder.session) ?: return null
    return containingClass.unsubstitutedScope(sessionHolder.session, sessionHolder.scopeSession, false)
}


private fun FirAnnotatedDeclaration.getDeprecationInfosCached(
    sessionHolder: SessionHolder,
    visited: Set<FirAnnotationContainer> = emptySet()
): DeprecationInfoForUseSites {
    val cached = deprecationInfoValue
    return if (cached != null) {
        cached
    } else {
        val calculated = getDeprecationInfos(sessionHolder, visited)
        deprecationInfoValue = calculated
        calculated
    }
}


private fun <T : FirAnnotatedDeclaration> T.getDeprecationForCallSite(
    sessionHolder: SessionHolder,
    vararg sites: AnnotationUseSiteTarget
): Deprecation? = getDeprecationInfosCached(sessionHolder).forCallSite(*sites)

fun <T : FirAnnotatedDeclaration> T.getDeprecation(callSite: FirElement?, sessionHolder: SessionHolder): Deprecation? {
    val deprecationInfos = mutableListOf<Deprecation>()
    when (this) {
        is FirProperty ->
            if (callSite is FirVariableAssignment) {
//                deprecationInfos.addIfNotNull(setter?.getDeprecationForCallSite(sessionHolder))
                deprecationInfos.addIfNotNull(
                    getDeprecationForCallSite(sessionHolder, AnnotationUseSiteTarget.PROPERTY_SETTER, AnnotationUseSiteTarget.PROPERTY)
                )
            } else {
//                deprecationInfos.addIfNotNull(getter?.getDeprecationForCallSite(sessionHolder))
                deprecationInfos.addIfNotNull(
                    getDeprecationForCallSite(sessionHolder, AnnotationUseSiteTarget.PROPERTY_GETTER, AnnotationUseSiteTarget.PROPERTY)
                )
            }
        else -> deprecationInfos.addIfNotNull(getDeprecationForCallSite(sessionHolder))
    }

    return deprecationInfos.firstOrNull()
}

private fun FirAnnotationCall.getStringArgument(name: Name): String? =
    findArgumentByName(name)?.let { expression ->
        expression.safeAs<FirConstExpression<*>>()?.value.safeAs()
    }

private fun FirAnnotationCall.getVersionFromArgument(name: Name): ApiVersion? =
    getStringArgument(name)?.let { ApiVersion.parse(it) }

private fun FirAnnotationCall.getDeprecationLevel(): DeprecationLevelValue? =
    findArgumentByName(LEVEL_NAME)?.let { argument ->
        val targetExpression = argument as? FirQualifiedAccessExpression ?: return null
        val targetName = (targetExpression.calleeReference as? FirNamedReference)?.name?.asString() ?: return null
        DeprecationLevelValue.values().find { it.name == targetName }
    }

private fun FirAnnotationContainer.getOwnDeprecationInfo(currentVersion: ApiVersion): DeprecationInfoForUseSites {
    val annotations = getAnnotationsByFqName(StandardNames.FqNames.deprecated) + getAnnotationsByFqName(JAVA_DEPRECATED)
    val deprecationByUseSite = mutableMapOf<AnnotationUseSiteTarget?, Deprecation>()
    annotations.mapNotNull { deprecated ->
        val deprecationLevel = deprecated.getDeprecationLevel() ?: DeprecationLevelValue.WARNING
        val deprecatedSinceKotlin = getAnnotationByFqName(StandardNames.FqNames.deprecatedSinceKotlin)

        fun levelApplied(name: Name, level: DeprecationLevelValue): DeprecationLevelValue? {
            deprecatedSinceKotlin?.getVersionFromArgument(name)?.takeIf { it <= currentVersion }?.let { return level }
            return level.takeIf { deprecatedSinceKotlin == null && level == deprecationLevel }
        }

        val appliedLevel = (levelApplied(HIDDEN_SINCE_NAME, DeprecationLevelValue.HIDDEN)
            ?: levelApplied(ERROR_SINCE_NAME, DeprecationLevelValue.ERROR)
            ?: levelApplied(WARNING_SINCE_NAME, DeprecationLevelValue.WARNING))

        appliedLevel?.let {
            deprecated.useSiteTarget to Deprecation(it, deprecated.getStringArgument(MESSAGE_NAME))
        }
    }.toMap(deprecationByUseSite)

    if (this is FirProperty) {
        setter?.getOwnDeprecationInfo(currentVersion)?.all?.let { deprecationByUseSite.put(AnnotationUseSiteTarget.PROPERTY_SETTER, it) }
        getter?.getOwnDeprecationInfo(currentVersion)?.all?.let { deprecationByUseSite.put(AnnotationUseSiteTarget.PROPERTY_GETTER, it) }
    }

    if (deprecationByUseSite.isEmpty()) return EmptyDeprecationInfosForUseSite

    @Suppress("UNCHECKED_CAST")
    val specificCallSite = deprecationByUseSite.filterKeys { it != null } as Map<AnnotationUseSiteTarget, Deprecation>
    return DeprecationInfoForUseSites(
        deprecationByUseSite[null],
        specificCallSite.takeIf { it.isNotEmpty() }
    )
}

class DeprecationInfoForUseSites(
    val all: Deprecation?,
    private val bySpecificSite: Map<AnnotationUseSiteTarget, Deprecation>?
) {
    fun forCallSite(vararg sites: AnnotationUseSiteTarget): Deprecation? {
        if (bySpecificSite != null) {
            for (site in sites) {
                bySpecificSite[site]?.let { return it }
            }
        }
        return all
    }

    fun isEmpty(): Boolean = all == null && bySpecificSite == null
    fun isNotEmpty(): Boolean = !isEmpty()

    fun combineMin(other: DeprecationInfoForUseSites): DeprecationInfoForUseSites {
        if (isEmpty() || isEmpty()) return EmptyDeprecationInfosForUseSite

        return DeprecationInfoForUseSites(
            if (all == null || other.all == null) null else minOf(all, other.all),
            if (bySpecificSite == null || other.bySpecificSite == null) {
                null
            } else {
                bySpecificSite.keys.intersect(other.bySpecificSite.keys).keysToMap { target ->
                    minOf(bySpecificSite[target]!!, other.bySpecificSite[target]!!)
                }
            }

        )
    }

    fun combinePreferLeft(other: DeprecationInfoForUseSites): DeprecationInfoForUseSites {
        return DeprecationInfoForUseSites(
            all ?: other.all,
            if (bySpecificSite == null || other.bySpecificSite == null) {
                bySpecificSite ?: other.bySpecificSite
            } else {
                bySpecificSite.keys.union(other.bySpecificSite.keys).keysToMapExceptNulls { target ->
                    bySpecificSite[target] ?: other.bySpecificSite[target]
                }
            }

        )
    }
}

class Deprecation(
    val level: DeprecationLevelValue,
    val message: String? = null
) : Comparable<Deprecation> {
    override fun compareTo(other: Deprecation): Int = level.compareTo(other.level)
}


// values from kotlin.DeprecationLevel
enum class DeprecationLevelValue {
    WARNING, ERROR, HIDDEN
}

private val EmptyDeprecationInfosForUseSite = DeprecationInfoForUseSites(null, null)


private fun <T, R> Collection<T>.collectFirstNotNull(f: (T) -> R?): R? {
    for (el in this) {
        val r = f(el)
        if (r != null) return r
    }
    return null
}
