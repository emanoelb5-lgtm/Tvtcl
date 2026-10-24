package com.b5solucoes.tcllight

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address

internal data class NetworkStatus(val connection: String, val internet: String,
    val ipv4: List<String> = emptyList(), val ipv6: List<String> = emptyList(), val connected: Boolean = false) {
    val address: String get() = (ipv4 + ipv6).firstOrNull() ?: "Ainda não informado"
    companion object {
        fun internetLabel(connected: Boolean, validated: Boolean, portal: Boolean): String = when {
            !connected -> "Sem conexão de rede"
            validated -> "Internet disponível"
            portal -> "A rede exige uma página de acesso"
            else -> "Internet não confirmada pelo Android"
        }
        fun read(manager: ConnectivityManager): NetworkStatus = try {
            val network = manager.activeNetwork
            val caps = manager.getNetworkCapabilities(network)
            val connected = network != null && caps != null
            val transport = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Cabo"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                connected -> "Rede"
                else -> "Offline"
            }
            val addresses = manager.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            NetworkStatus(transport, internetLabel(connected,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true),
                addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct(),
                addresses.filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress?.substringBefore('%') }.distinct(), connected)
        } catch (_: Exception) { NetworkStatus("Rede", "Informações de rede indisponíveis") }
    }
}
