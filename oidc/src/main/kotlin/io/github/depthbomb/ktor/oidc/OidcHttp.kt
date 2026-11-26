package io.github.depthbomb.ktor.oidc

import com.nimbusds.oauth2.sdk.http.HTTPRequest
import com.nimbusds.oauth2.sdk.http.HTTPResponse
import java.io.IOException
import java.net.HttpURLConnection

internal fun HTTPRequest.sendToProvider(maxBytes: Int = 1024 * 1024): HTTPResponse {
    followRedirects = false
    connectTimeout = 5_000
    readTimeout = 10_000

    val connection = toHttpURLConnection()
    try {
        val status = connection.responseCode
        if (status in 300..399) {
            throw IOException("OIDC endpoint returned a redirect (HTTP $status)")
        }

        val length = connection.contentLengthLong
        if (length > maxBytes) {
            throw IOException("OIDC response exceeds $maxBytes bytes")
        }

        val response = HTTPResponse(status)
        for ((name, values) in connection.headerFields) {
            if (name != null) {
                response.setHeader(name, *values.toTypedArray())
            }
        }

        val stream =
            if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
        stream?.use {
            val bytes = it.readNBytes(if (length < 0) maxBytes + 1 else length.toInt() + 1)
            if (bytes.size > maxBytes) {
                throw IOException("OIDC response exceeds $maxBytes bytes")
            }

            if (length >= 0 && bytes.size.toLong() != length) {
                throw IOException("OIDC response size differs from Content-Length")
            }

            response.body = bytes.toString(Charsets.UTF_8)
        }

        return response
    } catch (exception: IOException) {
        connection.disconnect()
        throw exception
    }
}
