package com.vaynax.messenger

import android.os.Bundle
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Emerald = Color(0xFF063F32)
private val DeepEmerald = Color(0xFF032B24)
private val Gold = Color(0xFFE6C477)
private val Snow = Color(0xFFF5F7F8)
private val ChechenRed = Color(0xFFC7353D)

/** Synthesized resonant glass-clink notification; no external audio file required. */
private fun playMessageClink() {
    Thread {
        try {
            val rate = 44100
            val count = (rate * 0.62).toInt()
            val samples = ShortArray(count)
            val frequencies = doubleArrayOf(1760.0, 2315.0, 2960.0, 3540.0, 4210.0)
            for (i in 0 until count) {
                val t = i.toDouble() / rate
                var value = 0.0
                for ((index, frequency) in frequencies.withIndex()) {
                    val delay = if (index % 2 == 0) 0.0 else 0.016
                    if (t >= delay) {
                        val local = t - delay
                        value += sin(2.0 * PI * frequency * local) * exp(-local * (7.0 + index)) / (index + 1.0)
                    }
                }
                samples[i] = (value * exp(-t * 3.0) * 9000.0).toInt().coerceIn(-32768, 32767).toShort()
            }
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return@Thread
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, samples.size * 2)).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(720)
            track.release()
        } catch (_: Exception) { }
    }.start()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { VaynaxApp() } }
    }
}

@Composable
private fun VaynaxApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vaynax_private", android.content.Context.MODE_PRIVATE) }
    var showChats by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(prefs.getString("server_url", "https://vaynax-server.onrender.com") ?: "https://vaynax-server.onrender.com") }
    var token by remember { mutableStateOf(prefs.getString("account_token", "") ?: "") }
    var accountName by remember { mutableStateOf(prefs.getString("account_name", "") ?: "") }
    Surface(Modifier.fillMaxSize(), color = DeepEmerald) {
        if (!showChats) WelcomeScreen(onContinue = { showChats = true })
        else ChatHome(baseUrl, { baseUrl = it; prefs.edit().putString("server_url", it).apply() },
            token, { token = it; prefs.edit().putString("account_token", it).apply() },
            accountName, { accountName = it; prefs.edit().putString("account_name", it).apply() },
            onBack = { showChats = false })
    }
}

