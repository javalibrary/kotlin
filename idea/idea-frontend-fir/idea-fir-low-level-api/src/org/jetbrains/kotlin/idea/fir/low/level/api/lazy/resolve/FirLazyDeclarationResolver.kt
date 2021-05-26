/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.lazy.resolve

import org.jetbrains.kotlin.fir.FirFakeSourceElementKind
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.FirProvider
import org.jetbrains.kotlin.fir.resolve.symbolProvider
import org.jetbrains.kotlin.fir.resolve.transformers.FirProviderInterceptor
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirTowerDataContextCollector
import org.jetbrains.kotlin.fir.types.FirImplicitTypeRef
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.idea.fir.low.level.api.api.*
import org.jetbrains.kotlin.idea.fir.low.level.api.element.builder.getNonLocalContainingOrThisDeclaration
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.FirFileBuilder
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.ModuleFileCache
import org.jetbrains.kotlin.idea.fir.low.level.api.providers.firIdeProvider
import org.jetbrains.kotlin.idea.fir.low.level.api.transformers.*
import org.jetbrains.kotlin.idea.fir.low.level.api.util.*
import org.jetbrains.kotlin.idea.util.ifTrue
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

enum class ResolveType {
    FileAnnotations,
    CallableReturnType,
    ClassSuperTypes,
    DeclarationStatus,
    ValueParametersTypes,
    TypeParametersTypes,
    AnnotationType,
    AnnotationParameters,
    CallableBodyResolve,
    ResolveForMemberScope,
    ResolveForSuperMembers,
    CallableContracts,
    NoResolve,
}

