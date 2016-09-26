/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve

import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.PropertyAccessorDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.name.FqName
import java.util.*

private val SINCE_KOTLIN_FQ_NAME = FqName("kotlin.SinceKotlin")

internal fun DeclarationDescriptor.getSinceKotlinAnnotation(): AnnotationDescriptor? =
        annotations.findAnnotation(SINCE_KOTLIN_FQ_NAME)

/**
 * @return the value of the [SinceKotlin] annotation argument if [descriptor] should not be accessible, or null if it should be
 */
internal fun LanguageVersionSettings.getSinceVersionIfInaccessible(descriptor: DeclarationDescriptor): ApiVersion? {
    // If there's no @Since annotation or its value is not recognized, allow the access
    val version = descriptor.getSinceKotlinVersion() ?: return null

    // Otherwise allow the access iff the version in @Since is not greater than our API version
    return if (apiVersion < version) version else null
}

// TODO: combine with deprecationByOverridden?
private fun getSinceKotlinVersionByOverridden(root: CallableMemberDescriptor): ApiVersion? {
    val visited = HashSet<CallableMemberDescriptor>()
    val versions = LinkedHashSet<ApiVersion>()
    var hasNoSinceKotlinVersion = false

    fun traverse(node: CallableMemberDescriptor) {
        if (!visited.add(node)) return

        if (!node.kind.isReal) {
            node.original.overriddenDescriptors.forEach(::traverse)
        }
        else {
            val ourVersion = node.getOwnSinceKotlinVersion()
            if (ourVersion != null) {
                versions.add(ourVersion)
            }
            else {
                hasNoSinceKotlinVersion = true
            }
        }
    }

    traverse(root)

    if (hasNoSinceKotlinVersion || versions.isEmpty()) return null

    return versions.min()
}

// TODO: doc
internal fun DeclarationDescriptor.getSinceKotlinVersion(): ApiVersion? {
    if (this is CallableMemberDescriptor && !kind.isReal) {
        return getSinceKotlinVersionByOverridden(this)
    }

    // TODO: property accessors
    // TODO: use-site targets
    val ownVersion = getOwnSinceKotlinVersion()
    if (ownVersion != null) return ownVersion

    return null
}

private fun DeclarationDescriptor.getOwnSinceKotlinVersion(): ApiVersion? {
    fun DeclarationDescriptor.loadAnnotationValue(): ApiVersion? =
            (getSinceKotlinAnnotation()?.allValueArguments?.values?.singleOrNull()?.value as? String)?.let(ApiVersion.Companion::parse)

    val ownVersion = loadAnnotationValue()
    val ctorClass = (this as? ConstructorDescriptor)?.containingDeclaration?.loadAnnotationValue()
    val property = (this as? PropertyAccessorDescriptor)?.correspondingProperty?.loadAnnotationValue()

    return listOfNotNull(ownVersion, ctorClass, property).min()
}
