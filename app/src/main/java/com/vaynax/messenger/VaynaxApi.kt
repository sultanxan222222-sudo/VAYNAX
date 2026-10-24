package com.vaynax.messenger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VaynaxContact(val id: String, val name: String)
data class VaynaxMessage(val id: String, val senderId: String, val ciphertext: String, val createdAt: Long)

class VaynaxApi(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    private suspend fun request(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "Укажи адрес сервера, начинающийся с https://"
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

    suspend fun contacts(token: String): List<VaynaxContact> {
        val items: JSONArray = request("GET", "/v1/contacts", token).optJSONArray("contacts") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            VaynaxContact(item.optString("id"), item.optString("name"))
        }
    }
}
