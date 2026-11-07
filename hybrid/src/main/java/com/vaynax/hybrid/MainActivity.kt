package com.vaynax.hybrid

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

private val Emerald = Color(0xFF063F35)
private val EmeraldBright = Color(0xFF0E8065)
private val Paper = Color(0xFFF4F5F2)
private val Gold = Color(0xFFD8B45A)
private val Muted = Color(0xFF69756F)
private val Red = Color(0xFFB8323B)

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var backend: GoBackend
    private var loadedConfig: Config? = null
    private var connected = false
    private var stateCallback: ((Boolean, String) -> Unit)? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = "VAYNAX-HYBRID"
        override fun onStateChange(newState: Tunnel.State) {
            connected = newState == Tunnel.State.UP
            runOnUiThread { stateCallback?.invoke(connected, if (connected) "Туннель активен" else "Отключено") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(applicationContext)
        setContent {
            var configName by remember { mutableStateOf("Конфигурация не выбрана") }
            var status by remember { mutableStateOf("Готов к настройке") }
            var isConnected by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }

            stateCallback = { active, message ->
                isConnected = active
                status = message
                busy = false
            }

            val configPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            loadedConfig = Config.parse(stream)
                        } ?: error("Не удалось открыть файл")
                        configName = uri.lastPathSegment?.substringAfterLast('/') ?: "WireGuard.conf"
                        status = "Конфигурация проверена"
                    } catch (e: Exception) {
                        loadedConfig = null
                        configName = "Конфигурация не выбрана"
                        status = "Ошибка конфигурации: ${e.message ?: "неверный файл"}"
                    }
                }
            }

            val vpnPermission = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) startTunnel(
                    onStatus = { message, active, loading ->
                        status = message
                        isConnected = active
                        busy = loading
                    }
                ) else {
                    status = "Разрешение Android VPN не выдано"
                    busy = false
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(46.dp).background(Emerald, RoundedCornerShape(15.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("V", color = Gold, fontSize = 25.sp, fontWeight = FontWeight.Black)
                            }
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text("VAYNAX", color = Emerald, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                                Text("HYBRID  •  NETWORK CONTINUITY", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().height(190.dp)
                                .background(Emerald, RoundedCornerShape(28.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Box(Modifier.size(76.dp).background(if (isConnected) EmeraldBright else Color(0xFF174F45), CircleShape), contentAlignment = Alignment.Center) {
                                    Text(if (isConnected) "✓" else "⌁", color = Gold, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(if (isConnected) "ЗАЩИЩЁННЫЙ КАНАЛ" else "СЕТЬ ПОД КОНТРОЛЕМ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
                                Text(status, color = Color(0xFFD1DDD8), fontSize = 12.sp)
                            }
                        }

                        Text("ПОДКЛЮЧЕНИЕ", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Column(
                            modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("WireGuard профиль", color = Emerald, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(configName, color = Muted, fontSize = 12.sp)
                            Button(
                                onClick = { configPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Emerald)
                            ) { Text("Выбрать .conf файл") }
                        }

                        Button(
                            onClick = {
                                if (isConnected) {
                                    busy = true
                                    stopTunnel { message ->
                                        status = message
                                        isConnected = false
                                        busy = false
                                    }
                                } else {
                                    val config = loadedConfig
                                    if (config == null) {
                                        status = "Сначала выбери WireGuard .conf"
                                    } else {
                                        busy = true
                                        val intent = VpnService.prepare(this@MainActivity)
                                        if (intent == null) startTunnel { message, active, loading ->
                                            status = message; isConnected = active; busy = loading
                                        } else vpnPermission.launch(intent)
                                    }
                                }
                            },
                            enabled = !busy && (loadedConfig != null || isConnected),
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isConnected) Red else EmeraldBright)
                        ) {
                            Text(if (busy) "ПОДОЖДИ…" else if (isConnected) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }

                        Spacer(Modifier.weight(1f))
                        Text(
                            "Первый сетевой модуль: настоящий WireGuard-туннель. Для подключения нужен действующий серверный профиль. Публичные узлы и интеллектуальный выбор маршрута добавляются отдельным этапом.",
                            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(EmeraldBright, CircleShape))
                            Text("ANDROID 13–16  •  REALME NOTE 50", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                        }
                    }
                }
            }
        }
    }

    private fun startTunnel(onStatus: (String, Boolean, Boolean) -> Unit) {
        val config = loadedConfig ?: run { onStatus("Сначала выбери конфигурацию", false, false); return }
        onStatus("Запуск защищённого туннеля…", false, true)
        worker.execute {
            try {
                backend.setState(tunnel, Tunnel.State.UP, config)
                runOnUiThread { onStatus("Туннель активен", true, false) }
            } catch (e: Exception) {
                runOnUiThread { onStatus("Не удалось подключиться: ${e.message ?: e.javaClass.simpleName}", false, false) }
            }
        }
    }

    private fun stopTunnel(onStatus: (String) -> Unit) {
        worker.execute {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                runOnUiThread { onStatus("Отключено") }
            } catch (e: Exception) {
                runOnUiThread { onStatus("Ошибка отключения: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    override fun onDestroy() {
        stateCallback = null
        worker.shutdown()
        super.onDestroy()
    }
}
