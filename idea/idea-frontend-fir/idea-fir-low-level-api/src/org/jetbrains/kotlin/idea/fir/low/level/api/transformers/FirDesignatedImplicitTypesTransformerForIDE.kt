/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.ResolutionMode
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirImplicitAwareBodyResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirTowerDataContextCollector
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.ImplicitBodyResolveComputationSession
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.createReturnTypeCalculatorForIDE
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.element.builder.FirIdeDesignatedBodyResolveTransformerForReturnTypeCalculator
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePhase
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePhaseForClasses
import org.jetbrains.kotlin.idea.fir.low.level.api.util.isTargetCallableDeclarationAndInPhase

internal class FirDesignatedImplicitTypesTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    session: FirSession,
    scopeSession: ScopeSession,
    towerDataContextCollector: FirTowerDataContextCollector? = null,
    implicitBodyResolveComputationSession: ImplicitBodyResolveComputationSession = ImplicitBodyResolveComputationSession(),
) : FirLazyTransformerForIDE, FirImplicitAwareBodyResolveTransformer(
    session,
    implicitBodyResolveComputationSession = implicitBodyResolveComputationSession,
    phase = FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE,
    implicitTypeOnly = true,
    scopeSession = scopeSession,
    firTowerDataContextCollector = towerDataContextCollector,
    returnTypeCalculator = createReturnTypeCalculatorForIDE(
        session,
        scopeSession,
        implicitBodyResolveComputationSession,
        ::FirIdeDesignatedBodyResolveTransformerForReturnTypeCalculator
    )
) {
    private val ideDeclarationTransformer = IDEDeclarationTransformer(designation)

    override fun transformDeclarationContent(declaration: FirDeclaration, data: ResolutionMode): FirDeclaration =
        ideDeclarationTransformer.transformDeclarationContent(this, declaration, data) {
            super.transformDeclarationContent(declaration, data)
        }

    override fun needReplacePhase(firDeclaration: FirDeclaration): Boolean = true

//    private fun FirDeclaration.updatePhaseIfNeeded() {
//        require(resolvePhase >= FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE || resolvePhase == FirResolvePhase.CONTRACTS)
//        if (ideDeclarationTransformer.needReplacePhase && resolvePhase < FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE) {
//            replaceResolvePhase(FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE)
//        }
//    }
//
//    override fun transformAnonymousInitializer(anonymousInitializer: FirAnonymousInitializer, data: ResolutionMode): FirDeclaration {
//        return super.transformAnonymousInitializer(anonymousInitializer, data).also {
//            it.updatePhaseIfNeeded()
//        }
//    }
//
//    override fun transformProperty(property: FirProperty, data: ResolutionMode): FirProperty {
//        return super.transformProperty(property, data).also {
//            if (it.returnTypeRef is FirResolvedTypeRef) it.updatePhaseIfNeeded()
//        }
//    }
//
//    override fun transformSimpleFunction(simpleFunction: FirSimpleFunction, data: ResolutionMode): FirSimpleFunction {
//        return super.transformSimpleFunction(simpleFunction, data).also {
//            if (it.returnTypeRef is FirResolvedTypeRef) it.updatePhaseIfNeeded()
//        }
//    }
//
//    override fun transformField(field: FirField, data: ResolutionMode): FirDeclaration {
//        return super.transformField(field, data).also {
//            if (field.returnTypeRef is FirResolvedTypeRef) it.updatePhaseIfNeeded()
//        }
//    }
//
//    override fun transformConstructor(constructor: FirConstructor, data: ResolutionMode): FirDeclaration {
//        return super.transformConstructor(constructor, data).also {
//            it.updatePhaseIfNeeded()
//        }
//    }
//
//    override fun transformTypeAlias(typeAlias: FirTypeAlias, data: ResolutionMode): FirDeclaration {
//        return super.transformTypeAlias(typeAlias, data).also {
//            it.updatePhaseIfNeeded()
//        }
//    }

    override fun transformDeclaration() {
        if (designation.isTargetCallableDeclarationAndInPhase(FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE)) return
        val callableDeclaration = designation.declaration as? FirCallableDeclaration<*>
        if (callableDeclaration != null) {
            if (callableDeclaration.returnTypeRef is FirResolvedTypeRef) return
            callableDeclaration.ensurePhase(FirResolvePhase.CONTRACTS)
        }
        designation.ensurePhaseForClasses(FirResolvePhase.STATUS)

        designation.firFile.transform<FirFile, ResolutionMode>(this, ResolutionMode.ContextIndependent)
        ideDeclarationTransformer.ensureDesignationPassed()

        designation.declaration.ensureResolved()
    }

    private fun FirDeclaration.ensureResolved() {
        when (this) {
            is FirSimpleFunction -> {
                check(returnTypeRef is FirResolvedTypeRef)
            }
            is FirConstructor -> Unit
            is FirProperty -> {
                check(returnTypeRef is FirResolvedTypeRef)
//                check(getter?.returnTypeRef?.let { it is FirResolvedTypeRef } ?: true)
//                check(setter?.returnTypeRef?.let { it is FirResolvedTypeRef } ?: true)
            }
            is FirClass<*> -> declarations.forEach { it.ensureResolved() }
            is FirTypeAlias -> Unit
            is FirEnumEntry -> Unit
            is FirField -> check(returnTypeRef is FirResolvedTypeRef)
            is FirAnonymousInitializer -> Unit
            else -> error { "Unexpected type: ${this::class.simpleName}" }
        }
    }
}
