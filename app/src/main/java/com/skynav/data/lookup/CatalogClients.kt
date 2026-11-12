package com.skynav.data.lookup

import com.skynav.domain.lookup.CatalogPoint
import com.skynav.domain.lookup.parseOpenCellId
import com.skynav.domain.lookup.parseWigleSearch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/** Looks up one cell in OpenCelliD. A blank token or a missing cell returns null. */
class OpenCellIdClient(private val token: String) {

    fun lookup(mcc: Int, mnc: Int, tac: Int, cellId: Long, radio: String): CatalogPoint? {
        if (token.isBlank()) return null
        val query = buildString {
            append("key=").append(encode(token))
            append("&mcc=").append(mcc)
            append("&mnc=").append(mnc)
            append("&lac=").append(tac)
            append("&cellid=").append(cellId)
            append("&radio=").append(encode(radio))
            append("&format=json")
        }
        val body = get("https://opencellid.org/cell/get?$query") ?: return null
        return parseOpenCellId(body)
    }
}

/**
 * Looks up one BSSID in WiGLE, the open Wi-Fi catalog.
 * Needs the API name and token from https://wigle.net/account. Blank credentials return null.
 */
class WigleClient(private val apiName: String, private val apiToken: String) {

    val configured: Boolean get() = apiName.isNotBlank() && apiToken.isNotBlank()

    fun lookup(bssid: String): CatalogPoint? {
        if (!configured) return null
        val query = "onlymine=false&netid=${encode(bssid)}"
        val auth = Base64.getEncoder().encodeToString("$apiName:$apiToken".toByteArray())
        val body = get("https://api.wigle.net/api/v2/network/search?$query", "Basic $auth") ?: return null
        return parseWigleSearch(body)
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun get(url: String, authorization: String? = null): String? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 8_000
    connection.readTimeout = 8_000
    connection.setRequestProperty("Accept", "application/json")
    if (authorization != null) connection.setRequestProperty("Authorization", authorization)
    return try {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        stream?.bufferedReader()?.use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
