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
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensureDesignation
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePhase
import org.jetbrains.kotlin.idea.fir.low.level.api.util.isTargetCallableDeclarationAndInPhase

internal class FirDesignatedStatusResolveTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    private val session: FirSession,
    private val scopeSession: ScopeSession,
) : FirLazyTransformerForIDE {
    private inner class FirDesignatedStatusResolveTransformerForIDE :
        FirStatusResolveTransformer(session, scopeSession, StatusComputationSession.Regular()) {

        val designationTransformer = IDEDeclarationTransformer(designation)

        override fun needReplacePhase(firDeclaration: FirDeclaration): Boolean = true

        override fun transformDeclarationContent(declaration: FirDeclaration, data: FirResolvedDeclarationStatus?): FirDeclaration =
            designationTransformer.transformDeclarationContent(this, declaration, data) {
                super.transformDeclarationContent(declaration, data)
            }
    }

    override fun transformDeclaration() {
        if (designation.isTargetCallableDeclarationAndInPhase(FirResolvePhase.STATUS)) return
        designation.ensureDesignation(FirResolvePhase.TYPES)

        val transformer = FirDesignatedStatusResolveTransformerForIDE()
        designation.firFile.transform<FirElement, FirResolvedDeclarationStatus?>(transformer, null)
        transformer.designationTransformer.ensureDesignationPassed()

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
        ensurePhase(FirResolvePhase.STATUS)
        when (this) {
            is FirSimpleFunction -> check(status is FirResolvedDeclarationStatus)
            is FirConstructor -> check(status is FirResolvedDeclarationStatus)
            is FirProperty -> {
                check(status is FirResolvedDeclarationStatus)
                check(getter?.status?.let { it is FirResolvedDeclarationStatus } ?: true)
                check(setter?.status?.let { it is FirResolvedDeclarationStatus } ?: true)
            }
            is FirRegularClass -> {
                check(status is FirResolvedDeclarationStatus)
            }
            is FirTypeAlias -> check(status is FirResolvedDeclarationStatus)
            is FirEnumEntry -> check(status is FirResolvedDeclarationStatus)
            is FirField -> check(status is FirResolvedDeclarationStatus)
            is FirAnonymousInitializer -> Unit
            else -> error { "Unexpected type: ${this::class.simpleName}" }
        }
    }
}