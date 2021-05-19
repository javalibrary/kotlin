/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.FirStatusResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.StatusComputationSession
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensureTargetPhase

internal class FirDesignatedStatusResolveTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    private val session: FirSession,
    private val scopeSession: ScopeSession,
) : FirLazyTransformerForIDE {
    private inner class FirDesignatedStatusResolveTransformerForIDE :
        FirStatusResolveTransformer(session, scopeSession, StatusComputationSession.Regular()) {

        val designationTransformer = IDEDeclarationTransformer(designation)

        override fun needReplacePhase(firDeclaration: FirDeclaration): Boolean =
            designationTransformer.needReplacePhase

        override fun transformDeclarationContent(declaration: FirDeclaration, data: FirResolvedDeclarationStatus?): FirDeclaration =
            designationTransformer.transformDeclarationContent(this, declaration, data) {
                super.transformDeclarationContent(declaration, data)
            }.updateClassIfContentResolved(FirResolvePhase.STATUS)
    }

    override fun transformDeclaration() {
        if (designation.declaration.resolvePhase >= FirResolvePhase.STATUS) return
        designation.ensureTargetPhase(FirResolvePhase.TYPES)

        val transformer = FirDesignatedStatusResolveTransformerForIDE()
        designation.firFile.transform<FirElement, FirResolvedDeclarationStatus?>(transformer, null)
        transformer.designationTransformer.ensureDesignationPassed()

        designation.toSequence(includeTarget = true).forEach {
            if (it is FirMemberDeclaration) {
                check(it.status is FirResolvedDeclarationStatus) {
                    "Unresolved status for fir declaration after status phase"
                }
            }
        }

        designation.ensureTargetPhase(FirResolvePhase.STATUS)
    }
}