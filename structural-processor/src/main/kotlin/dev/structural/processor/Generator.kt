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
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/** Collects generated adapters, overloads and proxies into files, then writes them all at once. */
class Generator(private val codeGenerator: CodeGenerator, private val dependencies: Dependencies) {
    private val files = linkedMapOf<Pair<String, String>, FileSpec.Builder>()
    private val proxies = mutableMapOf<Pair<String, String>, ClassName>()
    private val proxyNames = mutableSetOf<ClassName>()

    /** `fun I.asI(): I = this`, so any value already implementing the interface can be converted uniformly. */
    fun identityAdapter(iface: StructuralInterface) {
        val internal = iface.declaration.effectiveVisibility() == Visibility.INTERNAL
        adapterFile(iface).addFunction(
            adapterBuilder(iface, iface.declaration.toClassName(), internal).addStatement("return this").build(),
        )
    }

    /** `fun C.asI(): I`, returning `this` when [candidate] implements the interface nominally, otherwise a proxy. */
    fun adapter(iface: StructuralInterface, candidate: KSClassDeclaration, nominal: Boolean) {
        val internal = iface.declaration.effectiveVisibility() == Visibility.INTERNAL ||
            candidate.effectiveVisibility() == Visibility.INTERNAL
        val builder = adapterBuilder(iface, candidate.toClassName(), internal)
        if (nominal) builder.addStatement("return this") else builder.addStatement("return %T(this)", proxyFor(candidate, iface))
        adapterFile(iface).addFunction(builder.build())
    }

    fun overload(function: StructuralFunction, candidate: KSClassDeclaration) {
        val declaration = function.declaration
        val owner = function.owner
        val name = declaration.simpleName.asString()
        val adapter = adapterName(function.iface)
        val onReceiver = function.slot == StructuralFunction.RECEIVER
        val receiver = when {
            onReceiver -> candidate.toClassName()
            owner != null -> owner.toClassName()
            else -> declaration.extensionReceiver?.toTypeName()
        }
        val internal = declaration.effectiveVisibility() == Visibility.INTERNAL ||
            candidate.effectiveVisibility() == Visibility.INTERNAL

        val builder = FunSpec.builder(name)
            .addModifiers(if (internal) KModifier.INTERNAL else KModifier.PUBLIC)
            .returns(declaration.returnType!!.toTypeName())
        if (Modifier.SUSPEND in declaration.modifiers) builder.addModifiers(KModifier.SUSPEND)
        receiver?.let { builder.receiver(it) }
        declaration.parameters.forEachIndexed { index, parameter ->
            val type = if (index == function.slot) candidate.toClassName() else parameter.type.toTypeName()
            val spec = ParameterSpec.builder(parameter.name!!.asString(), type)
            if (parameter.isVararg) spec.addModifiers(KModifier.VARARG)
            builder.addParameter(spec.build())
        }

        // Converting with the adapter gives the interface type, so the call can't resolve to this overload again.
        val arguments = declaration.parameters.mapIndexed { index, parameter ->
            val parameterName = parameter.name!!.asString()
            // Named vararg arguments take an array directly; a spread there is a "redundant spread" warning.
            val value = when {
                index != function.slot -> CodeBlock.of("%N", parameterName)
                parameter.isVararg -> CodeBlock.of("%N.map { it.%M() }.toTypedArray()", parameterName, adapter)
                else -> CodeBlock.of("%N.%M()", parameterName, adapter)
            }
            CodeBlock.of("%N = %L", parameterName, value)
        }.joinToCode()
        val call = when {
            onReceiver -> CodeBlock.of("this.%M().%N(%L)", adapter, name, arguments)
            receiver != null -> CodeBlock.of("this.%N(%L)", name, arguments)
            else -> CodeBlock.of("%N(%L)", name, arguments)
        }
        builder.addStatement("return %L", call)

        val packageName = (owner ?: declaration).packageName.asString()
        val fileName = owner?.toClassName()?.simpleNames?.joinToString("_")
            ?: declaration.containingFile!!.fileName.removeSuffix(".kt")
        file(packageName, "${fileName}_Structural").addFunction(builder.build())
    }

    private fun adapterName(iface: StructuralInterface): MemberName = MemberName(
        iface.declaration.packageName.asString(),
        "as" + iface.declaration.simpleName.asString(),
        isExtension = true,
    )

    private fun adapterBuilder(iface: StructuralInterface, receiver: TypeName, internal: Boolean): FunSpec.Builder =
        FunSpec.builder(adapterName(iface).simpleName)
            .addModifiers(if (internal) KModifier.INTERNAL else KModifier.PUBLIC)
            .receiver(receiver)
            .returns(iface.declaration.toClassName())

    private fun adapterFile(iface: StructuralInterface): FileSpec.Builder = file(
        iface.declaration.packageName.asString(),
        iface.declaration.toClassName().simpleNames.joinToString("_") + "_Adapters",
    )

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
