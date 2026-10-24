package com.vaynax.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { VaynaxApp() } }
    }
}

@Composable
private fun VaynaxApp() {
    var showChats by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = DeepEmerald) {
        if (!showChats) {
            WelcomeScreen(onContinue = { showChats = true })
        } else {
            ChatHome(onBack = { showChats = false })
        }
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
private fun ChatHome(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(DeepEmerald).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("VAYNAX", color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Чаты", color = Snow, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Основа интерфейса готова. Личные и групповые сообщения, приглашения по QR-коду, защищённая синхронизация и интернет-звонки будут подключаться следующими этапами.",
            color = Snow, fontSize = 16.sp
        )
        FeatureRow("Сообщения", "Будет подключено")
        FeatureRow("Аудиозвонки через интернет", "Запланировано")
        FeatureRow("Видеозвонки через интернет", "Запланировано")
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack) { Text("Назад к приветствию") }
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
