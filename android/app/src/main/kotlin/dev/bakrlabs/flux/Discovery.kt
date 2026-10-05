package dev.bakrlabs.flux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException

const val DISCOVERY_PORT = 41500
private const val MAGIC = "FLUX1"

data class Device(val name: String, val host: String, val port: Int, val seen: Long)

suspend fun announce(name: String, port: Int) = withContext(Dispatchers.IO) {
    DatagramSocket().use { socket ->
        socket.setBroadcast(true)
        val payload = "$MAGIC\t$name\t$port".toByteArray()
        while (isActive) {
            broadcastAddresses().forEach { target ->
                runCatching { socket.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT)) }
            }
            delay(1000)
        }
    }
}

suspend fun listen(onFound: suspend (Device) -> Unit) = withContext(Dispatchers.IO) {
    DatagramSocket(null as SocketAddress?).use { socket ->
        socket.setReuseAddress(true)
        socket.bind(InetSocketAddress(DISCOVERY_PORT))
        socket.setSoTimeout(1000)
        val buffer = ByteArray(256)
        while (isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            }
            val parts = String(packet.data, 0, packet.length).split("\t")
            if (parts.size != 3 || parts[0] != MAGIC) continue
            val port = parts[2].toIntOrNull() ?: continue
            val host = packet.address.hostAddress ?: continue
            onFound(Device(parts[1], host, port, System.currentTimeMillis()))
        }
    }
}
