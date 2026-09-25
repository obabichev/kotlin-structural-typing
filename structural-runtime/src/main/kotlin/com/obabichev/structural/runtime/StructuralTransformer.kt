package com.obabichev.structural.runtime

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/** What a requirement needs from the class: nothing, a bridge, or more than can be built. */
private sealed interface Bridge {
    object NotNeeded : Bridge
    object Impossible : Bridge
    class Method(val node: MethodNode) : Bridge
}

/**
 * Rewrites a class as it is loaded so that it implements the `@Structural` interfaces its shape already satisfies.
 *
 * This is the whole of the runtime: the classloader and the java agent differ only in where they get the bytes and the
 * [ShapeResolver] from. Everything the transformer needs about other classes is read from their bytes, never by
 * loading them — it runs inside `findClass`, where loading a class would recurse.
 */
class StructuralTransformer(
    private val index: StructuralIndex,
    resolver: ShapeResolver,
    private val listener: (String) -> Unit = {},
) {
    private val shapes = ClassShapes(resolver)
    private val matcher = StructuralMatcher(shapes)

    /** The rewritten bytes, or null when [bytes] are left alone. */
    fun transform(internalName: String, bytes: ByteArray): ByteArray? = runCatching {
        if (index.interfaces.isEmpty()) return null
        val shape = ClassShapes.parse(bytes)
        if (!isCandidate(shape, internalName)) return null

        val members = declaredMembers(shape) +
            shape.superName?.let { shapes.superclassChain(it).flatMap(::declaredMembers) }.orEmpty()

        val matched = index.interfaces
            .mapNotNull { matcher.structuralInterface(it) }
            .filterNot { shapes.inheritsFrom(internalName, it.internalName) }
            .filter { matcher.implementsByShape(members, it) }
        if (matched.isEmpty()) return null

        val bridges = mutableListOf<MethodNode>()
        val added = matched.filter { iface ->
            val forThisInterface = bridgesFor(iface, members, internalName)
            if (forThisInterface == null) {
                listener("$internalName does not gain ${iface.internalName}: a bridge could not be built")
                false
            } else {
                bridges += forThisInterface
                true
            }
        }
        if (added.isEmpty()) return null

        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        for (iface in added) {
            node.interfaces.add(iface.internalName)
            // A generic signature repeats the supertypes; leaving it alone would make getInterfaces() and
            // getGenericInterfaces() disagree.
            node.signature?.let { node.signature = it + "L${iface.internalName};" }
        }
        node.methods.addAll(bridges)

        // COMPUTE_MAXS, not COMPUTE_FRAMES: computing frames asks ClassWriter.getCommonSuperClass, which loads
        // classes, and this runs inside findClass. Every bridge is straight-line code, so it needs no frames at all.
        val writer = ClassWriter(ClassReader(bytes), ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        listener("$internalName now implements ${added.joinToString { it.internalName }}")
        writer.toByteArray()
    }.getOrElse {
        listener("$internalName left alone: $it")
        null
    }

    /** Classes the plugin would consider: classes, objects and enum classes, never interfaces or annotation classes. */
    private fun isCandidate(shape: ClassShape, internalName: String): Boolean =
        !shape.isInterface && !shape.isAnnotation && !shape.isSynthetic &&
            !internalName.endsWith("package-info") && !internalName.endsWith("module-info") &&
            !internalName.contains("\$\$Lambda")

    /**
     * The methods [owner] needs so the interface's slots are filled, or null when one of them can't be built — in
     * which case the caller leaves the interface off rather than producing a class that fails at its first call.
     *
     * A class's own method usually already has the interface's name and descriptor and nothing is emitted. A bridge is
     * needed for a covariant return — `getLength()I` against a required `Number` — and for a member whose JVM name
     * differs, as with an enum's built-in `name`, which is `java.lang.Enum.name()` and not `getName()`.
     */
    private fun bridgesFor(iface: StructuralInterfaceShape, members: List<MemberView>, owner: String): List<MethodNode>? {
        val bridges = mutableListOf<MethodNode>()
        for (requirement in iface.requirements) {
            val implementation = matcher.implementationOf(members, requirement) ?: return null
            val slots = buildList {
                add(requirement.jvmName to requirement.jvmDescriptor to (implementation.jvmName to implementation.jvmDescriptor))
                if (requirement.isVar) {
                    val from = (requirement.setterName ?: return null) to (requirement.setterDescriptor ?: return null)
                    val to = (implementation.setterName ?: return null) to (implementation.setterDescriptor ?: return null)
                    add(from to to)
                }
            }
            for ((from, to) in slots) {
                when (val bridge = bridge(from.first, from.second, to.first, to.second, owner)) {
                    is Bridge.Method -> bridges += bridge.node
                    Bridge.NotNeeded -> Unit
                    Bridge.Impossible -> return null
                }
            }
        }
        return bridges
    }

    /** A synthetic bridge named [fromName]/[fromDescriptor] that calls [toName]/[toDescriptor] on `this`. */
    private fun bridge(
        fromName: String,
        fromDescriptor: String,
        toName: String,
        toDescriptor: String,
        owner: String,
    ): Bridge {
        if (fromName == toName && fromDescriptor == toDescriptor) return Bridge.NotNeeded
        val from = Type.getMethodType(fromDescriptor)
        val to = Type.getMethodType(toDescriptor)
        // The matcher demands exact parameter types, so only the return can differ. A difference here means the
        // matcher and the transformer disagree, which is a bug: refuse rather than emit something unverifiable.
        if (!from.argumentTypes.contentEquals(to.argumentTypes)) return Bridge.Impossible

        val method = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE,
            fromName,
            fromDescriptor,
            null,
            null,
        )
        method.visitCode()
        method.visitVarInsn(Opcodes.ALOAD, 0)
        var slot = 1
        for (argument in to.argumentTypes) {
            method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot)
            slot += argument.size
        }
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, toName, toDescriptor, false)
        if (!adaptReturn(method, to.returnType, from.returnType)) return Bridge.Impossible
        method.visitMaxs(0, 0)
        method.visitEnd()
        return Bridge.Method(method)
    }

    /** Puts the value [actual] left on the stack into the form [expected] wants, and returns it. */
    private fun adaptReturn(method: MethodNode, actual: Type, expected: Type): Boolean {
        if (actual == expected) {
            method.visitInsn(expected.getOpcode(Opcodes.IRETURN))
            return true
        }
        if (expected.sort == Type.VOID) {
            if (actual.sort != Type.VOID) method.visitInsn(if (actual.size == 2) Opcodes.POP2 else Opcodes.POP)
            method.visitInsn(Opcodes.RETURN)
            return true
        }
        if (expected.sort != Type.OBJECT && expected.sort != Type.ARRAY) return false
        when {
            // A Kotlin function returning Unit is void in bytecode; an Any requirement wants the Unit instance.
            actual.sort == Type.VOID ->
                method.visitFieldInsn(Opcodes.GETSTATIC, "kotlin/Unit", "INSTANCE", "Lkotlin/Unit;")
            actual.sort != Type.OBJECT && actual.sort != Type.ARRAY -> {
                val boxed = erasedInternalName(actual.descriptor) ?: return false
                method.visitMethodInsn(Opcodes.INVOKESTATIC, boxed, "valueOf", "(${actual.descriptor})L$boxed;", false)
            }
            // A reference already satisfies a supertype: the verifier accepts it on areturn with no cast.
            else -> Unit
        }
        method.visitInsn(Opcodes.ARETURN)
        return true
    }
}
