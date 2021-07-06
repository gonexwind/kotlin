/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.transformers

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.SessionHolderImpl


@OptIn(AdapterForResolveProcessor::class)
class FirDeprecationResolveProcessor(
    session: FirSession,
    scopeSession: ScopeSession
) : FirTransformerBasedResolveProcessor(session, scopeSession) {
    override val transformer = FirDeprecationResolveTransformer(session, scopeSession)
}

/**
 * Precalculates deprecation info for declarations
 */
class FirDeprecationResolveTransformer(
    override val session: FirSession,
    scopeSession: ScopeSession
) : FirAbstractTreeTransformer<Unit?>(phase = FirResolvePhase.DEPRECATIONS) {

    private val sessionHolder = SessionHolderImpl(session, scopeSession)

    override fun transformRegularClass(regularClass: FirRegularClass, data: Unit?): FirStatement {
        processClassLike(regularClass)
        return super.transformRegularClass(regularClass, data)
    }

    override fun transformTypeAlias(typeAlias: FirTypeAlias, data: Unit?): FirStatement {
        processClassLike(typeAlias)
        return super.transformTypeAlias(typeAlias, data)
    }

    override fun transformSimpleFunction(simpleFunction: FirSimpleFunction, data: Unit?): FirStatement {
        processCallableDeclaration(simpleFunction)
        return super.transformSimpleFunction(simpleFunction, data)
    }

    override fun transformBlock(block: FirBlock, data: Unit?): FirStatement {
        //don't go into bodies
        return block
    }

    override fun transformProperty(property: FirProperty, data: Unit?): FirStatement {
        processCallableDeclaration(property)
        return super.transformProperty(property, data)
    }

    override fun transformConstructor(constructor: FirConstructor, data: Unit?): FirStatement {
        processCallableDeclaration(constructor)
        return super.transformConstructor(constructor, data)
    }

    private fun processCallableDeclaration(simpleFunction: FirCallableDeclaration) {
        if (simpleFunction.deprecation == null) {
            simpleFunction.calculateOrGetDeprecations(sessionHolder)
        }
    }

    private fun processClassLike(regularClass: FirClassLikeDeclaration) {
        if (regularClass.deprecation == null) {
            regularClass.replaceDeprecation(regularClass.getOwnDeprecationInfo(session.languageVersionSettings.apiVersion))
        }
    }
}