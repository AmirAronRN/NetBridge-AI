package com.netbridge.ai.service

import com.netbridge.ai.state.NetBridgeState
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
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

        scope.launch(Dispatchers.IO) { speedTracker() }

        scope.launch(Dispatchers.IO) {
            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    NetBridgeState.connectedClients.value++
                    launch(Dispatchers.IO) { handleSocks5Client(clientSocket) }
                } catch (e: Exception) { break }
            }
        }
    }

    private suspend fun handleSocks5Client(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            var targetSocket: Socket? = null
            try {
                val input = DataInputStream(clientSocket.getInputStream())
                val output = DataOutputStream(clientSocket.getOutputStream())

                // 1. SOCKS5 Handshake
                val version = input.readByte().toInt()
                if (version != 5) return@withContext
                val numMethods = input.readByte().toInt()
                val methods = ByteArray(numMethods)
                input.readFully(methods)

                // Reply: No Authentication required
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // 2. Connection Request
                if (input.readByte().toInt() != 5) return@withContext
                val command = input.readByte().toInt()
                if (command != 1) return@withContext // Only CONNECT command is supported
                input.readByte() // Reserved
                val addressType = input.readByte().toInt()

                var targetAddress = ""
                when (addressType) {
                    1 -> { // IPv4
                        val ipBytes = ByteArray(4)
                        input.readFully(ipBytes)
                        targetAddress = java.net.InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                    }
                    3 -> { // Domain Name
                        val length = input.readByte().toInt()
                        val domainBytes = ByteArray(length)
                        input.readFully(domainBytes)
                        targetAddress = String(domainBytes)
                    }
                    else -> return@withContext // IPv6 is skipped for simplicity
                }

                val targetPort = input.readUnsignedShort()

                // 3. Connect to Target
                try {
                    targetSocket = Socket(targetAddress, targetPort)
                    // Success Reply
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                    output.flush()
                } catch (e: Exception) {
                    // Failure Reply
                    output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                    output.flush()
                    return@withContext
                }

                // 4. Relay Traffic
                val job1 = launch { relayStream(clientSocket.getInputStream(), targetSocket.getOutputStream(), txBytes) }
                val job2 = launch { relayStream(targetSocket.getInputStream(), clientSocket.getOutputStream(), rxBytes) }

                joinAll(job1, job2)
            } catch (e: Exception) {
                // Connection closed
            } finally {
                try { clientSocket.close() } catch (e: Exception) {}
                try { targetSocket?.close() } catch (e: Exception) {}
                NetBridgeState.connectedClients.value--
            }
        }
    }

    private suspend fun relayStream(input: InputStream, output: OutputStream, counter: AtomicLong) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(16384) // 16KB buffer for max speed
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

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
