/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.FirApplySupertypesTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.FirProviderInterceptor
import org.jetbrains.kotlin.fir.resolve.transformers.FirSupertypeResolverVisitor
import org.jetbrains.kotlin.fir.resolve.transformers.SupertypeComputationSession
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignation
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensureTargetPhase

internal class FirDesignatedSupertypeResolverTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    private val session: FirSession,
    private val scopeSession: ScopeSession,
    private val firProviderInterceptor: FirProviderInterceptor?
) : FirLazyTransformerForIDE {

    private val supertypeComputationSession = SupertypeComputationSession()

    private inner class DesignatedFirSupertypeResolverVisitor(classDesignation: FirDeclarationUntypedDesignation) :
        FirSupertypeResolverVisitor(
            session = session,
            supertypeComputationSession = supertypeComputationSession,
            scopeSession = scopeSession,
            scopeForLocalClass = null,
            localClassesNavigationInfo = null,
            firProviderInterceptor = firProviderInterceptor,
        ) {
        val declarationTransformer = IDEDeclarationTransformer(classDesignation)

        override fun visitDeclarationContent(declaration: FirDeclaration, data: Any?) {
            declarationTransformer.visitDeclarationContent(this, declaration, data) {
                super.visitDeclarationContent(declaration, data)
                declaration
            }
        }
    }

    private inner class DesignatedFirApplySupertypesTransformer(classDesignation: FirDeclarationUntypedDesignation) :
        FirApplySupertypesTransformer(supertypeComputationSession) {

        val declarationTransformer = IDEDeclarationTransformer(classDesignation)

        override fun needReplacePhase(firDeclaration: FirDeclaration): Boolean = declarationTransformer.needReplacePhase

        override fun transformDeclarationContent(declaration: FirDeclaration, data: Any?): FirDeclaration {
            return declarationTransformer.transformDeclarationContent(this, declaration, data) {
                super.transformDeclarationContent(declaration, data)
            }.updateClassIfContentResolved(FirResolvePhase.SUPER_TYPES)
        }

        override fun transformTypeAlias(typeAlias: FirTypeAlias, data: Any?): FirDeclaration {
            return super.transformTypeAlias(typeAlias, data).also {
                if (typeAlias.expandedTypeRef is FirResolvedTypeRef) {
                    typeAlias.replaceResolvePhase(FirResolvePhase.SUPER_TYPES)
                }
            }
        }

        override fun <E : FirElement> transformElement(element: E, data: Any?): E {
            if (element is FirDeclaration) {
                element.replaceResolvePhase(FirResolvePhase.SUPER_TYPES)
            }
            return element
        }
    }

    override fun transformDeclaration() {
        if (designation.declaration.resolvePhase >= FirResolvePhase.SUPER_TYPES) return

        check(designation.firFile.resolvePhase >= FirResolvePhase.IMPORTS) {
            "Invalid resolve phase of file. Should be IMPORTS but found ${designation.firFile.resolvePhase}"
        }

        val notResolvableToSuperTypes = designation.declaration !is FirClass<*> && designation.declaration !is FirTypeAlias

        if (notResolvableToSuperTypes) {
            designation.declaration.replaceResolvePhase(FirResolvePhase.SUPER_TYPES)
            return
        }

        val classDesignation = if (notResolvableToSuperTypes) {
            FirDeclarationUntypedDesignation(
                path = designation.path.dropLast(1),
                declaration = designation.path.last(),
                isLocalDesignation = false
            )
        } else designation

        val resolver = DesignatedFirSupertypeResolverVisitor(classDesignation)
        designation.firFile.accept(resolver, null)
        resolver.declarationTransformer.ensureDesignationPassed()

        val applier = DesignatedFirApplySupertypesTransformer(classDesignation)
        designation.firFile.transform<FirElement, Void?>(applier, null)
        applier.declarationTransformer.ensureDesignationPassed()

        designation.ensureTargetPhase(FirResolvePhase.SUPER_TYPES)
    }
}
