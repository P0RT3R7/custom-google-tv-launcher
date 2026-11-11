package com.custom.hisenselauncher.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Tiny blocking HTTP helper. Call off the main thread only. */
object Http {
    const val USER_AGENT = "HisenseLauncher/2.0 (personal Android TV launcher)"

    class Response(val code: Int, val body: String)

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val r = request("GET", url, null, headers)
        if (r.code !in 200..299) throw IOException("HTTP ${r.code} for $url")
        return r.body
    }

    fun request(method: String, url: String, body: String?, headers: Map<String, String> = emptyMap()): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", headers["Content-Type"] ?: "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
