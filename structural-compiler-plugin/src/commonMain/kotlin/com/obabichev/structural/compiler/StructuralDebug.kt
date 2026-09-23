package com.obabichev.structural.compiler

import java.io.File

/** TEMPORARY: records what the plugin receives, to find out what reaches it inside IntelliJ. Delete after the check. */
internal object StructuralDebug {
    private val log = File("/tmp/structural-debug.log")

    fun note(message: String) {
        runCatching { log.appendText("${System.currentTimeMillis()} $message\n") }
    }
}
