@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.obabichev.structural.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.name.ClassId
import java.io.File

/**
 * Writes the module's `@Structural` interfaces to [StructuralIndexFile] once the module is compiled, so modules
 * depending on it can find them. Runs on IR rather than FIR because that is where a module is finished: the whole
 * module's declarations are available in one place, exactly once.
 */
class StructuralIndexWriter(private val outputDirectory: File) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val classIds = mutableListOf<ClassId>()
        moduleFragment.files.forEach { collect(it, classIds) }
        StructuralIndexFile.writeTo(outputDirectory, classIds)
    }

    private fun collect(container: IrDeclarationContainer, into: MutableList<ClassId>) {
        for (declaration in container.declarations) {
            if (declaration !is IrClass) continue
            if (declaration.isInterface && declaration.hasAnnotation(STRUCTURAL_ANNOTATION)) {
                declaration.classId?.let(into::add)
            }
            collect(declaration, into)
        }
    }
}
