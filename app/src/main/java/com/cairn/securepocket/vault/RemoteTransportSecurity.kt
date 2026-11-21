package com.cairn.securepocket.vault

import com.cairn.securepocket.BuildConfig
import java.net.URI

/** Release sends private recordings and credentials only over TLS. Debug/validation can use emulator loopback. */
internal fun requireSecureRemote(uri: URI) {
    require(uri.scheme == "https" || (isLocalHttpAllowed(uri))) {
        "Remote recording and credential routes require HTTPS. Local HTTP is limited to debug loopback fixtures."
    }
}

private fun isLocalHttpAllowed(uri: URI): Boolean {
    if (uri.scheme != "http" || BuildConfig.BUILD_TYPE !in setOf("debug", "validation")) return false
    return uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2", "10.0.3.2")
}

internal fun checkedRemoteUri(raw: String): URI {
    val uri = URI(raw)
    require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Enter a valid storage server URL."
    }
    requireSecureRemote(uri)
    return uri
}
