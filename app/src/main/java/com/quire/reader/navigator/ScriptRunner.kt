package com.quire.reader.navigator

import kotlinx.coroutines.flow.StateFlow

/**
 * Common scripting surface shared by the pager's page fragments and the stack's
 * chapter views, so the navigator can query whichever page the user is reading.
 */
internal interface ScriptRunner {
    val isLoaded: StateFlow<Boolean>

    suspend fun awaitLoaded()

    fun runJavaScript(script: String, callback: ((String) -> Unit)? = null)

    suspend fun runJavaScriptSuspend(javascript: String): String
}