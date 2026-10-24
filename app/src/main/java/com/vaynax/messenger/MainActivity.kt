package com.vaynax.messenger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

private val DeepEmerald = Color(0xFF032B24)
private val Emerald = Color(0xFF063F32)
private val Gold = Color(0xFFE6C477)
private val Snow = Color(0xFFF5F7F8)

class MainActivity : ComponentActivity() {
    private lateinit var wifiManager: WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var peerUpdate: ((List<WifiP2pDevice>) -> Unit)? = null
    private var statusUpdate: ((String) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) discoverPeers()
        else statusUpdate?.invoke("Разрешение на поиск устройств не выдано. Личное меню доступно без него.")
    }

    private val receiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val mgr = wifiManager
                        val ch = channel ?: return
                        try {
                            mgr.requestPeers(ch) { list: WifiP2pDeviceList ->
                                peerUpdate?.invoke(list.deviceList.toList())
                                statusUpdate?.invoke(if (list.deviceList.isEmpty())
                                    "Поблизости пока никого нет. Меню и аккаунт работают локально."
                                else "Найдено устройств: ${list.deviceList.size}. Нажми на устройство для информации.")
                            }
                        } catch (_: SecurityException) {
                            statusUpdate?.invoke("Нет разрешения на поиск устройств.")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        statusUpdate?.invoke(if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                            "Wi-Fi Direct доступен. Можно искать устройства."
                        else "Включи Wi-Fi, чтобы искать устройства. Меню доступно и офлайн.")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiManager.initialize(this, mainLooper, null)
        setContent {
            MaterialTheme {
                VaynaxApp(
                    onDiscover = { peers, status ->
                        peerUpdate = peers
                        statusUpdate = status
                        requestPermissionsAndDiscover()
                    }
                )
            }
        }
    }

    private fun requestPermissionsAndDiscover() {
        val required = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else discoverPeers()
    }

    private fun discoverPeers() {
        val ch = channel ?: run {
            statusUpdate?.invoke("Wi-Fi Direct не инициализирован.")
            return
        }
        try {
            wifiManager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    statusUpdate?.invoke("Поиск устройств Wi-Fi Direct запущен…")
                }
                override fun onFailure(reason: Int) {
                    statusUpdate?.invoke("Не удалось начать поиск (код $reason). Проверь Wi-Fi и попробуй снова.")
                }
            })
        } catch (_: SecurityException) {
            statusUpdate?.invoke("Нет разрешения на поиск устройств.")
        }
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
}

@Composable
private fun VaynaxApp(onDiscover: ((List<WifiP2pDevice>) -> Unit, (String) -> Unit) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("vaynax_local_account", Context.MODE_PRIVATE) }
    var nickname by remember { mutableStateOf(prefs.getString("nickname", "") ?: "") }
    var savedNickname by remember { mutableStateOf(nickname) }
    var userId by remember { mutableStateOf(prefs.getString("user_id", "") ?: "") }
    var editing by remember { mutableStateOf(userId.isBlank()) }
    var status by remember { mutableStateOf("Аккаунт и меню работают локально. Wi-Fi Direct нужен только для поиска устройств рядом.") }
    var peers by remember { mutableStateOf<List<WifiP2pDevice>>(emptyList()) }
    var selectedPeer by remember { mutableStateOf<WifiP2pDevice?>(null) }

    Surface(Modifier.fillMaxSize(), color = DeepEmerald) {
        Column(
            Modifier.fillMaxSize().background(DeepEmerald).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Text("VAYNAX", color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("Связь с близкими", color = Snow, fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            if (editing) {
                Text(if (userId.isBlank()) "Создать аккаунт" else "Изменить имя", color = Snow, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Твой псевдоним") },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val name = nickname.trim()
                        if (name.isNotEmpty()) {
                            if (userId.isBlank()) userId = UUID.randomUUID().toString()
                            savedNickname = name
                            prefs.edit().putString("nickname", name).putString("user_id", userId).apply()
                            editing = false
                            status = "Аккаунт сохранён на этом телефоне. Пароль и сервер не нужны."
                        } else status = "Введи псевдоним."
                    },
                    enabled = nickname.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepEmerald)
                ) { Text("Сохранить аккаунт", fontWeight = FontWeight.Bold) }
            } else {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Emerald), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Твой аккаунт", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(savedNickname, color = Snow, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text("ID: ${userId.take(8)}…", color = Snow, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Изменить псевдоним") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Главное меню", color = Snow, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onDiscover({ peers = it }, { status = it }) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = DeepEmerald)
                ) { Text("Найти устройства рядом (Wi-Fi Direct)", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(10.dp))
                Text("Устройства поблизости", color = Snow, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                if (peers.isEmpty()) {
                    Text("Пока никого не найдено. Ты всё равно можешь открывать меню и настройки.", color = Snow, fontSize = 13.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        items(peers, key = { it.deviceAddress }) { peer ->
                            TextButton(onClick = { selectedPeer = peer; status = "Устройство: ${peer.deviceName.ifBlank { "Без имени" }}. Соединение и обмен сообщениями ещё не включены в этой версии." }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(peer.deviceName.ifBlank { "Устройство без имени" }, color = Gold)
                                    Text(if (peer.status == WifiP2pDevice.AVAILABLE) "Доступно" else "Статус: ${peer.status}", color = Snow, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                selectedPeer?.let { Text("Выбрано: ${it.deviceName}", color = Gold, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(14.dp))
            Text(status, color = Gold, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("История сообщений не отправляется в облако. В этой сборке доступно локальное меню и обнаружение устройств; подключение и переписка ещё не реализованы.", color = Snow, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}
