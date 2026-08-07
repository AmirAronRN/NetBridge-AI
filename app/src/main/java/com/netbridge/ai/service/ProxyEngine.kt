package com.netbridge.ai.service

import com.netbridge.ai.state.NetBridgeState
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class ProxyEngine(private val port: Int) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    private val rxBytes = AtomicLong(0)
    private val txBytes = AtomicLong(0)

    fun start(scope: CoroutineScope) {
        isRunning = true
        serverSocket = ServerSocket(port)
        
        scope.launch(Dispatchers.IO) {
            speedTracker()
        }

        scope.launch(Dispatchers.IO) {
            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    NetBridgeState.connectedClients.value++
                    launch(Dispatchers.IO) { handleClient(clientSocket) }
                } catch (e: Exception) { break }
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = clientSocket.getInputStream()
                val output = clientSocket.getOutputStream()
                
                // Read HTTP Request line
                val requestLine = readLine(input) ?: return@withContext
                val parts = requestLine.split(" ")
                if (parts.size < 3) return@withContext
                
                val method = parts[0]
                val url = parts[1]

                val hostPort = extractHostPort(url)
                val targetSocket = Socket(hostPort.first, hostPort.second)

                if (method.uppercase() == "CONNECT") {
                    // HTTPS Tunneling Handshake
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    output.flush()
                } else {
                    // Forward standard HTTP Request
                    val targetOut = targetSocket.getOutputStream()
                    targetOut.write("$requestLine\r\n".toByteArray())
                    // Normally you'd forward headers here, highly simplified for this demo
                }

                // Setup Bidirectional Relay via Coroutines
                val job1 = launch { relayStream(clientSocket.getInputStream(), targetSocket.getOutputStream(), txBytes) }
                val job2 = launch { relayStream(targetSocket.getInputStream(), clientSocket.getOutputStream(), rxBytes) }
                
                joinAll(job1, job2)
            } catch (e: Exception) {
                // Connection closed or error
            } finally {
                clientSocket.close()
                NetBridgeState.connectedClients.value--
            }
        }
    }

    private suspend fun relayStream(input: InputStream, output: OutputStream, counter: AtomicLong) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            var read: Int
            try {
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    output.flush()
                    counter.addAndGet(read.toLong())
                }
            } catch (e: Exception) { /* Suppress disconnect errors */ }
        }
    }

    private suspend fun speedTracker() {
        var lastRx = 0L
        var lastTx = 0L
        while (isRunning) {
            delay(1000)
            val currentRx = rxBytes.get()
            val currentTx = txBytes.get()
            
            NetBridgeState.downloadSpeedKbps.value = (currentRx - lastRx) / 1024
            NetBridgeState.uploadSpeedKbps.value = (currentTx - lastTx) / 1024
            
            lastRx = currentRx
            lastTx = currentTx
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun extractHostPort(url: String): Pair<String, Int> {
        val cleanUrl = url.replace("http://", "").replace("https://", "")
        val hostParts = cleanUrl.split("/")[0].split(":")
        val host = hostParts[0]
        val port = if (hostParts.size > 1) hostParts[1].toInt() else 80
        return Pair(host, port)
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
    }
}
