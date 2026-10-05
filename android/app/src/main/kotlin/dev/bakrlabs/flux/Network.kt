package dev.bakrlabs.flux

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface

private val skipped = listOf("lo", "rmnet", "ccmni", "pdp", "clat", "v4-", "tun", "dummy")

private fun lanInterfaceAddresses(): List<InterfaceAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { net -> net.isUp && skipped.none { net.name.startsWith(it) } }
        .flatMap { it.interfaceAddresses }
        .filter { it.address is Inet4Address && !it.address.isLinkLocalAddress && it.broadcast != null }
}.getOrDefault(emptyList())

fun lanAddresses(): List<String> = lanInterfaceAddresses().mapNotNull { it.address.hostAddress }

fun broadcastAddresses(): List<InetAddress> = lanInterfaceAddresses().mapNotNull { it.broadcast }
