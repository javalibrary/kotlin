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
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensureTargetPhase

internal class FirDesignatedTypeResolverTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    session: FirSession,
    scopeSession: ScopeSession,
) : FirLazyTransformerForIDE, FirTypeResolveTransformer(session, scopeSession) {

    private val declarationTransformer = IDEDeclarationTransformer(designation)

    override fun needReplacePhase(firDeclaration: FirDeclaration): Boolean =
        declarationTransformer.needReplacePhase

    override fun <E : FirElement> transformElement(element: E, data: Any?): E {
        return if (element is FirDeclaration && (element is FirRegularClass || element is FirFile)) {
            declarationTransformer.transformDeclarationContent(this, element, data) {
                super.transformElement(element, data)
            }.updateClassIfContentResolved(FirResolvePhase.TYPES)
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
        if (designation.declaration.resolvePhase >= FirResolvePhase.TYPES) return
        designation.ensureTargetPhase(FirResolvePhase.SUPER_TYPES)
        designation.firFile.transform<FirFile, Any?>(this, null)
        declarationTransformer.ensureDesignationPassed()
        designation.ensureTargetPhase(FirResolvePhase.TYPES)
    }
}