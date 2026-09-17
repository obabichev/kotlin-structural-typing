package dev.structural.processor

/** Processor options. [packages] are the packages (with subpackages) whose classes are candidates. */
class Config(val packages: List<String>) {
    fun includesPackage(name: String): Boolean = packages.any { name == it || name.startsWith("$it.") }

    companion object {
        const val PACKAGES_OPTION = "structural.packages"

        fun parse(options: Map<String, String>): Config? =
            options[PACKAGES_OPTION]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?.let(::Config)
    }
}
