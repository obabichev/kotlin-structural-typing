package com.obabichev.structural.ide

import java.io.File

/** TEMPORARY: records whether the IDE asks this plugin for a compiler plugin jar. Delete after the check. */
internal object IdeDebug {
    private val log = File("/tmp/structural-debug.log")

    fun note(message: String) {
        runCatching { log.appendText("${System.currentTimeMillis()} ide: $message\n") }
    }
}
