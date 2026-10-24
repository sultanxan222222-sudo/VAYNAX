package com.vaynax.messenger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

private val DeepEmerald = Color(0xFF032B24)
private val Emerald = Color(0xFF063F32)
private val Gold = Color(0xFFE6C477)
private val Snow = Color(0xFFF5F7F8)
private const val CHAT_PORT = 47821

private data class ChatLine(val id: String, val sender: String, val text: String, val mine: Boolean)

class MainActivity : ComponentActivity() {
    private lateinit var wifiManager: WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var peerUpdate: ((List<WifiP2pDevice>) -> Unit)? = null
    private var statusUpdate: ((String) -> Unit)? = null
    private var connectionUpdate: ((Boolean) -> Unit)? = null
    private var messageUpdate: ((ChatLine) -> Unit)? = null
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private val io = Executors.newCachedThreadPool()
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var connected = false
    private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) discoverPeers()
        else statusUpdate?.invoke("Разрешение на поиск устройств не выдано.")
    }

    private val receiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        try { wifiManager.requestPeers(channel) { list: WifiP2pDeviceList ->
                            peerUpdate?.invoke(list.deviceList.toList())
                            statusUpdate?.invoke(if (list.deviceList.isEmpty()) "Устройств рядом не найдено." else "Найдено устройств: ${list.deviceList.size}")
                        } } catch (_: Exception) { statusUpdate?.invoke("Не удалось обновить список устройств.") }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        statusUpdate?.invoke(if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "Wi-Fi Direct включён." else "Включи Wi-Fi.")
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (info?.isConnected == true) {
                            try { wifiManager.requestConnectionInfo(channel) { p2p: WifiP2pInfo -> onP2pConnected(p2p) } }
                            catch (_: Exception) { statusUpdate?.invoke("Ошибка получения параметров соединения.") }
                        } else {
                            connected = false
                            connectionUpdate?.invoke(false)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiManager.initialize(this, mainLooper, null)
        setContent { MaterialTheme { VaynaxApp(
            onDiscover = { peers, status, conn, msg -> peerUpdate = peers; statusUpdate = status; connectionUpdate = conn; messageUpdate = msg; requestPermissionsAndDiscover() },
            onConnect = { device -> connectTo(device) },
            onSend = { text -> sendEncryptedMessage(text) }
        ) } }
    }

    private fun requestPermissionsAndDiscover() {
        val required = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else discoverPeers()
    }

    private fun discoverPeers() {
        val ch = channel ?: return
        try { wifiManager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { statusUpdate?.invoke("Поиск устройств рядом…") }
            override fun onFailure(reason: Int) { statusUpdate?.invoke("Поиск не запущен (код $reason).") }
        }) } catch (_: SecurityException) { statusUpdate?.invoke("Нет разрешения на поиск.") }
    }

    private fun connectTo(device: WifiP2pDevice) {
        val ch = channel ?: return
        try {
            val config = android.net.wifi.p2p.WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
            wifiManager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { statusUpdate?.invoke("Запрос соединения отправлен: ${device.deviceName}") }
                override fun onFailure(reason: Int) { statusUpdate?.invoke("Соединение не удалось (код $reason).") }
            })
        } catch (_: SecurityException) { statusUpdate?.invoke("Нет разрешения на соединение.") }
    }

    private fun onP2pConnected(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        statusUpdate?.invoke("Wi-Fi Direct соединён. Запуск защищённого канала…")
        if (info.isGroupOwner) {
            io.execute {
                try {
                    serverSocket?.close()
                    serverSocket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(CHAT_PORT), 8) }
                    val client = serverSocket!!.accept()
                    attachSocket(client)
                } catch (e: Exception) { runOnUiThread { statusUpdate?.invoke("Не удалось открыть локальный канал: ${e.message ?: "ошибка"}") } }
            }
        } else {
            io.execute {
                try {
                    val client = Socket()
                    client.connect(InetSocketAddress(info.groupOwnerAddress.hostAddress, CHAT_PORT), 12000)
                    attachSocket(client)
                } catch (e: Exception) { runOnUiThread { statusUpdate?.invoke("Не удалось подключить канал: ${e.message ?: "ошибка"}") } }
            }
        }
    }

    private fun attachSocket(s: Socket) {
        socket?.close()
        socket = s
        val out = PrintWriter(s.getOutputStream(), true)
        writer = out
        connected = true
        runOnUiThread { connectionUpdate?.invoke(true); statusUpdate?.invoke("Локальный канал установлен.") }
        io.execute {
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (!s.isClosed) {
                    val line = input.readLine() ?: break
                    val obj = JSONObject(line)
                    val id = obj.optString("id")
                    if (id.isBlank() || !seen.add(id)) continue
                    val plain = decrypt(obj.optString("cipher"), obj.optString("iv"))
                    val sender = obj.optString("sender", "Собеседник")
                    val item = ChatLine(id, sender, plain, false)
                    saveMessage(item)
                    runOnUiThread { messageUpdate?.invoke(item) }
                }
            } catch (_: Exception) {
                runOnUiThread { statusUpdate?.invoke("Канал закрыт. Попробуй соединиться снова."); connectionUpdate?.invoke(false) }
            } finally { connected = false }
        }
    }

    /*
     * Each message uses AES-GCM with a per-install secret. It protects local message
     * payloads and accidental clear-text forwarding, but peers must share the same key
     * for decryption. The next identity/QR layer must transfer this key securely.
     */
    private fun localSecret(): SecretKey {
        val prefs = getSharedPreferences("vaynax_crypto", MODE_PRIVATE)
        val encoded = prefs.getString("aes_key", null)
        if (encoded != null) return javax.crypto.spec.SecretKeySpec(Base64.decode(encoded, Base64.NO_WRAP), "AES")
        val gen = KeyGenerator.getInstance("AES").apply { init(256) }
        val key = gen.generateKey()
        prefs.edit().putString("aes_key", Base64.encodeToString(key.encoded, Base64.NO_WRAP)).apply()
        return key
    }

    private fun encrypt(plain: String): Pair<String, String> {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, localSecret(), GCMParameterSpec(128, iv))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to Base64.encodeToString(iv, Base64.NO_WRAP)
    }

    private fun decrypt(cipherText: String, ivText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, localSecret(), GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun sendEncryptedMessage(text: String) {
        if (!connected || writer == null) { statusUpdate?.invoke("Сначала подключись к устройству."); return }
        try {
            val (cipher, iv) = encrypt(text)
            val id = UUID.randomUUID().toString()
            val prefs = getSharedPreferences("vaynax_local_account", MODE_PRIVATE)
            val sender = prefs.getString("nickname", "VAYNAX") ?: "VAYNAX"
            val obj = JSONObject().put("id", id).put("sender", sender).put("cipher", cipher).put("iv", iv)
            writer?.println(obj.toString())
            if (writer?.checkError() == true) throw IllegalStateException("send failed")
            val item = ChatLine(id, sender, text, true)
            seen.add(id); saveMessage(item)
            runOnUiThread { messageUpdate?.invoke(item); statusUpdate?.invoke("Сообщение отправлено по локальному каналу.") }
        } catch (e: Exception) { statusUpdate?.invoke("Отправка не удалась: ${e.message ?: "ошибка"}") }
    }

    private fun saveMessage(item: ChatLine) {
        val prefs = getSharedPreferences("vaynax_history", MODE_PRIVATE)
        val old = org.json.JSONArray(prefs.getString("items", "[]"))
        old.put(JSONObject().put("id", item.id).put("sender", item.sender).put("text", item.text).put("mine", item.mine))
        while (old.length() > 500) old.remove(0)
        prefs.edit().putString("items", old.toString()).apply()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") run { registerReceiver(receiver, filter) }
    }

    override fun onPause() {
        try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        super.onPause()
    }

    override fun onDestroy() {
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        io.shutdownNow()
        super.onDestroy()
    }
}

