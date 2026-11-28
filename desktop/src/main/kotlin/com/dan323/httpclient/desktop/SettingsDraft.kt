package com.dan323.httpclient.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dan323.httpclient.core.port.out.Allowlists
import com.dan323.httpclient.viewmodel.AllowlistForm

/** The Settings panel being edited: the two allowlist texts (one entry per line) and the errors of the last save attempt. */
class SettingsDraft(private val saved: Allowlists) {
    var envText by mutableStateOf(AllowlistForm.text(saved.envNames))
    var rootsText by mutableStateOf(AllowlistForm.text(saved.fileRoots))
    var errors by mutableStateOf(emptyList<String>())
        internal set

    /** True when saving would change what is allowed. */
    val dirty: Boolean get() = AllowlistForm.parse(envText, rootsText) != saved
}
