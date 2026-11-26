package io.github.depthbomb.ktor.signedurls

import io.ktor.http.*
import java.net.URI
import java.net.URISyntaxException

internal data class SignedTarget(val canonical: String, val token: String?)

private class QueryField(val name: String, val formName: String, val value: String?)

private val byName = compareBy(String.CASE_INSENSITIVE_ORDER, QueryField::name)
private val byFormName = compareBy(String.CASE_INSENSITIVE_ORDER, QueryField::formName)

private class QueryFields(private val parameterName: String) {
    private val fields = ArrayList<QueryField>()
    private var hasFormNames = false
    var token: String? = null
        private set

    fun add(rawName: String, rawValue: String?): Boolean {
        val name = decodeComponent(rawName, plusIsSpace = false) ?: return false
        // Netty applies form decoding to names as well as values. Preserve both readings of a literal '+'.
        val formName = if ('+' in rawName) rawName.decodeURLQueryComponent(plusIsSpace = true) else name
        val value = if (rawValue == null) null else decodeComponent(rawValue, plusIsSpace = true) ?: return false
        if (name.equals(parameterName, ignoreCase = true) ||
            (formName != name && formName.equals(parameterName, ignoreCase = true))
        ) {
            if (token != null || name != parameterName || formName != name || value.isNullOrEmpty()) {
                return false
            }
            token = value
        } else {
            fields.add(QueryField(name, formName, value))
            if (formName != name) {
                hasFormNames = true
            }
        }

        return true
    }

    // Read all occurrences, including bare fields and fields beyond Ktor's query parser limit.
    fun read(query: String, start: Int): Boolean {
        var fieldStart = start
        var equals = -1
        for (index in start..query.length) {
            if (index < query.length && query[index] != '&') {
                if (query[index] == '=' && equals < 0) {
                    equals = index
                }
                continue
            }

            val name = query.substring(fieldStart, if (equals < 0) index else equals)
            val value = if (equals < 0) null else query.substring(equals + 1, index)
            if (!add(name, value)) {
                return false
            }

            fieldStart = index + 1
            equals = -1
        }
        return true
    }

    fun canonical(path: String): String {
        if (fields.isEmpty()) {
            return path
        }

        // Both stable sorts must start in wire order for repeated names.
        val formFields = if (hasFormNames) fields.sortedWith(byFormName) else null

        fields.sortWith(byName)

        return buildString {
            append(path)
            appendQuery(fields, QueryField::name)
            if (formFields != null) {
                append('\n')
                appendQuery(formFields, QueryField::formName)
            }
        }
    }
}

internal fun parseTarget(fullPath: String, parameterName: String, requireToken: Boolean = false): SignedTarget? {
    // Proxies can send an absolute-form HTTP request target to the server.
    if (!fullPath.startsWith('/') && (fullPath.startsWith("http://", true) || fullPath.startsWith("https://", true))) {
        val uri = try {
            URI(fullPath)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.rawAuthority.isNullOrEmpty() || uri.rawFragment != null) {
            return null
        }
        val path = uri.rawPath.ifEmpty { "/" }
        return parseTarget(if (uri.rawQuery == null) path else "$path?${uri.rawQuery}", parameterName, requireToken)
    }

    val separator = fullPath.indexOf('?')
    val path = (if (separator < 0) fullPath else fullPath.substring(0, separator)).ifEmpty { "/" }
    if (separator < 0 || separator == fullPath.lastIndex) {
        return if (requireToken) null else SignedTarget(path, null)
    }
    // A token name must occur literally or contain a percent escape on the wire.
    if (requireToken && fullPath.indexOf(parameterName, separator + 1) < 0 && fullPath.indexOf(
            '%',
            separator + 1
        ) < 0
    ) {
        return null
    }

    val query = QueryFields(parameterName)
    if (!query.read(fullPath, separator + 1) || (requireToken && query.token == null)) {
        return null
    }

    return SignedTarget(query.canonical(path), query.token)
}

internal fun URLBuilder.targetForSigning(parameterName: String): String {
    require(protocol.name == "http" || protocol.name == "https") { "Signed URLs require HTTP or HTTPS" }

    val rawPath = encodedPath
    require(rawPath.none { it in "?#\\" || it <= ' ' || it == '\u007f' }) { "Invalid encoded URL path" }

    val path = if (rawPath.startsWith('/')) rawPath else "/$rawPath"
    val query = QueryFields(parameterName)
    for ((name, values) in encodedParameters.entries()) {
        val nameHasDelimiter = '&' in name || '=' in name
        if (values.isEmpty()) {
            require(if (nameHasDelimiter) query.read(name, 0) else query.add(name, null)) { "Invalid signed URL query" }
        }
        for (value in values) {
            // The encoded builder API can supply literal separators. Match how Ktor renders these fields.
            val valid = if (nameHasDelimiter || '&' in value) query.read("$name=$value", 0) else query.add(name, value)
            require(valid) { "Invalid signed URL query" }
        }
    }

    require(query.token == null) { "The URL already contains the reserved parameter $parameterName" }

    return query.canonical(path)
}

private inline fun StringBuilder.appendQuery(fields: List<QueryField>, name: (QueryField) -> String) {
    fields.forEachIndexed { index, field ->
        append(if (index == 0) '?' else '&')
        appendParameter(name(field))
        if (field.value != null) {
            append('=')
            appendParameter(field.value)
        }
    }
}

private const val HEX = "0123456789ABCDEF"

private fun StringBuilder.appendParameter(value: String) {
    for ((index, char) in value.withIndex()) {
        when (char) {
            in 'a'..'z', in 'A'..'Z', in '0'..'9', '-', '.', '_', '~' -> append(char)
            in '\u0000'..'\u007f' -> {
                append('%')
                append(HEX[char.code ushr 4])
                append(HEX[char.code and 15])
            }

            else -> {
                append(value.substring(index).encodeURLParameter())
                return
            }
        }
    }
}

// Decode percent escapes strictly so malformed UTF-8 cannot match a signed replacement character.
private fun decodeComponent(encoded: String, plusIsSpace: Boolean): String? {
    if (encoded.any { it <= ' ' || it == '#' || it == '\u007f' }) {
        return null
    }

    if ('%' !in encoded) {
        return if (plusIsSpace) encoded.replace('+', ' ') else encoded
    }

    val bytes = encoded.encodeToByteArray()
    var read = 0
    var written = 0
    while (read < bytes.size) {
        val byte = bytes[read++]
        bytes[written++] = when (byte.toInt()) {
            '%'.code -> {
                if (read + 2 > bytes.size) {
                    return null
                }
                val high = bytes[read++].toInt().toChar().digitToIntOrNull(16) ?: return null
                val low = bytes[read++].toInt().toChar().digitToIntOrNull(16) ?: return null
                ((high shl 4) or low).toByte()
            }

            '+'.code -> if (plusIsSpace) ' '.code.toByte() else byte
            else -> byte
        }
    }

    return try {
        bytes.decodeToString(endIndex = written, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}
