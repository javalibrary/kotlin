/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.FirApplySupertypesTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.FirProviderInterceptor
import org.jetbrains.kotlin.fir.resolve.transformers.FirSupertypeResolverVisitor
import org.jetbrains.kotlin.fir.resolve.transformers.SupertypeComputationSession
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignation
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePhase

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

        override fun needReplacePhase(firDeclaration: FirDeclaration): Boolean = true

        override fun transformDeclarationContent(declaration: FirDeclaration, data: Any?): FirDeclaration {
            return declarationTransformer.transformDeclarationContent(this, declaration, data) {
                super.transformDeclarationContent(declaration, data)
            }
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

        val isResolvableTarget = designation.declaration is FirClass<*> || designation.declaration is FirTypeAlias

        if (!isResolvableTarget && designation.path.isEmpty()) return

        val classDesignation = if (isResolvableTarget) {
            designation
        } else {
            if (designation.path.isEmpty()) return
            FirDeclarationUntypedDesignation(
                path = designation.path.dropLast(1),
                declaration = designation.path.last(),
                isLocalDesignation = false,
            )
        }

        val resolver = DesignatedFirSupertypeResolverVisitor(classDesignation)
        designation.firFile.accept(resolver, null)
        resolver.declarationTransformer.ensureDesignationPassed()

        val applier = DesignatedFirApplySupertypesTransformer(classDesignation)
        designation.firFile.transform<FirElement, Void?>(applier, null)
        applier.declarationTransformer.ensureDesignationPassed()

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
        ensurePhase(FirResolvePhase.SUPER_TYPES)
        when (this) {
            is FirFunction<*> -> Unit
            is FirProperty -> Unit
            is FirRegularClass -> {
                check(superTypeRefs.all { it is FirResolvedTypeRef })
            }
            is FirTypeAlias -> Unit
            is FirEnumEntry -> Unit
            is FirField -> Unit
            is FirAnonymousInitializer -> Unit
            else -> error { "Unexpected type: ${this::class.simpleName}" }
        }
    }
}
