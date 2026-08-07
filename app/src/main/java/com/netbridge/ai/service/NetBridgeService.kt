package com.netbridge.ai.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.netbridge.ai.MainActivity
import com.netbridge.ai.state.NetBridgeState
import kotlinx.coroutines.*
import java.net.NetworkInterface

class NetBridgeService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private var proxyEngine: ProxyEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsdManager: NsdManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundService()
        acquireWakeLock()
        
        val port = NetBridgeState.proxyPort.value
        updateNetworkInterfaces()
        
        // 1. Start Proxy
        proxyEngine = ProxyEngine(port)
        proxyEngine?.start(scope)
        NetBridgeState.isRunning.value = true

        // 2. Start mDNS Auto-Discovery Broadcast
        registerMDns(port)

        // 3. Start Telemetry Ticker for UI & Notification
        scope.launch {
            while (isActive) {
                updateNotification()
                delay(1000)
            }
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "netbridge_proxy_channel"
        val channel = NotificationChannel(channelId, "Proxy Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, NetBridgeService::class.java).apply { action = "STOP" }, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("NetBridge Proxy Active")
            .setContentText("Routing via VPN...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()

        startForeground(1, notification)
    }

    private fun updateNotification() {
        // Notification updates logic here. Throttled to prevent OS spam.
    }

    private fun registerMDns(port: Int) {
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "NetBridge_AI_${android.os.Build.MODEL}"
            serviceType = "_netbridge._tcp"
            setPort(port)
        }
        
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        })
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetBridge::ProxyWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
    }

    private fun updateNetworkInterfaces() {
        val ips = mutableListOf<String>()
        NetworkInterface.getNetworkInterfaces().iterator().forEach { intf ->
            // Filter out loopback, and pick active interfaces (Hotspot, USB Tethering, Wi-Fi)
            if (!intf.isLoopback && intf.isUp && (intf.name.contains("wlan") || intf.name.contains("rndis") || intf.name.contains("swlan"))) {
                intf.inetAddresses.iterator().forEach { addr ->
                    if (!addr.isLoopbackAddress && addr.address.size == 4) { // IPv4
                        ips.add("${intf.name}: ${addr.hostAddress}")
                    }
                }
            }
        }
        NetBridgeState.activeInterfaces.value = ips
    }

    override fun onDestroy() {
        job.cancel()
        proxyEngine?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        nsdManager?.unregisterService(null) // omitted listener implementation for brevity
        NetBridgeState.isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
