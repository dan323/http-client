package com.dan323.httpclient.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

// Named to avoid clashing with IntelliJ Ultimate's own "HTTP Request" file type.
class HttpFileType private constructor() : LanguageFileType(HttpLanguage) {
    override fun getName() = "HTTP Client File"
    override fun getDescription() = "HTTP request file"
    override fun getDefaultExtension() = "http"
    // /icons/http.svg, with http_dark.svg picked automatically in dark themes (IconLoader caches the result).
    override fun getIcon(): Icon = IconLoader.getIcon("/icons/http.svg", HttpFileType::class.java)

    companion object {
        @JvmField val INSTANCE = HttpFileType()
    }
}
