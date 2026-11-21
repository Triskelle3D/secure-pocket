package com.cairn.securepocket

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Diagnostic only: pins WebDAV PROPFIND to the currently connected Wi-Fi Network and reports the
 * exact failing step/exception without changing settings or app permissions.
 */
class WebDavProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun requireIsolatedPackage() {
        check(context.packageName.endsWith(".isotest")) { "WebDAV probes require the isolated validation app." }
    }

    @Test fun webDavPropfindOverWifiReportsHttpOrExactFailure() {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("webdav_url")?.trim()?.trimEnd('/').orEmpty()
        assumeTrue("Set instrumentation arg webdav_url to the isolated fixture", base.isNotBlank())
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        assumeTrue("No connected Wi-Fi Network is available to pin the WebDAV probe", wifi != null)
        val user = args.getString("webdav_user", "SyntheticTest")
        val password = args.getString("webdav_password", "FixtureOnly")
        var step = "open Wi-Fi-bound connection"
        try {
            val connection = (wifi!!.openConnection(URL("$base/")) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
                requestMethod = "PROPFIND"
                doOutput = true
                setRequestProperty("Depth", "0")
                setRequestProperty("Content-Type", "application/xml; charset=utf-8")
                val basic = android.util.Base64.encodeToString("$user:$password".toByteArray(), android.util.Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $basic")
            }
            step = "write PROPFIND body"
            val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".toByteArray()
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            step = "read PROPFIND response"
            val code = connection.responseCode
            val response = if (code in 200..299) connection.inputStream else connection.errorStream
            response?.use { it.readBytes() }
            connection.disconnect()
            assertTrue("Wi-Fi-bound PROPFIND returned HTTP $code", code in 200..299)
        } catch (error: Exception) {
            throw AssertionError("WebDAV probe failed while trying to $step: ${error.javaClass.name}: ${error.message}", error)
        }
    }
}
