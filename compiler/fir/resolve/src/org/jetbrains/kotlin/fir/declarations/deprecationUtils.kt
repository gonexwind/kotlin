/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.references.FirNamedReference
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

private object FirDeprecationInfoKey : FirDeclarationDataKey()

private var FirAnnotatedDeclaration.deprecationInfoValue: DeprecationInfoForUseSites? by FirDeclarationDataRegistry.data(
    FirDeprecationInfoKey
)

private fun FirAnnotationContainer.getDeprecationInfos(): DeprecationInfoForUseSites =
    //todo inherited deprecations
    getOwnDeprecationInfo()

private fun <T : FirAnnotatedDeclaration> T.getDeprecationInfoForCallSite(vararg sites: AnnotationUseSiteTarget): DeprecationInfo {
    val cached = deprecationInfoValue
    val result =
        if (cached != null) {
            cached
        } else {
            val calculated = getDeprecationInfos()
            deprecationInfoValue = calculated
            calculated
        }

    return result.forCallSite(*sites) ?: EmptyDeprecationInfo
}

fun <T : FirAnnotatedDeclaration> T.getDeprecation(callSite: FirElement?, currentVersion: ApiVersion): AppliedDeprecation? {
    val deprecationInfos = mutableListOf<DeprecationInfo>()
    when (this) {
        is FirProperty ->
            if (callSite is FirVariableAssignment) {
                deprecationInfos.addIfNotNull(setter?.getDeprecationInfoForCallSite())
                deprecationInfos.add(
                    getDeprecationInfoForCallSite(AnnotationUseSiteTarget.PROPERTY_SETTER, AnnotationUseSiteTarget.PROPERTY)
                )
            } else {
                deprecationInfos.addIfNotNull(getter?.getDeprecationInfoForCallSite())
                deprecationInfos.add(
                    getDeprecationInfoForCallSite(AnnotationUseSiteTarget.PROPERTY_GETTER, AnnotationUseSiteTarget.PROPERTY)
                )
            }
        else -> deprecationInfos.add(getDeprecationInfoForCallSite())
    }

    return deprecationInfos.mapNotNull { it.shouldApply(currentVersion) }.firstOrNull()
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

private fun FirAnnotationContainer.getOwnDeprecationInfo(): DeprecationInfoForUseSites {
    val annotations = getAnnotationsByFqName(StandardNames.FqNames.deprecated) + getAnnotationsByFqName(JAVA_DEPRECATED)
    val deprecationByUseSite = annotations.map { deprecated ->
        val deprecationLevel = deprecated.getDeprecationLevel() ?: DeprecationLevelValue.WARNING
        val deprecatedSinceKotlin = getAnnotationByFqName(StandardNames.FqNames.deprecatedSinceKotlin)

        fun versionFieldOrFromDeprecated(name: Name, level: DeprecationLevelValue): ApiVersion? {
            deprecatedSinceKotlin?.getVersionFromArgument(name)?.let { return it }
            return ApiVersion.KOTLIN_1_0.takeIf { deprecatedSinceKotlin == null && level == deprecationLevel }
        }

        val deprecationInfo = DeprecationInfo(
            hiddenSince = versionFieldOrFromDeprecated(HIDDEN_SINCE_NAME, DeprecationLevelValue.HIDDEN),
            errorSince = versionFieldOrFromDeprecated(ERROR_SINCE_NAME, DeprecationLevelValue.ERROR),
            warningSince = versionFieldOrFromDeprecated(WARNING_SINCE_NAME, DeprecationLevelValue.WARNING),
            message = deprecated.getStringArgument(MESSAGE_NAME)
        )
        deprecated.useSiteTarget to deprecationInfo
    }.toMap()

    if (deprecationByUseSite.isEmpty()) return EmptyDeprecationInfosForUseSite

    @Suppress("UNCHECKED_CAST")
    val specificCallSite = deprecationByUseSite.filterKeys { it != null } as Map<AnnotationUseSiteTarget, DeprecationInfo>
    return DeprecationInfoForUseSites(
        deprecationByUseSite[null],
        specificCallSite.takeIf { it.isNotEmpty() }
    )
}

class DeprecationInfo(
    val hiddenSince: ApiVersion? = null,
    val errorSince: ApiVersion? = null,
    val warningSince: ApiVersion? = null,
    val message: String? = null
) {

    fun shouldApply(currentVersion: ApiVersion): AppliedDeprecation? =
        when {
            hiddenSince != null && hiddenSince <= currentVersion -> AppliedDeprecation(DeprecationLevelValue.HIDDEN, message)
            errorSince != null && errorSince <= currentVersion -> AppliedDeprecation(DeprecationLevelValue.ERROR, message)
            warningSince != null && warningSince <= currentVersion -> AppliedDeprecation(DeprecationLevelValue.WARNING, message)
            else -> null
        }

}

class DeprecationInfoForUseSites(
    private val all: DeprecationInfo?,
    private val bySpecificSite: Map<AnnotationUseSiteTarget, DeprecationInfo>?
) {
    fun forCallSite(vararg sites: AnnotationUseSiteTarget): DeprecationInfo? {
        if (bySpecificSite != null) {
            for (site in sites) {
                bySpecificSite[site]?.let { return it }
            }
        }
        return all
    }
}

class AppliedDeprecation(
    val level: DeprecationLevelValue,
    val message: String? = null
) : Comparable<AppliedDeprecation> {
    override fun compareTo(other: AppliedDeprecation): Int = level.compareTo(other.level)
}


// values from kotlin.DeprecationLevel
enum class DeprecationLevelValue {
    WARNING, ERROR, HIDDEN
}

private val EmptyDeprecationInfo = DeprecationInfo()
private val EmptyDeprecationInfosForUseSite = DeprecationInfoForUseSites(null, null)