@Composable
private fun WelcomeScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(DeepEmerald).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Ассаляму алейкум", color = Snow, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(24.dp))
        MountainTowerArt(Modifier.fillMaxWidth().height(260.dp))
        Spacer(Modifier.height(18.dp))
        Text("VAYNAX", color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Spacer(Modifier.height(8.dp))
        Text("Связь с близкими. Где бы вы ни были.", color = Snow, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onContinue,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepEmerald)
        ) { Text("Продолжить", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun MountainTowerArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Emerald, size = Size(w, h))
        // Snow-covered Caucasus ridgelines.
        val back = Path().apply {
            moveTo(0f, h * .70f)
            lineTo(w * .18f, h * .28f)
            lineTo(w * .27f, h * .42f)
            lineTo(w * .43f, h * .10f)
            lineTo(w * .60f, h * .43f)
            lineTo(w * .73f, h * .24f)
            lineTo(w, h * .66f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(back, Color(0xFF426E6A))
        val snowCaps = Path().apply {
            moveTo(w * .12f, h * .42f); lineTo(w * .18f, h * .28f); lineTo(w * .24f, h * .38f)
            lineTo(w * .20f, h * .36f); lineTo(w * .18f, h * .40f); lineTo(w * .16f, h * .36f); close()
            moveTo(w * .34f, h * .29f); lineTo(w * .43f, h * .10f); lineTo(w * .52f, h * .29f)
            lineTo(w * .46f, h * .25f); lineTo(w * .43f, h * .29f); lineTo(w * .40f, h * .23f); close()
            moveTo(w * .68f, h * .33f); lineTo(w * .73f, h * .24f); lineTo(w * .80f, h * .36f)
            lineTo(w * .74f, h * .32f); lineTo(w * .72f, h * .35f); close()
        }
        drawPath(snowCaps, Snow)
        // Stylized stone watchtower silhouette.
        val left = w * .39f
        val right = w * .61f
        val top = h * .38f
        val base = h * .88f
        val tower = Path().apply {
            moveTo(left + w * .025f, base)
            lineTo(left + w * .045f, top + h * .08f)
            lineTo(left, top + h * .08f)
            lineTo(left + w * .015f, top)
            lineTo(right - w * .015f, top)
            lineTo(right, top + h * .08f)
            lineTo(right - w * .045f, top + h * .08f)
            lineTo(right - w * .025f, base)
            close()
        }
        drawPath(tower, Color(0xFFB9A98C))
        drawLine(Gold, Offset(left, top + h * .08f), Offset(right, top + h * .08f), 3f)
        drawRect(DeepEmerald, topLeft = Offset(w * .485f, h * .67f), size = Size(w * .03f, h * .10f))
        // Small golden ornamental strokes.
        drawLine(Gold, Offset(w * .43f, h * .49f), Offset(w * .57f, h * .49f), 2f)
        drawLine(Gold, Offset(w * .44f, h * .54f), Offset(w * .56f, h * .54f), 2f)
        drawLine(ChechenRed, Offset(w * .08f, h * .94f), Offset(w * .92f, h * .94f), 3f)
        drawRect(Gold, topLeft = Offset(w * .08f, h * .955f), size = Size(w * .84f, 2f))
    }
}

@Composable
private fun ChatHome(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    accountName: String,
    onAccountNameChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var nameInput by remember { mutableStateOf(accountName) }
    var inviteCode by remember { mutableStateOf("") }
    var createdInvite by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<VaynaxContact>>(emptyList()) }
    var selectedContact by remember { mutableStateOf<VaynaxContact?>(null) }
    var messageDraft by remember { mutableStateOf("") }
    var chatMessages by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var status by remember { mutableStateOf("Укажи адрес работающего сервера, чтобы подключить аккаунт.") }
    var busy by remember { mutableStateOf(false) }

    // Keep the open chat fresh while the app is in the foreground.
    // Messages from other contacts stay queued until their own chat is opened.
    LaunchedEffect(baseUrl, token, selectedContact?.id) {
        val activeContact = selectedContact
        if (token.isNotBlank() && baseUrl.isNotBlank() && activeContact != null) {
            while (true) {
                delay(3500)
                try {
                    val api = VaynaxApi(baseUrl)
                    val pending = api.pendingMessages(token)
                    val incoming = pending.filter { it.senderId == activeContact.id }
                    if (incoming.isNotEmpty()) {
                        val decoded = incoming.mapNotNull { msg ->
                            try {
                                val bytes = android.util.Base64.decode(
                                    msg.ciphertext.removePrefix("v1:"),
                                    android.util.Base64.DEFAULT
                                )
                                Pair(msg.senderId, String(bytes, Charsets.UTF_8))
                            } catch (_: Exception) { null }
                        }
                        if (decoded.isNotEmpty()) {
                            chatMessages = (chatMessages + decoded).takeLast(200)
                            playMessageClink()
                        }
                        api.acknowledge(token, incoming.map { it.id })
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Temporary network failures are retried on the next cycle.
                }
            }
        }
    }

    fun runRequest(action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { status = action() }
            catch (e: Exception) { status = e.message ?: "Не удалось выполнить запрос" }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(DeepEmerald).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VAYNAX", color = Gold, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onBack) { Text("Назад") }
        }
        Text("Подключение и контакты", color = Snow, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = baseUrl, onValueChange = onBaseUrlChange, modifier = Modifier.fillMaxWidth(),
                label = { Text("Адрес сервера HTTPS") }, singleLine = true, placeholder = { Text("https://твой-сервер.example") })
            if (token.isBlank()) {
                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Твой псевдоним") }, singleLine = true)
                Button(enabled = !busy && nameInput.trim().isNotEmpty() && (baseUrl.startsWith("https://") || baseUrl.startsWith("http://")),
                    onClick = { runRequest {
                        val result = VaynaxApi(baseUrl).register(nameInput)
                        val newToken = result.getString("token")
                        val newName = result.optString("name", nameInput.trim())
                        onTokenChange(newToken); onAccountNameChange(newName)
                        "Аккаунт создан: $newName"
                    } }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Подключение…" else "Создать аккаунт") }
            } else {
                Text("Аккаунт: $accountName", color = Gold, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { runRequest {
                        val me = VaynaxApi(baseUrl).me(token); "Подключено как ${me.optString("name", accountName)}"
                    } }, modifier = Modifier.weight(1f)) { Text("Проверить") }
                    Button(enabled = !busy, onClick = { runRequest {
                        val result = VaynaxApi(baseUrl).createInvite(token)
                        createdInvite = result.getString("code"); "Приглашение создано. Действует 7 дней."
                    } }, modifier = Modifier.weight(1f)) { Text("Создать код") }
                }
                if (createdInvite.isNotBlank()) Text("Код приглашения: $createdInvite", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = inviteCode, onValueChange = { inviteCode = it.uppercase() },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Код друга") }, singleLine = true)
                Button(enabled = !busy && inviteCode.trim().isNotEmpty(), onClick = { runRequest {
                    val peer = VaynaxApi(baseUrl).redeemInvite(token, inviteCode)
                    inviteCode = ""
                    contacts = VaynaxApi(baseUrl).contacts(token)
                    "Добавлен контакт: ${peer.optString("name")}"
                } }, modifier = Modifier.fillMaxWidth()) { Text("Добавить по коду") }
                Button(enabled = !busy, onClick = { runRequest {
                    contacts = VaynaxApi(baseUrl).contacts(token); "Контактов: ${contacts.size}"
                } }, modifier = Modifier.fillMaxWidth()) { Text("Обновить контакты") }
                Text("Контакты", color = Snow, fontWeight = FontWeight.SemiBold)
                if (contacts.isEmpty()) Text("Пока нет загруженных контактов.", color = Snow, fontSize = 13.sp)
                contacts.forEach { contact ->
                    Text("• ${contact.name}", color = if (selectedContact?.id == contact.id) Gold else Snow,
                        fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable {
                            selectedContact = contact
                            chatMessages = emptyList()
                            status = "Выбран контакт: ${contact.name}. Нажми «Загрузить сообщения»."
                        }.padding(vertical = 4.dp))
                }
                if (selectedContact != null) {
                    Text("Чат: ${selectedContact!!.name}", color = Gold, fontWeight = FontWeight.Bold)
                    Button(enabled = !busy, onClick = { runRequest {
                        val activeContact = selectedContact ?: error("Сначала выбери контакт")
                        val api = VaynaxApi(baseUrl)
                        // Only acknowledge messages from the currently open chat.
                        // Acknowledging every pending message here silently discarded
                        // messages belonging to other contacts.
                        val pending = api.pendingMessages(token)
                        val incoming = pending.filter { it.senderId == activeContact.id }
                        val decoded = incoming.mapNotNull { msg ->
                            try {
                                val bytes = android.util.Base64.decode(msg.ciphertext.removePrefix("v1:"), android.util.Base64.DEFAULT)
                                Pair(msg.senderId, String(bytes, Charsets.UTF_8))
                            } catch (_: Exception) { null }
                        }
                        chatMessages = (chatMessages + decoded).takeLast(200)
                        if (decoded.isNotEmpty()) playMessageClink()
                        if (incoming.isNotEmpty()) api.acknowledge(token, incoming.map { it.id })
                        "Загружено сообщений этого чата: ${decoded.size}. Важно: содержимое пока не защищено сквозным шифрованием."
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Загрузить сообщения") }
                    chatMessages.takeLast(8).forEach { item ->
                        val sender = contacts.firstOrNull { it.id == item.first }?.name ?: if (item.first == "me") "Ты" else "Контакт"
                        Text("$sender: ${item.second}", color = Snow, fontSize = 13.sp)
                    }
                    OutlinedTextField(value = messageDraft, onValueChange = { messageDraft = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Сообщение") })
                    Button(enabled = !busy && messageDraft.isNotBlank(), onClick = {
                        val target = selectedContact
                        val draft = messageDraft
                        if (target != null) runRequest {
                            val encoded = "v1:" + android.util.Base64.encodeToString(draft.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                            VaynaxApi(baseUrl).sendMessage(token, target.id, encoded)
                            chatMessages = (chatMessages + Pair("me", draft)).takeLast(200)
                            messageDraft = ""
                            "Сообщение отправлено в очередь. Получатель увидит его после загрузки сообщений."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Отправить") }
                }
            }
            Text(status, color = Gold, fontSize = 14.sp)
            Text("Переписка, сквозное шифрование, QR-коды и звонки ещё не реализованы. Не используй тестовый сервер для личных данных.",
                color = Snow, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FeatureRow(title: String, status: String) {
    Column(
        Modifier.fillMaxWidth().background(Emerald, RoundedCornerShape(12.dp)).padding(16.dp)
    ) {
        Text(title, color = Snow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(status, color = Gold, fontSize = 13.sp)
    }
}
