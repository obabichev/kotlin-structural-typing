package com.obabichev.structural.runtime

import java.net.URL
import java.net.URLClassLoader
import java.security.CodeSource
import java.util.concurrent.ConcurrentHashMap

/**
 * A classloader that makes a class implement the `@Structural` interfaces its shape satisfies, by rewriting its bytes
 * on the way in. It is what lets a class from a dependency — compiled without the plugin, its class file fixed — still
 * be a `Sized`.
 *
 * It loads **self-first**: a class the parent has already defined can never be transformed, which is the whole point.
 * Only the JDK's own packages and this runtime's are left to the parent.
 *
 * ### The interface has to be the same class on both sides
 *
 * A transformed class's `implements Sized` resolves through *its* defining loader. Code checking `instanceof` uses
 * *its* `Sized`. If those are two different classes the check is simply false, with no error anywhere — it looks
 * exactly like a failed match. Two arrangements work:
 *
 * - put the whole application classpath on the loader and run its entry point through [StructuralLauncher], so one
 *   loader defines everything and exactly one `Sized` exists;
 * - put only the dependency's classes on the loader and leave the interfaces to the parent, which is enough when the
 *   transformed objects are only ever handled as `Any` or as the interface.
 *
 * Putting the interfaces on *both* is the arrangement that fails silently, so the loader checks for it and says so.
 */
class StructuralClassLoader(
    urls: Array<URL>,
    parent: ClassLoader?,
    index: StructuralIndex,
    private val listener: (String) -> Unit = {},
) : URLClassLoader(urls, parent) {

    private val transformer = StructuralTransformer(index, ::classBytes, listener)
    private val reported = ConcurrentHashMap.newKeySet<String>()

    init {
        index.names.forEach(::checkInterfaceIsShared)
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        findLoadedClass(name)
            ?: if (parentFirst(name)) {
                super.loadClass(name, resolve)
            } else {
                runCatching { findClass(name) }.getOrElse { super.loadClass(name, resolve) }
                    .also { if (resolve) resolveClass(it) }
            }
    }

    override fun findClass(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val url = findResource(path) ?: throw ClassNotFoundException(name)
        val bytes = url.openStream().use { it.readBytes() }
        val transformed = transformer.transform(name.replace('.', '/'), bytes) ?: bytes
        definePackageOf(name, url)
        return defineClass(name, transformed, 0, transformed.size, CodeSource(codeSourceOf(url, path), null as Array<java.security.cert.Certificate>?))
    }

    /**
     * Bytes for the matcher, which must never load a class to inspect one. The loader's own URLs come first, then the
     * parent — including the JDK, whose classes the matcher reads to decide that `Integer` is a `Number`.
     */
    private fun classBytes(internalName: String): ByteArray? {
        val path = "$internalName.class"
        val url = findResource(path)
            ?: parent?.getResource(path)
            ?: ClassLoader.getPlatformClassLoader().getResource(path)
            ?: return null
        return runCatching { url.openStream().use { it.readBytes() } }.getOrNull()
    }

    /**
     * The JDK's packages must come from the parent, and so must this runtime's: loading a second copy of the
     * transformer would leave its types unable to meet the ones the caller holds.
     */
    private fun parentFirst(name: String): Boolean = PARENT_FIRST.any { name.startsWith(it) }

    /** Warns when the interface exists on both sides of the loader, which makes every `instanceof` quietly false. */
    private fun checkInterfaceIsShared(name: String) {
        val parent = parent ?: return
        val path = name.replace('.', '/') + ".class"
        if (findResource(path) != null && parent.getResource(path) != null && !parentFirst(name)) {
            if (reported.add(name)) {
                listener(
                    "$name is on this loader's classpath and on its parent's, so it will be loaded twice and no " +
                        "transformed class will look like it. Put it on only one of them.",
                )
            }
        }
    }

    private fun definePackageOf(name: String, url: URL) {
        val packageName = name.substringBeforeLast('.', "")
        if (packageName.isEmpty() || getDefinedPackage(packageName) != null) return
        runCatching { definePackage(packageName, null, null, null, null, null, null, null) }
    }

    private fun codeSourceOf(url: URL, path: String): URL =
        runCatching {
            val text = url.toString()
            if (text.startsWith("jar:")) URL(text.substringBeforeLast("!/") + "!/") else URL(text.removeSuffix(path))
        }.getOrDefault(url)

    companion object {
        init {
            registerAsParallelCapable()
        }

        private val PARENT_FIRST = listOf(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
            "com.obabichev.structural.runtime.",
        )
    }
}
