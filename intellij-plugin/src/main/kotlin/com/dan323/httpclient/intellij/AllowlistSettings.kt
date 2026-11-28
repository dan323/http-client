package com.dan323.httpclient.intellij

import com.dan323.httpclient.adapters.FileAccessPolicy
import com.dan323.httpclient.adapters.SystemEnvironmentVariables
import com.dan323.httpclient.core.port.out.Allowlists
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the user allowed under Settings | Tools | [DISPLAY_NAME]: application level, in the IDE's own configuration
 * (never in a project's `.idea`, which a cloned repository could bring) and not synced to other machines (the folders
 * are local paths). Added to `-Dhttpclient.allowEnv` / `HTTPC_ALLOW_ENV` and `-Dhttpclient.allowFileRoots` /
 * `HTTPC_ALLOW_FILE_ROOTS`.
 */
@Service(Service.Level.APP)
@State(name = "HttpClientAllowlists", storages = [Storage("httpClientAllowlists.xml", roamingType = RoamingType.DISABLED)])
class AllowlistSettings : PersistentStateComponent<AllowlistSettings.Saved> {
    /** The stored form (lists of plain text). */
    class Saved {
        var envNames: MutableList<String> = mutableListOf()
        var fileRoots: MutableList<String> = mutableListOf()
    }

    @Volatile var allowlists: Allowlists = Allowlists()
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState() = Saved().also {
        it.envNames = allowlists.envNames.toMutableList()
        it.fileRoots = allowlists.fileRoots.toMutableList()
    }

    override fun loadState(saved: Saved) {
        allowlists = Allowlists(saved.envNames.toList(), saved.fileRoots.toList())
    }

    /** Saves [granted]; when it changed, the listeners are told (the projects forget their remembered responses). */
    fun update(granted: Allowlists) {
        if (granted == allowlists) return
        allowlists = granted
        listeners.forEach { it() }
    }

    /** Calls [listener] after every change; unregistered when [parent] is disposed. */
    fun onChange(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent, Disposable { listeners -= listener })
    }

    companion object {
        const val DISPLAY_NAME = "HTTP Client: Allowed Access"

        /** Where to allow a folder, in the message of a refused `< path` / `>> path`. */
        val FILE_HINT = "add the folder under Settings | Tools | $DISPLAY_NAME, or ${FileAccessPolicy.DEFAULT_HINT}"

        /** Where to allow a name, in the message of a refused `{{$env.NAME}}`. */
        const val ENV_HINT = "add it under Settings | Tools | $DISPLAY_NAME, or ${SystemEnvironmentVariables.DEFAULT_HINT}"

        fun getInstance(): AllowlistSettings = ApplicationManager.getApplication().getService(AllowlistSettings::class.java)
    }
}
