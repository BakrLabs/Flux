package dev.bakrlabs.flux

import java.net.Inet4Address
import java.net.NetworkInterface

private val skipped = listOf("lo", "rmnet", "ccmni", "pdp", "clat", "v4-", "tun", "dummy")

fun lanAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { net -> net.isUp && skipped.none { net.name.startsWith(it) } }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
}.getOrDefault(emptyList())
