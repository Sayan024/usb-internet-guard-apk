package com.usbguard.firewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * A "sinkhole" VPN: it captures all IPv4 and IPv6 traffic from apps on this phone and
 * drops every packet. Nothing is forwarded anywhere, so no server is involved.
 *
 * USB tethering traffic from the PC is forwarded by Android's tethering stack, which on
 * stock Android does not pass through app VPNs. That is the behaviour this app relies on,
 * and it must be confirmed on the actual phone (acceptance test 3).
 */
class FirewallVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.usbguard.firewall.action.START"
        const val ACTION_STOP = "com.usbguard.firewall.action.STOP"

        private const val TAG = "UsbGuardVpn"
        private const val CHANNEL_ID = "firewall_status"
        private const val NOTIFICATION_ID = 1

        // Private addresses for the TUN interface. Nothing ever answers on them.
        private const val TUN_V4 = "10.111.222.1"
        private const val DNS_V4 = "10.111.222.2"
        private const val TUN_V6 = "fd00:1:fd00:1::1"
        private const val DNS_V6 = "fd00:1:fd00:1::2"

        fun start(context: Context) {
            val intent = Intent(context, FirewallVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FirewallVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(FirewallPhase.OFF, null)
            return START_NOT_STICKY
        }
        // ACTION_START, a system restart (null intent) or an always-on VPN start.
        startFirewall()
        return START_STICKY
    }

    private fun startFirewall() {
        // Must happen quickly after startForegroundService(), even if we fail below.
        goForeground()
        if (running) return

        FirewallState.set(FirewallPhase.STARTING)
        val pfd = try {
            buildInterface()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            null
        }
        if (pfd == null) {
            shutdown(
                FirewallPhase.ERROR,
                "Android refused to start the VPN. Open the app and enable USB Only Mode again to re-grant VPN permission.",
            )
            return
        }

        tun = pfd
        running = true
        FirewallState.resetCounters()
        worker = Thread({ drainLoop(pfd) }, "usbguard-drain").also { it.start() }
        FirewallState.set(FirewallPhase.ACTIVE)
        Log.i(TAG, "Firewall active")
    }

    private fun buildInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(TUN_V4, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(DNS_V4)
            .setBlocking(false)
            .setConfigureIntent(openAppIntent())

        try {
            builder.addAddress(TUN_V6, 128)
                .addRoute("::", 0)
                .addDnsServer(DNS_V6)
        } catch (e: IllegalArgumentException) {
            // Android blocks a family the VPN doesn't configure, so IPv6 stays blocked anyway.
            Log.w(TAG, "IPv6 setup failed", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        // Returns null if the user has not granted (or has revoked) VPN permission.
        return builder.establish()
    }

    /** Reads packets from the TUN interface and throws them away, counting as it goes. */
    private fun drainLoop(pfd: ParcelFileDescriptor) {
        val tunFd = pfd.fileDescriptor
        val buffer = ByteArray(32767)
        val pollFd = StructPollfd()
        pollFd.fd = tunFd
        pollFd.events = OsConstants.POLLIN.toShort()
        val stopMask = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL

        while (running) {
            try {
                pollFd.revents = 0
                // Short timeout so shutdown() is noticed quickly.
                if (Os.poll(arrayOf(pollFd), 500) <= 0) continue
                if (pollFd.revents.toInt() and stopMask != 0) break
                while (running) {
                    val n = Os.read(tunFd, buffer, 0, buffer.size)
                    if (n <= 0) break
                    FirewallState.recordDrop(buffer[0].toInt() and 0xFF, n)
                }
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                Log.w(TAG, "TUN read error", e)
                break
            } catch (e: Exception) {
                Log.w(TAG, "TUN loop error", e)
                break
            }
        }

        if (running) {
            // The loop died on its own. Report it rather than silently pretending to protect.
            Handler(Looper.getMainLooper()).post {
                shutdown(FirewallPhase.ERROR, "The firewall stopped unexpectedly. Turn USB Only Mode on again.")
            }
        }
    }

    private fun shutdown(phase: FirewallPhase, message: String?) {
        running = false
        worker?.let {
            it.interrupt()
            try { it.join(1000) } catch (_: InterruptedException) {}
        }
        worker = null
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        FirewallState.set(phase, message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // The user turned the VPN off in Android settings, or another VPN app took over.
        // Don't fight it: record that the user's choice changed.
        val ctx = applicationContext
        CoroutineScope(Dispatchers.IO).launch { GuardSettings.setEnabled(ctx, false) }
        shutdown(
            FirewallPhase.REVOKED,
            "Android turned the firewall off (another VPN started, or it was switched off in VPN settings).",
        )
        super.onRevoke()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            try { tun?.close() } catch (_: IOException) {}
            tun = null
            FirewallState.set(FirewallPhase.OFF)
        }
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Firewall status", NotificationManager.IMPORTANCE_LOW),
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FirewallVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("USB Only Mode is on")
            .setContentText("Phone apps are blocked from the internet. USB tethering is left alone.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, "Turn off", stopIntent)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
