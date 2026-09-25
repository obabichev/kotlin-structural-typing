package com.obabichev.structural.runtime

import kotlin.metadata.KmClass
import kotlin.metadata.jvm.KotlinClassMetadata
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Supplies the bytes of a class without loading it. Matching runs inside `findClass`, so resolving a type by loading
 * it would recurse and define classes before their turn; everything is read from bytes instead.
 */
fun interface ShapeResolver {
    fun bytes(internalName: String): ByteArray?
}

/** A method as the class file declares it. */
internal class MethodShape(val name: String, val descriptor: String, val access: Int, val signature: String?) {
    val isPublic: Boolean get() = access and Opcodes.ACC_PUBLIC != 0
    val isStatic: Boolean get() = access and Opcodes.ACC_STATIC != 0
    val isAbstract: Boolean get() = access and Opcodes.ACC_ABSTRACT != 0
    val isSynthetic: Boolean get() = access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0
    val returnDescriptor: String get() = descriptor.substringAfter(')')
    val parameterDescriptor: String get() = descriptor.substringAfter('(').substringBefore(')')
}

/** A class read from its bytes: what it is, what it extends, what it declares, and its Kotlin metadata if it has any. */
internal class ClassShape(
    val internalName: String,
    val access: Int,
    val superName: String?,
    val interfaces: List<String>,
    val signature: String?,
    val methods: List<MethodShape>,
    val kotlin: KmClass?,
    val sealed: Boolean,
) {
    val isInterface: Boolean get() = access and Opcodes.ACC_INTERFACE != 0
    val isAnnotation: Boolean get() = access and Opcodes.ACC_ANNOTATION != 0
    val isEnum: Boolean get() = access and Opcodes.ACC_ENUM != 0
    val isSynthetic: Boolean get() = access and Opcodes.ACC_SYNTHETIC != 0

    /** Type parameters show up as a generic signature on the class. The plugin ignores generic interfaces too. */
    val isGeneric: Boolean get() = signature != null && signature.startsWith("<")

    fun method(name: String, descriptor: String): MethodShape? =
        methods.firstOrNull { it.name == name && it.descriptor == descriptor }
}

/** Parses and caches [ClassShape]s. One instance per classloader or agent; safe for concurrent use. */
internal class ClassShapes(private val resolver: ShapeResolver) {
    private val cache = ConcurrentHashMap<String, Any>()

    fun of(internalName: String): ClassShape? {
        cache[internalName]?.let { return it as? ClassShape }
        val shape = resolver.bytes(internalName)?.let { parse(it) }
        cache.putIfAbsent(internalName, shape ?: ABSENT)
        return shape
    }

    /** [internalName] and its superclasses, nearest first, as far as they can be read. */
    fun superclassChain(internalName: String): List<ClassShape> {
        val chain = mutableListOf<ClassShape>()
        val seen = mutableSetOf<String>()
        var current: String? = internalName
        while (current != null && seen.add(current)) {
            val shape = of(current) ?: break
            chain += shape
            current = shape.superName
        }
        return chain
    }

    /** Whether [subtype] is [supertype] or inherits from it, walking declared supertypes from their bytes. */
    fun inheritsFrom(subtype: String, supertype: String): Boolean {
        if (subtype == supertype) return true
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(subtype))
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            if (name == supertype) return true
            val shape = of(name) ?: continue
            shape.superName?.let { queue += it }
            queue += shape.interfaces
        }
        return false
    }

    companion object {
        private val ABSENT = Any()

        fun parse(bytes: ByteArray): ClassShape {
            val node = ClassNode()
            ClassReader(bytes).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            return ClassShape(
                internalName = node.name,
                access = node.access,
                superName = node.superName,
                interfaces = node.interfaces.orEmpty(),
                signature = node.signature,
                methods = node.methods.orEmpty().map { MethodShape(it.name, it.desc, it.access, it.signature) },
                kotlin = node.kotlinClass(),
                // A sealed interface rejects implementors it doesn't permit, with IncompatibleClassChangeError.
                sealed = !node.permittedSubclasses.isNullOrEmpty(),
            )
        }

        /** The `KmClass` of a Kotlin class, or null for Java classes and for anything this metadata version can't read. */
        private fun ClassNode.kotlinClass(): KmClass? {
            val annotation = visibleAnnotations.orEmpty().firstOrNull { it.desc == "Lkotlin/Metadata;" } ?: return null
            val values = annotation.values.orEmpty().chunked(2).associate { it[0] as String to it[1] }

            @Suppress("UNCHECKED_CAST")
            val metadata = kotlin.metadata.jvm.Metadata(
                kind = values["k"] as? Int ?: 1,
                metadataVersion = (values["mv"] as? List<Int>)?.toIntArray(),
                data1 = (values["d1"] as? List<String>)?.toTypedArray(),
                data2 = (values["d2"] as? List<String>)?.toTypedArray(),
                extraString = values["xs"] as? String,
                packageName = values["pn"] as? String,
                extraInt = values["xi"] as? Int ?: 0,
            )
            return (runCatching { KotlinClassMetadata.readStrict(metadata) }.getOrNull()
                as? KotlinClassMetadata.Class)?.kmClass
        }
    }
}
