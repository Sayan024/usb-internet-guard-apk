package com.usbguard.firewall

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class FirewallPhase { OFF, STARTING, ACTIVE, ERROR, REVOKED }

data class FirewallStatus(
    val phase: FirewallPhase,
    val message: String? = null,
)

data class PacketCounters(
    val total: Long,
    val ipv4: Long,
    val ipv6: Long,
    val bytes: Long,
)

/** In-memory status shared between the VPN service and the dashboard (same process). */
object FirewallState {
    private val _status = MutableStateFlow(FirewallStatus(FirewallPhase.OFF))
    val status: StateFlow<FirewallStatus> = _status.asStateFlow()

    private val total = AtomicLong()
    private val ipv4 = AtomicLong()
    private val ipv6 = AtomicLong()
    private val bytes = AtomicLong()

    fun set(phase: FirewallPhase, message: String? = null) {
        _status.value = FirewallStatus(phase, message)
    }

    fun resetCounters() {
        total.set(0); ipv4.set(0); ipv6.set(0); bytes.set(0)
    }

    /** Called for every packet dropped. [firstByte] holds the IP version in its top 4 bits. */
    fun recordDrop(firstByte: Int, length: Int) {
        total.incrementAndGet()
        bytes.addAndGet(length.toLong())
        when ((firstByte ushr 4) and 0x0F) {
            4 -> ipv4.incrementAndGet()
            6 -> ipv6.incrementAndGet()
        }
    }

    fun counters() = PacketCounters(total.get(), ipv4.get(), ipv6.get(), bytes.get())
}
