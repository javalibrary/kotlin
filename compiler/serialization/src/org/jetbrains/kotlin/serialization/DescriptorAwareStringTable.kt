/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.serialization

import org.jetbrains.kotlin.descriptors.ClassifierDescriptorWithTypeParameters
import org.jetbrains.kotlin.metadata.serialization.StringTable
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.types.ErrorUtils

interface DescriptorAwareStringTable : StringTable {
    fun getQualifiedClassNameIndex(classId: ClassId): Int =
        getQualifiedClassNameIndex(classId.asString(), classId.isLocal)

    fun getFqNameIndex(descriptor: ClassifierDescriptorWithTypeParameters): Int {
        return doGetFqNameIndex(descriptor).first
    }

    fun getFqNameIndexReplacementAware(descriptor: ClassifierDescriptorWithTypeParameters): FqNameIndexWithLocalReplacementInfo {
        val (index, isClassIdReplaced) = doGetFqNameIndex(descriptor)
        return FqNameIndexWithLocalReplacementInfo(index, isClassIdReplaced, isLocalClassIdReplacementKeptGeneric)
    }

    fun getLocalClassIdReplacement(descriptor: ClassifierDescriptorWithTypeParameters): ClassId? = null

    val isLocalClassIdReplacementKeptGeneric: Boolean

    private fun doGetFqNameIndex(descriptor: ClassifierDescriptorWithTypeParameters): Pair<Int, Boolean> {
        if (ErrorUtils.isError(descriptor)) {
            throw IllegalStateException("Cannot get FQ name of error class: ${renderDescriptor(descriptor)}")
        }

        var replaced = false
        val classId = descriptor.classId
            ?: getLocalClassIdReplacement(descriptor)?.also { replaced = true }
            ?: throw IllegalStateException("Cannot get FQ name of local class: ${renderDescriptor(descriptor)}")

        return getQualifiedClassNameIndex(classId) to replaced
    }

    private fun renderDescriptor(descriptor: ClassifierDescriptorWithTypeParameters): String =
        DescriptorRenderer.COMPACT.render(descriptor) + " defined in " +
                DescriptorRenderer.FQ_NAMES_IN_TYPES.render(descriptor.containingDeclaration)
}

data class FqNameIndexWithLocalReplacementInfo(
    val fqNameIndex: Int,
    val isClassIdReplaced: Boolean,
    val isReplacementKeptGeneric: Boolean,
)