internal class FirLazyDeclarationResolver(
    private val firFileBuilder: FirFileBuilder
) {
    fun lazyResolveDeclaration(
        firDeclaration: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        toResolveType: ResolveType,
        scopeSession: ScopeSession = ScopeSession(),
        checkPCE: Boolean = false,
    ) {
        if (firDeclaration.resolvePhase == FirResolvePhase.BODY_RESOLVE) return

        val firFile = firDeclaration.getContainingFile()
            ?: error("FirFile was not found for\n${firDeclaration.render()}")

        fun tryResolveAsLocalDeclaration(): Boolean {
            val nonLocalDeclarationToResolve = firDeclaration.getNonLocalDeclarationToResolve(
                firFile.moduleData.session.firIdeProvider, moduleFileCache
            )

            if (firDeclaration != nonLocalDeclarationToResolve) {
                runLazyDesignatedResolveWithoutLock(
                    designation = nonLocalDeclarationToResolve.collectDesignation(firFile),
                    scopeSession = scopeSession,
                    toPhase = FirResolvePhase.BODY_RESOLVE,
                    checkPCE = checkPCE,
                )
                check(firDeclaration is FirResolvedTypeRef)
                return true
            }
            return false
        }

        firFileBuilder.runCustomResolveUnderLock(firFile, moduleFileCache) {
            if (tryResolveAsLocalDeclaration()) return@runCustomResolveUnderLock

            when (toResolveType) {
                ResolveType.CallableReturnType -> {
                    require(firDeclaration is FirCallableDeclaration<*>) {
                        firDeclaration.errorMessage<FirCallableDeclaration<*>>(ResolveType.CallableReturnType)
                    }
                    resolveToCallableReturnType(firDeclaration, firFile, scopeSession, checkPCE = false)
                }
                else -> error("")
            }
        }
    }

    private inline fun <reified T : FirDeclaration> FirDeclaration.errorMessage(type: ResolveType) =
        "$type require to be called on ${T::class.simpleName} but ${this::class.simpleName}"

    private fun FirDeclarationUntypedDesignation.minPhase(): FirResolvePhase {
        val pathOrDeclarationPhase = path.minByOrNull { it.resolvePhase }?.resolvePhase ?: declaration.resolvePhase
        return minOf(pathOrDeclarationPhase, declaration.resolvePhase)
    }

    fun resolveToCallableBodyResolve(
        firDeclaration: FirCallableDeclaration<*>,
        firFile: FirFile,
        scopeSession: ScopeSession = ScopeSession(),
        checkPCE: Boolean,
    ) {
        if (firDeclaration.resolvePhase == FirResolvePhase.BODY_RESOLVE) return

        val designation = firDeclaration.collectDesignation(firFile)
        check(firDeclaration.resolvePhase < FirResolvePhase.BODY_RESOLVE) { "XXX" }

        runLazyDesignatedResolveWithoutLock(
            designation = designation,
            scopeSession = scopeSession,
            toPhase = FirResolvePhase.BODY_RESOLVE,
            checkPCE = checkPCE,
        )

        check(firDeclaration.resolvePhase >= FirResolvePhase.BODY_RESOLVE) { "XXX" }
        check(firDeclaration is FirResolvedTypeRef)
    }

    fun resolveToCallableReturnType(
        firDeclaration: FirCallableDeclaration<*>,
        firFile: FirFile,
        scopeSession: ScopeSession = ScopeSession(),
        checkPCE: Boolean,
    ) {
        if (firDeclaration.returnTypeRef is FirResolvedTypeRef) return

        val targetPhase =
            if (firDeclaration.returnTypeRef is FirImplicitTypeRef) FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE else FirResolvePhase.TYPES

        val designation = firDeclaration.collectDesignation(firFile)
        check(firDeclaration.resolvePhase < targetPhase) { "XXX" }

        runLazyDesignatedResolveWithoutLock(
            designation = designation,
            scopeSession = scopeSession,
            toPhase = targetPhase,
            checkPCE = checkPCE,
        )

        check(firDeclaration.resolvePhase >= targetPhase) { "XXX" }
        check(firDeclaration.returnTypeRef is FirResolvedTypeRef)
    }

    private fun runLazyDesignatedResolveWithoutLock(
        designation: FirDeclarationUntypedDesignationWithFile,
        scopeSession: ScopeSession,
        toPhase: FirResolvePhase,
        checkPCE: Boolean,
        isOnAirResolve: Boolean = false,
        towerDataContextCollector: FirTowerDataContextCollector? = null
    ) {
        check(!designation.isLocalDesignation) { "Could not resolve local designation" }

        //This needed to override standard symbol resolve in supertype transformer with adding on-air created symbols
        val firProviderInterceptor = isOnAirResolve.ifTrue {
            FirProviderInterceptorForIDE.createForFirElement(
                session = designation.firFile.moduleData.session,
                firFile = designation.firFile,
                element = designation.declaration
            )
        }

        var currentPhase = designation.minPhase()

        while (currentPhase < toPhase) {
            currentPhase = currentPhase.next
            if (currentPhase.pluginPhase) continue
            if (checkPCE) checkCanceled()
            FirLazyBodiesCalculator.calculateLazyBodiesInsideIfNeeded(designation, currentPhase)

            runLazyResolvePhase(
                phase = currentPhase,
                scopeSession = scopeSession,
                designation = designation,
                towerDataContextCollector = towerDataContextCollector,
                firProviderInterceptor = firProviderInterceptor
            )
        }
    }


    /**
     * Fully resolve file annotations (synchronized)
     * @see resolveFileAnnotationsWithoutLock not synchronized
     */
    fun resolveFileAnnotations(
        firFile: FirFile,
        annotations: List<FirAnnotationCall>,
        moduleFileCache: ModuleFileCache,
        scopeSession: ScopeSession = ScopeSession(),
        collector: FirTowerDataContextCollector? = null,
    ) {
        lazyResolveDeclaration(
            declaration = firFile,
            moduleFileCache = moduleFileCache,
            toPhase = FirResolvePhase.IMPORTS,
            checkPCE = false,
            reresolveFile = false
        )
        firFileBuilder.runCustomResolveUnderLock(firFile, moduleFileCache) {
            resolveFileAnnotationsWithoutLock(firFile, annotations, scopeSession, collector)
        }
    }

    /**
     * Fully resolve file annotations (not synchronized)
     * @see resolveFileAnnotations synchronized version
     */
    private fun resolveFileAnnotationsWithoutLock(
        firFile: FirFile,
        annotations: List<FirAnnotationCall>,
        scopeSession: ScopeSession,
        collector: FirTowerDataContextCollector? = null,
    ) {
        FirFileAnnotationsResolveTransformer(
            firFile = firFile,
            annotations = annotations,
            session = firFile.moduleData.session,
            scopeSession = scopeSession,
            firTowerDataContextCollector = collector,
        ).transformDeclaration()
    }

    private fun getResolvableDeclaration(declaration: FirDeclaration, moduleFileCache: ModuleFileCache): FirDeclaration {
        if (declaration is FirPropertyAccessor || declaration is FirTypeParameter || declaration is FirValueParameter) {
            val ktContainingResolvableDeclaration = when (val psi = declaration.psi) {
                is KtPropertyAccessor -> psi.property
                is KtProperty -> psi
                is KtParameter, is KtTypeParameter -> psi.getNonLocalContainingOrThisDeclaration()
                    ?: error("Cannot find containing declaration for KtParameter")
                is KtCallExpression -> {
                    check(declaration.source?.kind == FirFakeSourceElementKind.DefaultAccessor)
                    val delegationCall = psi.parent as KtPropertyDelegate
                    delegationCall.parent as KtProperty
                }
                null -> error("Cannot find containing declaration for KtParameter")
                else -> error("Invalid source of property accessor ${psi::class}")
            }
            return ktContainingResolvableDeclaration.findSourceNonLocalFirDeclaration(
                firFileBuilder = firFileBuilder,
                firSymbolProvider = declaration.moduleData.session.symbolProvider,
                moduleFileCache = moduleFileCache
            )
        }

        return declaration
    }

    private fun FirDeclaration.firResolvePhaseWithSubDeclarations(): FirResolvePhase {
        fun <T : FirDeclaration> T.containingDeclarations() = when (this) {
            is FirClass<*> -> declarations
            is FirAnonymousObject -> declarations
            else -> emptyList()
        }

        val containingResolvePhase =
            containingDeclarations().minByOrNull { it.firResolvePhaseWithSubDeclarations() }?.resolvePhase ?: resolvePhase

        return minOf(containingResolvePhase, resolvePhase)
    }

    /**
     * Run partially designated resolve that resolve declaration into last file-wise resolve and then resolve a designation (synchronized)
     * @see LAST_NON_LAZY_PHASE is the last file-wise resolve
     * @see lazyDesignatedResolveDeclaration designated resolve
     * @see runLazyResolveWithoutLock (not synchronized)
     */
    fun lazyResolveDeclaration(
        declaration: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        toPhase: FirResolvePhase,
        scopeSession: ScopeSession = ScopeSession(),
        checkPCE: Boolean = false,
        reresolveFile: Boolean = false,
    ) {
        val resolvePhase = declaration.firResolvePhaseWithSubDeclarations()
        if (resolvePhase >= toPhase) return

        val resolvableDeclaration = getResolvableDeclaration(declaration, moduleFileCache)

        val firFile = resolvableDeclaration.getContainingFile()
            ?: error("FirFile was not found for\n${resolvableDeclaration.render()}")
        val provider = firFile.moduleData.session.firIdeProvider

        // Lazy since we want to read the resolve phase inside the lock. Otherwise, we may run the same resolve phase multiple times. See
        // KT-45121
        val fromPhase: FirResolvePhase by lazy(LazyThreadSafetyMode.NONE) {
            val resolvable = resolvableDeclaration.firResolvePhaseWithSubDeclarations()
            if (reresolveFile) resolvableDeclaration.resolvePhase else minOf(resolvable, resolvable)
        }

        if (checkPCE) {
            firFileBuilder.runCustomResolveWithPCECheck(firFile, moduleFileCache) {
                runLazyResolveWithoutLock(
                    firDeclarationToResolve = declaration,
                    moduleFileCache = moduleFileCache,
                    containerFirFile = firFile,
                    provider = provider,
                    scopeSession = scopeSession,
                    fromPhase = fromPhase,
                    toPhase = toPhase,
                    checkPCE = true,
                )
            }
        } else {
            firFileBuilder.runCustomResolveUnderLock(firFile, moduleFileCache) {
                executeWithoutPCE {
                    runLazyResolveWithoutLock(
                        firDeclarationToResolve = declaration,
                        moduleFileCache = moduleFileCache,
                        containerFirFile = firFile,
                        provider = provider,
                        scopeSession = scopeSession,
                        fromPhase = fromPhase,
                        toPhase = toPhase,
                        checkPCE = false,
                    )
                }
            }
        }
    }

    /**
     * Designated resolve (not synchronized)
     * @see runLazyDesignatedResolveWithoutLock for designated resolve
     * @see lazyResolveDeclaration synchronized version
     */
    private fun runLazyResolveWithoutLock(
        firDeclarationToResolve: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        containerFirFile: FirFile,
        provider: FirProvider,
        scopeSession: ScopeSession,
        fromPhase: FirResolvePhase,
        toPhase: FirResolvePhase,
        checkPCE: Boolean,
    ) {
        if (fromPhase >= toPhase) return
        val nonLazyPhase = minOf(toPhase, LAST_NON_LAZY_PHASE)

        if (fromPhase < nonLazyPhase) {
            firFileBuilder.runResolveWithoutLock(
                firFile = containerFirFile,
                fromPhase = fromPhase,
                toPhase = nonLazyPhase,
                scopeSession = scopeSession,
                checkPCE = checkPCE
            )
        }
        if (toPhase <= nonLazyPhase) return

        runLazyDesignatedResolveWithoutLock(
            firDeclarationToResolve = firDeclarationToResolve,
            moduleFileCache = moduleFileCache,
            containerFirFile = containerFirFile,
            provider = provider,
            scopeSession = scopeSession,
            fromPhase = LAST_NON_LAZY_PHASE,
            toPhase = toPhase,
            checkPCE = checkPCE,
            isOnAirResolve = false
        )
    }

    /**
     * Run designated resolve only designation with fully resolved path (synchronized).
     * Suitable for body resolve or/and on-air resolve.
     * @see lazyResolveDeclaration for ordinary resolve
     * @param firDeclarationToResolve target non-local declaration
     * @param isOnAirResolve should be true when node does not belong to it's true designation (OnAir resolve in custom context)
     */
    fun lazyDesignatedResolveDeclaration(
        firDeclarationToResolve: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        containerFirFile: FirFile,
        provider: FirProvider,
        scopeSession: ScopeSession = ScopeSession(),
        toPhase: FirResolvePhase,
        checkPCE: Boolean,
        isOnAirResolve: Boolean,
        towerDataContextCollector: FirTowerDataContextCollector? = null,
    ) {
        // Lazy since we want to read the resolve phase inside the lock. Otherwise, we may run the same resolve phase multiple times. See
        // KT-45121
        val fromPhase: FirResolvePhase by lazy(LazyThreadSafetyMode.NONE) {
            minOf(containerFirFile.resolvePhase, firDeclarationToResolve.firResolvePhaseWithSubDeclarations())
        }

        if (checkPCE) {
            firFileBuilder.runCustomResolveWithPCECheck(containerFirFile, moduleFileCache) {
                runLazyDesignatedResolveWithoutLock(
                    firDeclarationToResolve = firDeclarationToResolve,
                    moduleFileCache = moduleFileCache,
                    containerFirFile = containerFirFile,
                    provider = provider,
                    scopeSession = scopeSession,
                    fromPhase = fromPhase,
                    toPhase = toPhase,
                    checkPCE = checkPCE,
                    isOnAirResolve = isOnAirResolve,
                    towerDataContextCollector = towerDataContextCollector,
                )
            }
        } else {
            firFileBuilder.runCustomResolveUnderLock(containerFirFile, moduleFileCache) {
                runLazyDesignatedResolveWithoutLock(
                    firDeclarationToResolve = firDeclarationToResolve,
                    moduleFileCache = moduleFileCache,
                    containerFirFile = containerFirFile,
                    provider = provider,
                    scopeSession = scopeSession,
                    fromPhase = fromPhase,
                    toPhase = toPhase,
                    checkPCE = checkPCE,
                    isOnAirResolve = isOnAirResolve,
                    towerDataContextCollector = towerDataContextCollector,
                )
            }
        }
    }

    /**
     * Designated resolve (not synchronized)
     * @see runLazyResolveWithoutLock for ordinary resolve
     * @see lazyDesignatedResolveDeclaration synchronized version
     */
    private fun runLazyDesignatedResolveWithoutLock(
        firDeclarationToResolve: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        containerFirFile: FirFile,
        provider: FirProvider,
        scopeSession: ScopeSession,
        fromPhase: FirResolvePhase,
        toPhase: FirResolvePhase,
        checkPCE: Boolean,
        isOnAirResolve: Boolean,
        towerDataContextCollector: FirTowerDataContextCollector? = null
    ) {
        var currentPhase = fromPhase
        runLazyResolveWithoutLock(
            firDeclarationToResolve = firDeclarationToResolve,
            moduleFileCache = moduleFileCache,
            containerFirFile = containerFirFile,
            provider = provider,
            scopeSession = scopeSession,
            fromPhase = currentPhase,
            toPhase = FirResolvePhase.IMPORTS,
            checkPCE = checkPCE
        )
        currentPhase = maxOf(fromPhase, FirResolvePhase.IMPORTS)
        if (currentPhase >= toPhase) return

        if (firDeclarationToResolve is FirFile) {
            resolveFileAnnotationsWithoutLock(containerFirFile, containerFirFile.annotations, ScopeSession())
            return
        }

        val nonLocalDeclarationToResolve = firDeclarationToResolve.getNonLocalDeclarationToResolve(provider, moduleFileCache)
        val designation = nonLocalDeclarationToResolve.collectDesignation(containerFirFile)
        check(!designation.isLocalDesignation) { "Could not resolve local designation" }

        //This needed to override standard symbol resolve in supertype transformer with adding on-air created symbols
        val firProviderInterceptor = isOnAirResolve.ifTrue {
            FirProviderInterceptorForIDE.createForFirElement(
                session = designation.firFile.moduleData.session,
                firFile = designation.firFile,
                element = designation.declaration
            )
        }

        while (currentPhase < toPhase) {
            currentPhase = currentPhase.next
            if (currentPhase.pluginPhase) continue
            if (checkPCE) checkCanceled()
            FirLazyBodiesCalculator.calculateLazyBodiesInsideIfNeeded(designation, currentPhase)

            runLazyResolvePhase(
                phase = currentPhase,
                scopeSession = scopeSession,
                designation = designation,
                towerDataContextCollector = towerDataContextCollector,
                firProviderInterceptor = firProviderInterceptor
            )
        }
    }

    private fun runLazyResolvePhase(
        phase: FirResolvePhase,
        scopeSession: ScopeSession,
        designation: FirDeclarationUntypedDesignationWithFile,
        towerDataContextCollector: FirTowerDataContextCollector?,
        firProviderInterceptor: FirProviderInterceptor?
    ) {
        val transformer = phase.createLazyTransformer(
            designation,
            scopeSession,
            towerDataContextCollector,
            firProviderInterceptor,
        )

        firFileBuilder.firPhaseRunner.runPhaseWithCustomResolve(phase) {
            transformer.transformDeclaration()
        }
    }

    private fun FirResolvePhase.createLazyTransformer(
        designation: FirDeclarationUntypedDesignationWithFile,
        scopeSession: ScopeSession,
        towerDataContextCollector: FirTowerDataContextCollector?,
        firProviderInterceptor: FirProviderInterceptor?,
    ): FirLazyTransformerForIDE = when (this) {
        FirResolvePhase.SUPER_TYPES -> FirDesignatedSupertypeResolverTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            firProviderInterceptor,
        )
        FirResolvePhase.SEALED_CLASS_INHERITORS -> FirLazyTransformerForIDE.DUMMY
        FirResolvePhase.TYPES -> FirDesignatedTypeResolverTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
        )
        FirResolvePhase.STATUS -> FirDesignatedStatusResolveTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession
        )
        FirResolvePhase.CONTRACTS -> FirDesignatedContractsResolveTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
        )
        FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE -> FirDesignatedImplicitTypesTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            towerDataContextCollector
        )
        FirResolvePhase.BODY_RESOLVE -> FirDesignatedBodyResolveTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            towerDataContextCollector,
            firProviderInterceptor
        )
        else -> error("Non-lazy phase $this")
    }

    private fun FirDeclaration.getNonLocalDeclarationToResolve(provider: FirProvider, moduleFileCache: ModuleFileCache): FirDeclaration {
        if (this is FirFile) return this
        val ktDeclaration = psi as? KtDeclaration ?: error("FirDeclaration should have a PSI of type KtDeclaration")
        if (declarationCanBeLazilyResolved(ktDeclaration)) return this
        val nonLocalPsi = ktDeclaration.getNonLocalContainingOrThisDeclaration()
            ?: error("Container for local declaration cannot be null")
        return nonLocalPsi.findSourceNonLocalFirDeclaration(firFileBuilder, provider.symbolProvider, moduleFileCache)
    }

    companion object {
        private val LAST_NON_LAZY_PHASE = FirResolvePhase.IMPORTS

        fun declarationCanBeLazilyResolved(declaration: KtDeclaration): Boolean {
            return when (declaration) {
                !is KtNamedDeclaration -> false
                is KtDestructuringDeclarationEntry, is KtFunctionLiteral, is KtTypeParameter -> false
                is KtPrimaryConstructor -> false
                is KtParameter -> {
                    if (declaration.hasValOrVar()) declaration.containingClassOrObject?.getClassId() != null
                    else false
                }
                is KtCallableDeclaration, is KtEnumEntry -> {
                    when (val parent = declaration.parent) {
                        is KtFile -> true
                        is KtClassBody -> (parent.parent as? KtClassOrObject)?.getClassId() != null
                        else -> false
                    }
                }
                is KtClassLikeDeclaration -> declaration.getClassId() != null
                else -> error("Unexpected ${declaration::class.qualifiedName}")
            }
        }
    }
}
