package com.netbridge.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.netbridge.ai.service.NetBridgeService
import com.netbridge.ai.state.NetBridgeState

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NetBridgeMainScreen(
                        onToggleService = { start ->
                            val intent = Intent(this, NetBridgeService::class.java)
                            if (start) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                                else startService(intent)
                            } else {
                                intent.action = "STOP"
                                startService(intent)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun NetBridgeMainScreen(onToggleService: (Boolean) -> Unit) {
    val isRunning by NetBridgeState.isRunning.collectAsState()
    val activeInterfaces by NetBridgeState.activeInterfaces.collectAsState()
    val clients by NetBridgeState.connectedClients.collectAsState()
    val downSpeed by NetBridgeState.downloadSpeedKbps.collectAsState()
    val upSpeed by NetBridgeState.uploadSpeedKbps.collectAsState()
    val port by NetBridgeState.proxyPort.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(text = "NetBridge AI", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(text = "VPN Proxy Agent", fontSize = 16.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(48.dp))

        // Main Toggle Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(if (isRunning) "Sharing Active" else "Ready to Share", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Port: $port", fontSize = 14.sp, color = Color.Gray)
                }
                Switch(
                    checked = isRunning,
                    onCheckedChange = { onToggleService(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isRunning) {
            // Live Stats View
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBox("Clients", "$clients")
                StatBox("Download", "${downSpeed} kb/s")
                StatBox("Upload", "${upSpeed} kb/s")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // IP Information & QR Code
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Auto-Discovery Active via mDNS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val primaryIp = activeInterfaces.firstOrNull()?.split(":")?.get(1)?.trim() ?: "127.0.0.1"
                    val qrBitmap = generateQrCode("http://$primaryIp:$port")
                    
                    qrBitmap?.let {
                        Image(bitmap = it.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(150.dp))
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Manual Connection Addresses:", fontWeight = FontWeight.Bold)
                    activeInterfaces.forEach {
                        Text(it, fontSize = 14.sp, color = Color.LightGray)
                    }
                }
            }
        }
    }
}

@Composable
fun StatBox(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(title, fontSize = 12.sp, color = Color.Gray)
    }
}

// Lightweight utility to generate a QR Code using ZXing
fun generateQrCode(text: String): Bitmap? {
    return try {
        val size = 512
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) { null }
}
