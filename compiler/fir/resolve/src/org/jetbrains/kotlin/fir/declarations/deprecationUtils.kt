/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

private val JAVA_DEPRECATED = FqName("java.lang.Deprecated")
private val HIDDEN_SINCE_NAME = Name.identifier("hiddenSince")
private val ERROR_SINCE_NAME = Name.identifier("errorSince")
private val WARNING_SINCE_NAME = Name.identifier("warningSince")
private val MESSAGE_NAME = Name.identifier("message")
private val LEVEL_NAME = Name.identifier("level")

private object FirDeprecationInfoKey : FirDeclarationDataKey()

private var FirAnnotatedDeclaration.deprecationInfoValue: DeprecationInfo? by FirDeclarationDataRegistry.data(
    FirDeprecationInfoKey
)

private fun FirAnnotationContainer.getDeprecationInfo(): DeprecationInfo =
    //todo inherited deprecations
    getOwnDeprecationInfo() ?: EmptyDeprecationInfo

fun <T : FirAnnotatedDeclaration> T.getDeprecationInfoCached(): DeprecationInfo {
    val cached = deprecationInfoValue
    if (cached != null) return cached
    val calculated = getDeprecationInfo()
    deprecationInfoValue = calculated
    return calculated
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

private fun FirAnnotationContainer.getOwnDeprecationInfo(): DeprecationInfo? {
    val deprecated = getAnnotationByFqName(StandardNames.FqNames.deprecated) ?: getAnnotationByFqName(JAVA_DEPRECATED)
    if (deprecated != null) {
        val deprecationLevel = deprecated.getDeprecationLevel()
        val deprecatedSinceKotlin = getAnnotationByFqName(StandardNames.FqNames.deprecatedSinceKotlin)

        fun versionFieldOrFromDeprecated(name: Name, level: DeprecationLevelValue): ApiVersion? {
            deprecatedSinceKotlin?.getVersionFromArgument(name)?.let { return it }
            return ApiVersion.KOTLIN_1_0.takeIf { level == deprecationLevel }
        }

        return DeprecationInfo(
            hiddenSince = versionFieldOrFromDeprecated(HIDDEN_SINCE_NAME, DeprecationLevelValue.HIDDEN),
            errorSince = versionFieldOrFromDeprecated(ERROR_SINCE_NAME, DeprecationLevelValue.ERROR),
            warningSince = versionFieldOrFromDeprecated(WARNING_SINCE_NAME, DeprecationLevelValue.WARNING),
            message = deprecated.getStringArgument(MESSAGE_NAME)
        )
    }

    //TODO old FE has deprecation source, eg coroutines related
    //see org.jetbrains.kotlin.resolve.deprecation.DeprecationResolver.addDeprecationIfPresent
    return null
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





