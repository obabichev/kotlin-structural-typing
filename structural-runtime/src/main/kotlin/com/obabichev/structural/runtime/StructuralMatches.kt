package com.obabichev.structural.runtime

import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Which `@Structural` interfaces a class implements by shape, decided from class file bytes alone.
 *
 * This is the same decision [StructuralTransformer] makes before it rewrites a class, exposed on its own for callers
 * that want the answer without the bytecode — the compiler plugin asks it while deserializing a dependency class, so
 * that what the compiler believes and what the classloader does come from one implementation and cannot drift apart.
 */
class StructuralMatches(private val index: StructuralIndex, resolver: ShapeResolver) {
    private val shapes = ClassShapes(resolver)
    private val matcher = StructuralMatcher(shapes)
    private val cache = ConcurrentHashMap<String, List<String>>()

    /** The interfaces [internalName] matches, as JVM internal names, nearest-first and never one it already has. */
    fun interfacesOf(internalName: String): List<String> {
        cache[internalName]?.let { return it }
        val computed = compute(internalName)
        cache.putIfAbsent(internalName, computed)
        return computed
    }

    private fun compute(internalName: String): List<String> {
        if (index.interfaces.isEmpty()) return emptyList()
        val shape = shapes.of(internalName) ?: return emptyList()
        if (shape.isInterface || shape.isAnnotation || shape.isSynthetic) return emptyList()
        val members = shapes.superclassChain(internalName).flatMap(::declaredMembers)
        return index.interfaces
            .mapNotNull { matcher.structuralInterface(it) }
            .filterNot { shapes.inheritsFrom(internalName, it.internalName) }
            .filter { matcher.implementsByShape(members, it) }
            .map { it.internalName }
    }
}

/**
 * Reads class bytes from a classpath of directories and jars, without loading anything. Jars are opened once and kept
 * open, so close the resolver when the classpath is no longer needed.
 */
class ClasspathShapeResolver(roots: List<File>) : ShapeResolver, AutoCloseable {
    private val directories = roots.filter { it.isDirectory }
    private val jars = roots.filter { it.isFile }.mapNotNull { runCatching { JarFile(it) }.getOrNull() }

    override fun bytes(internalName: String): ByteArray? {
        val path = "$internalName.class"
        for (directory in directories) {
            val file = File(directory, path)
            if (file.isFile) return runCatching { file.readBytes() }.getOrNull()
        }
        for (jar in jars) {
            val entry = jar.getEntry(path) ?: continue
            return runCatching { jar.getInputStream(entry).use { it.readBytes() } }.getOrNull()
        }
        // The JDK's own classes are not on the classpath but the matcher reads them, to decide that Integer is a Number.
        return ClassLoader.getPlatformClassLoader().getResourceAsStream(path)?.use { it.readBytes() }
    }

    override fun close() {
        jars.forEach { runCatching { it.close() } }
    }

    companion object {
        fun urlsOf(roots: List<File>): Array<URL> = roots.map { it.toURI().toURL() }.toTypedArray()
    }
}
