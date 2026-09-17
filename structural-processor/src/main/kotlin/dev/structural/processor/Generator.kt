package dev.structural.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/** Collects generated overloads and proxies into files, then writes them all at once. */
class Generator(private val codeGenerator: CodeGenerator, private val dependencies: Dependencies) {
    private val files = linkedMapOf<Pair<String, String>, FileSpec.Builder>()
    private val proxies = mutableMapOf<Pair<String, String>, ClassName>()
    private val proxyNames = mutableSetOf<ClassName>()

    fun overload(function: StructuralFunction, candidate: KSClassDeclaration, nominal: Boolean) {
        val declaration = function.declaration
        val owner = function.owner
        val name = declaration.simpleName.asString()
        val parameterNames = declaration.parameters.map { it.name!!.asString() }
        val structuralName = parameterNames[function.slot]
        val argumentName = uniqueName("structuralArgument", parameterNames)
        val receiver = owner?.toClassName() ?: declaration.extensionReceiver?.toTypeName()
        val internal = declaration.effectiveVisibility() == Visibility.INTERNAL ||
            candidate.effectiveVisibility() == Visibility.INTERNAL

        val builder = FunSpec.builder(name)
            .addModifiers(if (internal) KModifier.INTERNAL else KModifier.PUBLIC)
            .returns(declaration.returnType!!.toTypeName())
        if (Modifier.SUSPEND in declaration.modifiers) builder.addModifiers(KModifier.SUSPEND)
        receiver?.let { builder.receiver(it) }
        declaration.parameters.forEachIndexed { index, parameter ->
            val type = if (index == function.slot) candidate.toClassName() else parameter.type.toTypeName()
            builder.addParameter(parameterNames[index], type)
        }

        val ifaceType = function.iface.declaration.toClassName()
        if (nominal) {
            builder.addStatement("val %N: %T = %N", argumentName, ifaceType, structuralName)
        } else {
            builder.addStatement("val %N: %T = %T(%N)", argumentName, ifaceType, proxyFor(candidate, function.iface), structuralName)
        }
        val arguments = parameterNames
            .map { CodeBlock.of("%N = %N", it, if (it == structuralName) argumentName else it) }
            .joinToCode()
        builder.addStatement(if (receiver != null) "return this.%N(%L)" else "return %N(%L)", name, arguments)

        val packageName = (owner ?: declaration).packageName.asString()
        val fileName = owner?.toClassName()?.simpleNames?.joinToString("_")
            ?: declaration.containingFile!!.fileName.removeSuffix(".kt")
        file(packageName, "${fileName}_Structural").addFunction(builder.build())
    }

    fun write() {
        files.values.forEach { it.build().writeTo(codeGenerator, dependencies) }
    }

    private fun proxyFor(candidate: KSClassDeclaration, iface: StructuralInterface): ClassName =
        proxies.getOrPut(candidate.fqName to iface.name) {
            val packageName = candidate.packageName.asString()
            val baseName = candidate.toClassName().simpleNames.joinToString("_") + "_As" + iface.declaration.simpleName.asString()
            var className = ClassName(packageName, baseName)
            if (className in proxyNames) className = ClassName(packageName, "${baseName}_${Integer.toHexString(iface.name.hashCode())}")
            proxyNames += className
            file(packageName, className.simpleName).addType(proxyType(className, candidate, iface))
            className
        }

    private fun proxyType(className: ClassName, candidate: KSClassDeclaration, iface: StructuralInterface): TypeSpec {
        val targetType = candidate.toClassName()
        val target = uniqueName("target", iface.properties.map { it.name })
        return TypeSpec.classBuilder(className)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(iface.declaration.toClassName())
            .primaryConstructor(FunSpec.constructorBuilder().addParameter(target, targetType).build())
            .addProperty(PropertySpec.builder(target, targetType).initializer("%N", target).build())
            .addProperties(iface.properties.map { delegatingProperty(it, target) })
            .addFunction(
                FunSpec.builder("equals")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("other", ANY.copy(nullable = true))
                    .returns(BOOLEAN)
                    .addStatement("return other is %T && other.%N == %N", className, target, target)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("hashCode").addModifiers(KModifier.OVERRIDE).returns(INT)
                    .addStatement("return %N.hashCode()", target).build(),
            )
            .addFunction(
                FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING)
                    .addStatement("return %N.toString()", target).build(),
            )
            .build()
    }

    private fun delegatingProperty(property: RequiredProperty, target: String): PropertySpec {
        val type = property.type.toTypeName()
        val builder = PropertySpec.builder(property.name, type, KModifier.OVERRIDE)
            .mutable(property.mutable)
            .getter(FunSpec.getterBuilder().addStatement("return %N.%N", target, property.name).build())
        if (property.mutable) {
            builder.setter(
                FunSpec.setterBuilder().addParameter("value", type).addStatement("%N.%N = value", target, property.name).build(),
            )
        }
        return builder.build()
    }

    private fun file(packageName: String, name: String): FileSpec.Builder =
        files.getOrPut(packageName to name) { FileSpec.builder(packageName, name) }
}

private fun uniqueName(base: String, taken: Collection<String>): String =
    generateSequence(base) { "${it}_" }.first { it !in taken }
