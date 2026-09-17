package dev.structural.processor

/**
 * Processor options. [packages] limits candidate classes to these packages (with subpackages); when empty, every class
 * in the module is a candidate.
 */
class Config(val packages: List<String>) {
    fun includesPackage(name: String): Boolean =
        packages.isEmpty() || packages.any { name == it || name.startsWith("$it.") }

    companion object {
        const val PACKAGES_OPTION = "structural.packages"

        fun parse(options: Map<String, String>): Config =
            Config(options[PACKAGES_OPTION].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }
}
