package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.EnvironmentVariables

/** Reads the OS process environment for `{{$env.NAME}}`. */
class SystemEnvironmentVariables(private val source: () -> Map<String, String> = System::getenv) : EnvironmentVariables {
    override fun get(name: String): String? = source()[name]
}
