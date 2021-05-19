/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.FirTypeResolveTransformer
import org.jetbrains.kotlin.fir.types.FirImplicitTypeRef
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.util.*
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePhaseForClasses

internal class FirDesignatedTypeResolverTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    session: FirSession,
    scopeSession: ScopeSession,
) : FirLazyTransformerForIDE, FirTypeResolveTransformer(session, scopeSession) {

    private val declarationTransformer = IDEDeclarationTransformer(designation)

    override fun needReplacePhase(firDeclaration: FirDeclaration): Boolean = true

    override fun <E : FirElement> transformElement(element: E, data: Any?): E {
        return if (element is FirDeclaration && (element is FirRegularClass || element is FirFile)) {
            declarationTransformer.transformDeclarationContent(this, element, data) {
                super.transformElement(element, data)
            }
        } else {
            super.transformElement(element, data)
        }
    }

    override fun transformAnonymousInitializer(anonymousInitializer: FirAnonymousInitializer, data: Any?): FirDeclaration {
        check(anonymousInitializer.resolvePhase >= FirResolvePhase.SUPER_TYPES)
        if (anonymousInitializer.resolvePhase == FirResolvePhase.SUPER_TYPES) {
            anonymousInitializer.replaceResolvePhase(FirResolvePhase.TYPES)
        }
        return super.transformAnonymousInitializer(anonymousInitializer, data)
    }


    override fun transformDeclaration() {
        if (designation.isTargetCallableDeclarationAndInPhase(FirResolvePhase.TYPES)) return
        designation.ensurePhaseForClasses(FirResolvePhase.SUPER_TYPES)
        designation.firFile.transform<FirFile, Any?>(this, null)
        declarationTransformer.ensureDesignationPassed()

        designation.path.forEach { it.ensureResolved() }
        designation.declaration.ensureResolvedDeep()
    }

    private fun FirDeclaration.ensureResolvedDeep() {
        ensureResolved()
        if (this is FirRegularClass) {
            declarations.forEach { it.ensureResolvedDeep() }
        }
    }

    private fun FirDeclaration.ensureResolved() {
        ensurePhase(FirResolvePhase.TYPES)
        when (this) {
            is FirFunction<*> -> {
                check(returnTypeRef is FirResolvedTypeRef || returnTypeRef is FirImplicitTypeRef)
                check(receiverTypeRef?.let { it is FirResolvedTypeRef } ?: true)
                valueParameters.forEach {
                    check(it.returnTypeRef is FirResolvedTypeRef || it.returnTypeRef is FirImplicitTypeRef)
                }
            }
            is FirProperty -> {
                check(returnTypeRef is FirResolvedTypeRef || returnTypeRef is FirImplicitTypeRef)
                check(receiverTypeRef?.let { it is FirResolvedTypeRef } ?: true)
                getter?.ensureResolved()
                setter?.ensureResolved()
            }
            is FirRegularClass -> Unit
            is FirTypeAlias -> Unit
            is FirEnumEntry -> check(returnTypeRef is FirResolvedTypeRef)
            is FirField -> check(returnTypeRef is FirResolvedTypeRef || returnTypeRef is FirImplicitTypeRef)
            is FirAnonymousInitializer -> Unit
            else -> error { "Unexpected type: ${this::class.simpleName}" }
        }
    }
}