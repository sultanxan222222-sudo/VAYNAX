package com.vaynax.messenger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VaynaxContact(val id: String, val name: String)
data class VaynaxMessage(val id: String, val senderId: String, val ciphertext: String, val createdAt: Long)
data class VaynaxVoiceNote(val id: String, val senderId: String, val contentType: String, val createdAt: Long, val sizeBytes: Long)

class VaynaxApi(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    private suspend fun request(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val parsedUrl = runCatching { URL(base) }.getOrNull()
        val secureTransport = parsedUrl?.protocol.equals("https", ignoreCase = true)
        val loopbackDevelopment = parsedUrl?.protocol.equals("http", ignoreCase = true) &&
            (parsedUrl?.host.equals("localhost", ignoreCase = true) ||
             parsedUrl?.host == "127.0.0.1" || parsedUrl?.host == "::1")
        require(parsedUrl != null && (secureTransport || loopbackDevelopment)) {
            "Для защиты аккаунта нужен HTTPS. HTTP разрешён только для localhost при локальной разработке."
        }
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (status !in 200..299) throw IllegalStateException(result.optString("error", "Ошибка сервера ($status)"))
            result
        } finally {
            connection.disconnect()
        }
    }

    suspend fun register(name: String): JSONObject =
        request("POST", "/v1/register", body = JSONObject().put("name", name.trim()))

    suspend fun me(token: String): JSONObject = request("GET", "/v1/me", token)
    suspend fun createInvite(token: String): JSONObject = request("POST", "/v1/invites", token)
    suspend fun redeemInvite(token: String, code: String): JSONObject =
        request("POST", "/v1/invites/redeem", token, JSONObject().put("code", code.trim()))
    suspend fun sendMessage(token: String, recipientId: String, ciphertext: String): JSONObject =
        request("POST", "/v1/messages/send", token,
            JSONObject().put("recipientId", recipientId).put("ciphertext", ciphertext))

    suspend fun pendingMessages(token: String): List<VaynaxMessage> {
        val items = request("GET", "/v1/messages/pending", token).optJSONArray("messages") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            VaynaxMessage(item.optString("id"), item.optString("senderId"),
                item.optString("ciphertext"), item.optLong("createdAt"))
        }
    }

    suspend fun acknowledge(token: String, ids: List<String>) {
        val array = JSONArray()
        ids.forEach { array.put(it) }
        request("POST", "/v1/messages/ack", token, JSONObject().put("ids", array))
    }

    // Voice-note bytes must already be encrypted by the client before upload.
    suspend fun uploadVoiceNote(token: String, recipientId: String, encryptedBytes: ByteArray, contentType: String = "audio/mp4"): JSONObject =
        withContext(Dispatchers.IO) {
            require(encryptedBytes.size in 29..(10 * 1024 * 1024)) { "Размер зашифрованной голосовой записи должен быть от 29 байт до 10 МБ." }
            val connection = (URL("$base/v1/voice-notes/$recipientId").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-Vaynax-Content-Type", contentType)
            }
            try {
                connection.outputStream.use { it.write(encryptedBytes) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bodyText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val result = if (bodyText.isBlank()) JSONObject() else JSONObject(bodyText)
                if (status !in 200..299) throw IllegalStateException(result.optString("error", "Ошибка загрузки голосового сообщения ($status)"))
                result
            } finally { connection.disconnect() }
        }

    suspend fun pendingVoiceNotes(token: String): List<VaynaxVoiceNote> {
        val items = request("GET", "/v1/voice-notes/pending", token).optJSONArray("voiceNotes") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            VaynaxVoiceNote(item.optString("id"), item.optString("senderId"), item.optString("contentType"),
                item.optLong("createdAt"), item.optLong("sizeBytes"))
        }
    }

    suspend fun downloadVoiceNote(token: String, id: String): ByteArray = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Неверный идентификатор голосового сообщения." }
        val connection = (URL("$base/v1/voice-notes/$id").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IllegalStateException(runCatching { JSONObject(error).optString("error") }.getOrNull().orEmpty().ifBlank { "Ошибка получения голосового сообщения ($status)" })
            }
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 10 * 1024 * 1024) { "Голосовое сообщение превышает 10 МБ." }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    suspend fun acknowledgeVoiceNotes(token: String, ids: List<String>) {
        val array = JSONArray()
        ids.forEach { array.put(it) }
        request("POST", "/v1/voice-notes/ack", token, JSONObject().put("ids", array))
    }

    suspend fun contacts(token: String): List<VaynaxContact> {
        val items: JSONArray = request("GET", "/v1/contacts", token).optJSONArray("contacts") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            VaynaxContact(item.optString("id"), item.optString("name"))
        }
    }
}
