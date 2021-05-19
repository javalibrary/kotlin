/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase

internal interface FirLazyTransformerForIDE {
    fun transformDeclaration()

    companion object {
        val DUMMY = object : FirLazyTransformerForIDE {
            override fun transformDeclaration() = Unit
        }
    }

    fun <T : FirDeclaration> T.containingDeclarations() = when (this) {
        is FirClass<*> -> declarations
        is FirAnonymousObject -> declarations
        else -> emptyList()
    }
}