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

package org.jetbrains.kotlin.backend.jvm.codegen

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.lower.FileClassDescriptor
import org.jetbrains.kotlin.codegen.ClassBuilder
import org.jetbrains.kotlin.codegen.ImplementationBodyCodegen
import org.jetbrains.kotlin.codegen.MemberCodegen.badDescriptor
import org.jetbrains.kotlin.codegen.OwnerKind
import org.jetbrains.kotlin.codegen.SuperClassInfo
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOrigin
import org.jetbrains.kotlin.resolve.jvm.diagnostics.OtherOrigin
import org.jetbrains.kotlin.resolve.source.PsiSourceElement
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import java.lang.RuntimeException

class ClassCodegen private constructor(val irClass: IrClass, val context: JvmBackendContext) {

    val state = context.state

    val typeMapper = context.state.typeMapper

    val descriptor = irClass.descriptor

    val type: Type = typeMapper.mapType(descriptor)

    val psiElement = irClass.descriptor.psiElement!!

    val visitor: ClassBuilder = state.factory.newVisitor(OtherOrigin(psiElement, descriptor), type, psiElement.containingFile)

    fun generate() {
        val superClassInfo = SuperClassInfo.getSuperClassInfo(descriptor, typeMapper)
        val signature = ImplementationBodyCodegen.signature(descriptor, type, superClassInfo, typeMapper)

        visitor.defineClass(
                psiElement,
                state.classFileVersion,
                descriptor.calculateClassFlags(),
                signature.name,
                signature.javaGenericSignature,
                signature.superclassName,
                signature.interfaces.toTypedArray()
        )

        irClass.declarations.forEach {
            generateDeclaration(it)
        }

        visitor.done()
    }

    companion object {
        fun generate(irClass: IrClass, context: JvmBackendContext) {
            val descriptor = irClass.descriptor
            val state = context.state

            if (ErrorUtils.isError(descriptor)) {
                badDescriptor(descriptor, state.classBuilderMode)
                return
            }

            if (descriptor.name == SpecialNames.NO_NAME_PROVIDED) {
                badDescriptor(descriptor, state.classBuilderMode)
            }

            ClassCodegen(irClass, context).generate()
        }
    }

    fun generateDeclaration(declaration: IrDeclaration) {
        when (declaration) {
            is IrField ->
                generateField(declaration)
            is IrFunction -> {
                generateMethod(declaration)
            }
            is IrAnonymousInitializer -> {
                // skip
            }
            else -> throw RuntimeException("Unsupported declaration $declaration")
        }
    }


    fun generateField(field: IrField) {
        val fieldType = typeMapper.mapType(field.descriptor)
        val fieldSignature = typeMapper.mapFieldSignature(field.descriptor.type, field.descriptor)
        visitor.newField(field.OtherOrigin, field.descriptor.calculateCommonFlags(), field.descriptor.name.asString(), fieldType.descriptor,
                         fieldSignature, null/*TODO support default values*/)
    }

    fun generateMethod(method: IrFunction) {
        FunctionCodegen(method, this).generate()
    }

}

fun ClassDescriptor.calculateClassFlags(): Int {
    return (if (!DescriptorUtils.isInterface(this)) Opcodes.ACC_SUPER else 0).
            or(calculateCommonFlags()).
            or(if (DescriptorUtils.isInterface(this)) Opcodes.ACC_INTERFACE else 0)
}

fun MemberDescriptor.calculateCommonFlags(): Int {
    var flags = 0
    if (Visibilities.isPrivate(visibility)) {
        flags = flags.or(Opcodes.ACC_PRIVATE)
    }
    else if (visibility == Visibilities.PUBLIC || visibility == Visibilities.INTERNAL) {
        flags = flags.or(Opcodes.ACC_PUBLIC)
    }
    else if (visibility == Visibilities.PROTECTED) {
        flags = flags.or(Opcodes.ACC_PROTECTED)
    }
    else {
        throw RuntimeException("Unsupported visibility $visibility for descriptor $this")
    }

    when (modality) {
        Modality.ABSTRACT -> {
            flags = flags.or(Opcodes.ACC_ABSTRACT)
        }
        Modality.FINAL -> {
            if (this !is ConstructorDescriptor) {
                flags = flags.or(Opcodes.ACC_FINAL)
            }
        }
        Modality.OPEN -> {
            assert(!Visibilities.isPrivate(visibility))
        }
        else -> throw RuntimeException("Unsupported modality $modality for descriptor $this")
    }

    if (this is CallableMemberDescriptor) {
        if (this !is ConstructorDescriptor && dispatchReceiverParameter == null) {
            flags = flags or Opcodes.ACC_STATIC
        }
    }

    return flags
}

val DeclarationDescriptorWithSource.psiElement: PsiElement?
    get() = (source as? PsiSourceElement)?.psi

val IrField.OtherOrigin: JvmDeclarationOrigin
    get() = OtherOrigin(descriptor.psiElement, this.descriptor)

val IrFunction.OtherOrigin: JvmDeclarationOrigin
    get() = OtherOrigin(descriptor.psiElement, this.descriptor)

fun DeclarationDescriptor.getMemberOwnerKind(): OwnerKind = when (this) {
    is FileClassDescriptor ->
        OwnerKind.PACKAGE
    is PackageFragmentDescriptor,
    is ClassDescriptor ->
        OwnerKind.IMPLEMENTATION
    else ->
        throw AssertionError("Unexpected declaration container: $this")
}