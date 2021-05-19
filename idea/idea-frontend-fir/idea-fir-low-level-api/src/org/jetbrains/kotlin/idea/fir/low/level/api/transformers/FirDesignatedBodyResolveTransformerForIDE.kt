/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.ResolutionMode
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.FirProviderInterceptor
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.*
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.element.builder.FirIdeDesignatedBodyResolveTransformerForReturnTypeCalculator
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePhase
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePhaseForClasses
import org.jetbrains.kotlin.idea.fir.low.level.api.util.isTargetCallableDeclarationAndInPhase

internal class FirDesignatedBodyResolveTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    session: FirSession,
    scopeSession: ScopeSession,
    towerDataContextCollector: FirTowerDataContextCollector? = null,
    firProviderInterceptor: FirProviderInterceptor? = null,
) : FirLazyTransformerForIDE, FirBodyResolveTransformer(
    session,
    phase = FirResolvePhase.BODY_RESOLVE,
    implicitTypeOnly = false,
    scopeSession = scopeSession,
    returnTypeCalculator = createReturnTypeCalculatorForIDE(
        session,
        scopeSession,
        ImplicitBodyResolveComputationSession(),
        ::FirIdeDesignatedBodyResolveTransformerForReturnTypeCalculator
    ),
    firTowerDataContextCollector = towerDataContextCollector,
    firProviderInterceptor = firProviderInterceptor,
) {
    private val ideDeclarationTransformer = IDEDeclarationTransformer(designation)

    override fun transformDeclarationContent(declaration: FirDeclaration, data: ResolutionMode): FirDeclaration =
        ideDeclarationTransformer.transformDeclarationContent(this, declaration, data) {
            super.transformDeclarationContent(declaration, data)
        }

    override fun needReplacePhase(firDeclaration: FirDeclaration): Boolean = true

    override fun transformDeclaration() {
        if (designation.isTargetCallableDeclarationAndInPhase(FirResolvePhase.BODY_RESOLVE)) return

        (designation.declaration as? FirCallableDeclaration<*>)?.ensurePhase(FirResolvePhase.CONTRACTS)
        designation.ensurePhaseForClasses(FirResolvePhase.STATUS)

        designation.firFile.transform<FirFile, ResolutionMode>(this, ResolutionMode.ContextIndependent)
        ideDeclarationTransformer.ensureDesignationPassed()
        //TODO Figure out why the phase is not updated
        (designation.declaration as? FirTypeAlias)?.replaceResolvePhase(FirResolvePhase.BODY_RESOLVE)
        designation.declaration.ensureResolved()
    }

    private fun FirDeclaration.ensureResolved() {
        when (this) {
            is FirSimpleFunction -> ensurePhase(FirResolvePhase.BODY_RESOLVE)
            is FirConstructor -> ensurePhase(FirResolvePhase.BODY_RESOLVE)
            is FirProperty -> {
                ensurePhase(FirResolvePhase.BODY_RESOLVE)
                getter?.ensurePhase(FirResolvePhase.BODY_RESOLVE)
                setter?.ensurePhase(FirResolvePhase.BODY_RESOLVE)
            }
            is FirClass<*> -> declarations.forEach { it.ensureResolved() }
            is FirTypeAlias -> ensurePhase(FirResolvePhase.BODY_RESOLVE)
            is FirEnumEntry -> Unit
            is FirField -> ensurePhase(FirResolvePhase.BODY_RESOLVE)
            is FirAnonymousInitializer -> ensurePhase(FirResolvePhase.BODY_RESOLVE)
            else -> error { "Unexpected type: ${this::class.simpleName}" }
        }
    }

}

