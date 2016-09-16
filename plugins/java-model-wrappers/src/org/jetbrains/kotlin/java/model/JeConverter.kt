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

package org.jetbrains.kotlin.java.model

import com.intellij.psi.*
import org.jetbrains.kotlin.java.model.elements.*
import org.jetbrains.kotlin.java.model.internal.JeElementRegistry

fun PsiElement?.toJeElement(registry: JeElementRegistry): JeElement? = when (this) {
    null -> null
    is PsiPackage -> JePackageElement(this, registry)
    is PsiClass -> JeTypeElement(this, registry)
    is PsiVariable -> JeVariableElement(this, registry)
    is PsiMethod -> JeMethodExecutableElement(this, registry)
    is PsiClassInitializer -> JeClassInitializerExecutableElement(this, registry)
    else -> null
}