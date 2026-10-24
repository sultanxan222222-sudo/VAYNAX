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
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import java.security.MessageDigest
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
    @Volatile private var sessionKey: SecretKey? = null
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

    @Volatile private var activeGroupOwner = false
    @Volatile private var connectionAttemptRunning = false

    private fun onP2pConnected(info: WifiP2pInfo) {
        if (!info.groupFormed || connectionAttemptRunning || connected) return
        connectionAttemptRunning = true
        activeGroupOwner = info.isGroupOwner
        statusUpdate?.invoke("Wi-Fi Direct соединён. Настраиваю защищённый канал…")
        if (info.isGroupOwner) {
            io.execute {
                try {
                    serverSocket?.close()
                    val server = ServerSocket()
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(CHAT_PORT), 8)
                    server.soTimeout = 45_000
                    serverSocket = server
                    val client = server.accept()
                    attachSocket(client)
                } catch (e: Exception) {
                    connectionAttemptRunning = false
                    runOnUiThread { statusUpdate?.invoke("Ожидание второго телефона завершилось: ${e.message ?: "ошибка"}") }
                }
            }
        } else {
            val host = info.groupOwnerAddress.hostAddress
            io.execute {
                var lastError: Exception? = null
                for (attempt in 1..8) {
                    if (connected) return@execute
                    try {
                        val client = Socket()
                        client.tcpNoDelay = true
                        client.connect(InetSocketAddress(host, CHAT_PORT), 4000)
                        attachSocket(client)
                        return@execute
                    } catch (e: Exception) {
                        lastError = e
                        try { Thread.sleep(900) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                    }
                }
                connectionAttemptRunning = false
                runOnUiThread { statusUpdate?.invoke("Не удалось открыть канал после повторных попыток: ${lastError?.message ?: "ошибка"}") }
            }
        }
    }

    private fun attachSocket(s: Socket) {
        socket?.close()
        socket = s
        io.execute {
            try {
                s.tcpNoDelay = true
                val out = PrintWriter(s.getOutputStream(), true)
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                // Ephemeral ECDH key agreement creates a fresh AES-GCM session key per connection.
                val generator = KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
                val pair = generator.generateKeyPair()
                out.println(JSONObject().put("type", "hello").put("pub", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)))
                val helloLine = input.readLine() ?: throw IllegalStateException("peer closed during handshake")
                val hello = JSONObject(helloLine)
                if (hello.optString("type") != "hello") throw IllegalStateException("invalid handshake")
                val peerBytes = Base64.decode(hello.getString("pub"), Base64.NO_WRAP)
                val peerPublic: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerBytes))
                val agreement = KeyAgreement.getInstance("ECDH")
                agreement.init(pair.private)
                agreement.doPhase(peerPublic, true)
                val raw = agreement.generateSecret()
                val keyBytes = MessageDigest.getInstance("SHA-256").digest(raw)
                sessionKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
                writer = out
                connected = true
                runOnUiThread { connectionUpdate?.invoke(true); statusUpdate?.invoke("Защищённый локальный канал установлен.") }
                while (!s.isClosed) {
                    val line = input.readLine() ?: break
                    val obj = JSONObject(line)
                    if (obj.optString("type") != "message") continue
                    val id = obj.optString("id")
                    if (id.isBlank() || !seen.add(id)) continue
                    val plain = decrypt(obj.optString("cipher"), obj.optString("iv"))
                    val sender = obj.optString("sender", "Собеседник")
                    val item = ChatLine(id, sender, plain, false)
                    saveMessage(item)
                    runOnUiThread { messageUpdate?.invoke(item) }
                }
            } catch (e: Exception) {
                runOnUiThread { statusUpdate?.invoke("Защищённый канал закрыт: ${e.message ?: "ошибка"}"); connectionUpdate?.invoke(false) }
            } finally {
                connected = false
                connectionAttemptRunning = false
                sessionKey = null
                writer = null
                try { s.close() } catch (_: Exception) {}
            }
        }
    }

    private fun encrypt(plain: String): Pair<String, String> {
        val key = sessionKey ?: throw IllegalStateException("secure session not ready")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to Base64.encodeToString(iv, Base64.NO_WRAP)
    }

    private fun decrypt(cipherText: String, ivText: String): String {
        val key = sessionKey ?: throw IllegalStateException("secure session not ready")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun sendEncryptedMessage(text: String) {
        if (!connected || writer == null) { statusUpdate?.invoke("Сначала подключись к устройству."); return }
        try {
            val (cipher, iv) = encrypt(text)
            val id = UUID.randomUUID().toString()
            val prefs = getSharedPreferences("vaynax_local_account", MODE_PRIVATE)
            val sender = prefs.getString("nickname", "VAYNAX") ?: "VAYNAX"
            val obj = JSONObject().put("type", "message").put("id", id).put("sender", sender).put("cipher", cipher).put("iv", iv)
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
    var isConnected by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<ChatLine>>(emptyList()) }
    var showGreeting by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1400)
        showGreeting = false
        try {
            val arr = org.json.JSONArray(historyPrefs.getString("items", "[]"))
            lines = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatLine(o.getString("id"), o.optString("sender"), o.optString("text"), o.optBoolean("mine"))
            }
        } catch (_: Exception) { lines = emptyList() }
    }

    Surface(Modifier.fillMaxSize(), color = DeepEmerald) {
        if (showGreeting) {
            Column(Modifier.fillMaxSize().background(DeepEmerald).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("✦", color = Gold, fontSize = 34.sp)
                Spacer(Modifier.height(14.dp))
                Text("Ассаляму алейкум", color = Snow, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text("VAYNAX", color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(0.62f).height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF168653), RoundedCornerShape(3.dp)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Snow, RoundedCornerShape(3.dp)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFB62835), RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.height(18.dp))
                Text("Связь с близкими", color = Gold, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                Text("━━━━━━━━  ✧  ━━━━━━━━", color = Gold.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        } else {
            Column(Modifier.fillMaxSize().background(DeepEmerald).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("VAYNAX", color = Gold, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text("Связь с близкими", color = Snow.copy(alpha = 0.82f), fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("ЧЕЧЕНСКИЙ СТИЛЬ", color = Gold, fontSize = 9.sp, letterSpacing = 1.sp)
                        Row(Modifier.width(72.dp).height(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF168653)))
                            Box(Modifier.weight(1f).fillMaxHeight().background(Snow))
                            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFB62835)))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Gold.copy(alpha = 0.55f)))
                Spacer(Modifier.height(12.dp))

                if (editing) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Emerald),
                        shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("ТВОЙ ПРОФИЛЬ", color = Gold, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Создать аккаунт", color = Snow, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("Имя будет храниться на этом телефоне.", color = Snow.copy(alpha = 0.75f), fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(value = nickname, onValueChange = { nickname = it.take(32) },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Твой псевдоним") }, singleLine = true,
                                shape = RoundedCornerShape(12.dp))
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                val name = nickname.trim()
                                if (name.isNotEmpty()) {
                                    if (userId.isBlank()) userId = UUID.randomUUID().toString()
                                    savedNickname = name
                                    prefs.edit().putString("nickname", name).putString("user_id", userId).apply()
                                    editing = false
                                    status = "Аккаунт сохранён на этом телефоне."
                                }
                            }, enabled = nickname.trim().isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepEmerald),
                                shape = RoundedCornerShape(12.dp)) { Text("Сохранить аккаунт", fontWeight = FontWeight.Bold) }
                        }
                    }
                } else {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Emerald),
                        shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp).background(DeepEmerald, RoundedCornerShape(23.dp)), contentAlignment = Alignment.Center) {
                                Text(savedNickname.trim().take(1).uppercase().ifBlank { "V" }, color = Gold, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(savedNickname, color = Snow, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text("ID: " + userId.take(8) + "…", color = Snow.copy(alpha = 0.68f), fontSize = 11.sp)
                            }
                            TextButton(onClick = { editing = true }) { Text("Изменить", color = Gold, fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        onDiscover({ peers = it }, { status = it }, { isConnected = it },
                            { item -> if (lines.none { existing -> existing.id == item.id }) lines = lines + item })
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepEmerald),
                        shape = RoundedCornerShape(12.dp)) {
                        Text("⌕   Найти устройства рядом", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    if (peers.isNotEmpty()) {
                        Text("УСТРОЙСТВА РЯДОМ", color = Gold, fontSize = 11.sp, letterSpacing = 1.5.sp,
                            modifier = Modifier.align(Alignment.Start).padding(top = 10.dp, bottom = 4.dp))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 112.dp)
                            .background(Emerald, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp)) {
                            items(peers, key = { it.deviceAddress }) { peer ->
                                TextButton(onClick = {
                                    onConnect(peer)
                                    status = "Подключаемся к " + peer.deviceName.ifBlank { "устройству" } + "…"
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(peer.deviceName.ifBlank { "Устройство без имени" }, color = Gold, fontWeight = FontWeight.SemiBold)
                                        Text(if (peer.status == WifiP2pDevice.AVAILABLE) "Доступно · нажми для подключения" else "Статус: " + peer.status,
                                            color = Snow.copy(alpha = 0.78f), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().background(if (isConnected) Color(0xFF124A37) else Color(0xFF263B35),
                        RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (isConnected) Color(0xFF70D99A) else Gold.copy(alpha = 0.7f), RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isConnected) "Защищённое соединение установлено" else "Ожидание Wi-Fi Direct", color = Snow, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Сообщения", color = Gold, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("· локальная история", color = Snow.copy(alpha = 0.62f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF052F28), RoundedCornerShape(14.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (lines.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("✧", color = Gold, fontSize = 28.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("Здесь начнётся ваш разговор", color = Snow, fontSize = 14.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(4.dp))
                                Text("Найди устройство рядом и подключись.", color = Snow.copy(alpha = 0.62f), fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                        items(lines, key = { it.id }) { line ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start) {
                                Column(Modifier.widthIn(max = 280.dp)
                                    .background(if (line.mine) Color(0xFF165541) else Emerald, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(if (line.mine) "Ты" else line.sender, color = Gold, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(2.dp))
                                    Text(line.text, color = Snow, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it.take(2000) }, modifier = Modifier.weight(1f),
                            label = { Text("Написать сообщение") }, maxLines = 3, shape = RoundedCornerShape(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            val msg = draft.trim()
                            if (msg.isNotEmpty()) { onSend(msg); draft = "" }
                        }, enabled = draft.trim().isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepEmerald),
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.height(54.dp)) { Text("➤", fontSize = 20.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(status, color = Gold.copy(alpha = 0.88f), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("━━━━━━━━  ✧  ━━━━━━━━", color = Gold.copy(alpha = 0.5f), fontSize = 10.sp)
            }
        }
    }
}
