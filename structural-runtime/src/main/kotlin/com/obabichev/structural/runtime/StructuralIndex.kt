package com.obabichev.structural.runtime

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URL
import java.util.jar.JarFile

/** The `@Structural` interfaces the runtime matches classes against, as JVM internal names. */
class StructuralIndex private constructor(internal val interfaces: Set<String>) {

    val names: Set<String> get() = interfaces.map { it.replace('/', '.') }.toSet()

    companion object {
        private const val STRUCTURAL_DESCRIPTOR = "Lcom/obabichev/structural/Structural;"

        /** Interfaces named explicitly, by qualified name. Also how a user opts out of one a scan would find. */
        fun of(vararg names: String): StructuralIndex =
            StructuralIndex(names.map { it.replace('.', '/') }.toSet())

        /**
         * Every `@Structural` interface on [urls].
         *
         * The annotation is `BINARY`-retained, so it is a `RuntimeInvisibleAnnotation` and reflection never sees it:
         * the only way to find these is to read the class files.
         */
        fun fromClasspath(urls: Array<URL>): StructuralIndex {
            val found = mutableSetOf<String>()
            for (url in urls) {
                val file = runCatching { File(url.toURI()) }.getOrNull() ?: continue
                when {
                    file.isDirectory -> file.walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .forEach { candidate -> structuralInterface(candidate.readBytes())?.let { found += it } }
                    file.isFile -> runCatching {
                        JarFile(file).use { jar ->
                            jar.entries().asSequence()
                                .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                                .forEach { entry ->
                                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                                    structuralInterface(bytes)?.let { found += it }
                                }
                        }
                    }
                }
            }
            return StructuralIndex(found)
        }

        /** The internal name of [bytes] if they are an interface annotated `@Structural`, else null. */
        private fun structuralInterface(bytes: ByteArray): String? = runCatching {
            var name: String? = null
            var isInterface = false
            var structural = false
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        access: Int,
                        className: String,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        name = className
                        isInterface = access and Opcodes.ACC_INTERFACE != 0
                    }

                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        if (descriptor == STRUCTURAL_DESCRIPTOR) structural = true
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            if (isInterface && structural) name else null
        }.getOrNull()
    }
}