@Composable
private fun VaynaxApp(
    onDiscover: ((List<WifiP2pDevice>) -> Unit, (String) -> Unit, (Boolean) -> Unit, (ChatLine) -> Unit) -> Unit,
    onConnect: (WifiP2pDevice) -> Unit,
    onSend: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("vaynax_local_account", Context.MODE_PRIVATE) }
    val historyPrefs = remember { context.getSharedPreferences("vaynax_history", Context.MODE_PRIVATE) }
    var nickname by remember { mutableStateOf(prefs.getString("nickname", "") ?: "") }
    var savedNickname by remember { mutableStateOf(nickname) }
    var userId by remember { mutableStateOf(prefs.getString("user_id", "") ?: "") }
    var editing by remember { mutableStateOf(userId.isBlank()) }
    var status by remember { mutableStateOf("Создай аккаунт, затем найди устройство рядом.") }
    var peers by remember { mutableStateOf<List<WifiP2pDevice>>(emptyList()) }
    var selectedPeer by remember { mutableStateOf<WifiP2pDevice?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<ChatLine>>(emptyList()) }
    LaunchedEffect(Unit) {
        try {
            val arr = org.json.JSONArray(historyPrefs.getString("items", "[]"))
            lines = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatLine(o.getString("id"), o.optString("sender"), o.optString("text"), o.optBoolean("mine"))
            }
        } catch (_: Exception) { lines = emptyList() }
    }

    Surface(Modifier.fillMaxSize(), color = DeepEmerald) {
        Column(Modifier.fillMaxSize().background(DeepEmerald).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("VAYNAX", color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("Связь с близкими", color = Snow, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            if (editing) {
                Text("Создать аккаунт", color = Snow, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = nickname, onValueChange = { nickname = it.take(32) }, modifier = Modifier.fillMaxWidth(), label = { Text("Твой псевдоним") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    val name = nickname.trim()
                    if (name.isNotEmpty()) {
                        if (userId.isBlank()) userId = UUID.randomUUID().toString()
                        savedNickname = name
                        prefs.edit().putString("nickname", name).putString("user_id", userId).apply()
                        editing = false
                        status = "Аккаунт сохранён на этом телефоне."
                    }
                }, enabled = nickname.trim().isNotEmpty(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepEmerald)) { Text("Сохранить аккаунт", fontWeight = FontWeight.Bold) }
            } else {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Emerald), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Твой аккаунт", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(savedNickname, color = Snow, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("ID: ${userId.take(8)}…", color = Snow, fontSize = 12.sp)
                        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Изменить псевдоним") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onDiscover({ peers = it }, { status = it }, { isConnected = it }, { item -> lines = lines + item }) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepEmerald)) { Text("Найти устройства рядом", fontWeight = FontWeight.Bold) }
                LazyColumn(Modifier.weight(0.8f).fillMaxWidth()) {
                    items(peers, key = { it.deviceAddress }) { peer ->
                        TextButton(onClick = { selectedPeer = peer; onConnect(peer); status = "Подключаемся к ${peer.deviceName.ifBlank { "устройству" }}…" }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(peer.deviceName.ifBlank { "Устройство без имени" }, color = Gold)
                                Text(if (peer.status == WifiP2pDevice.AVAILABLE) "Доступно — нажми для подключения" else "Статус: ${peer.status}", color = Snow, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isConnected) "Соединение установлено" else "Нет соединения", color = if (isConnected) Gold else Snow, fontSize = 12.sp)
                }
                Text("Сообщения (локальная история)", color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Emerald, RoundedCornerShape(10.dp)).padding(8.dp)) {
                    items(lines, key = { it.id }) { line ->
                        Text("${if (line.mine) "Я" else line.sender}: ${line.text}", color = if (line.mine) Gold else Snow, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it.take(2000) }, modifier = Modifier.weight(1f), label = { Text("Сообщение") }, maxLines = 3)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { val msg = draft.trim(); if (msg.isNotEmpty()) { onSend(msg); draft = "" } }, enabled = draft.trim().isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepEmerald)) { Text("➤") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(status, color = Gold, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
