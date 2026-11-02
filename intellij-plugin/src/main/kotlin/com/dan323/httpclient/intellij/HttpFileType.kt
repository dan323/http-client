package com.dan323.httpclient.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

// Named to avoid clashing with IntelliJ Ultimate's own "HTTP Request" file type.
class HttpFileType private constructor() : LanguageFileType(HttpLanguage) {
    override fun getName() = "HTTP Client File"
    override fun getDescription() = "HTTP request file"
    override fun getDefaultExtension() = "http"
    override fun getIcon(): Icon = AllIcons.Webreferences.Server

    companion object {
        @JvmField val INSTANCE = HttpFileType()
    }
}
