package dev.antisnorr.alert

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * The only supported way for a third-party app to make Gadgetbridge do
 * something is its Intent API. There is no public "vibrate" command.
 *
 * The receiver lives in Gadgetbridge's DeviceCommunicationService and is
 * exported, but it ignores debug commands unless the user enabled
 * "Intent API → Allow Debug Commands".
 *
 * Docs/source:
 * app/src/main/java/nodomain/freeyourgadget/gadgetbridge/externalevents/IntentApiReceiver.java
 */
object GadgetbridgeBridge {
    const val PACKAGE = "nodomain.freeyourgadget.gadgetbridge"

    /**
     * Upstream Gadgetbridge first, then known forks that keep the same
     * Intent API (e.g. Bangle.js Gadgetbridge, which uses its own package id).
     */
    private val KNOWN_PACKAGES = listOf(
        PACKAGE,
        "com.espruino.gadgetbridge.banglejs",
    )

    private const val ACTION_SEND_NOTIFICATION =
        "nodomain.freeyourgadget.gadgetbridge.command.DEBUG_SEND_NOTIFICATION"
    private const val ACTION_INCOMING_CALL =
        "nodomain.freeyourgadget.gadgetbridge.command.DEBUG_INCOMING_CALL"
    private const val ACTION_END_CALL =
        "nodomain.freeyourgadget.gadgetbridge.command.DEBUG_END_CALL"

    private const val TAG = "GadgetbridgeBridge"

    /** Package id of the installed Gadgetbridge-compatible app, if any. */
    fun installedPackage(context: Context): String? = KNOWN_PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /**
     * Builds a NotificationSpec inside Gadgetbridge and forwards it to the
     * connected device. Most bands vibrate according to the per-app
     * notification settings.
     */
    fun sendNotification(context: Context, title: String, body: String) {
        val target = installedPackage(context) ?: return
        val intent = Intent(ACTION_SEND_NOTIFICATION)
            .setPackage(target)
            .putExtra("sender", title)
            .putExtra("subject", title)
            .putExtra("body", body)
            .putExtra("type", "GENERIC_ALARM_CLOCK")
        send(context, intent)
    }

    /** Simulates an incoming call — usually the strongest vibration available. */
    fun startFakeCall(context: Context, caller: String) {
        val target = installedPackage(context) ?: return
        val intent = Intent(ACTION_INCOMING_CALL)
            .setPackage(target)
            .putExtra("caller", caller)
        send(context, intent)
    }

    fun endFakeCall(context: Context) {
        val target = installedPackage(context) ?: return
        send(context, Intent(ACTION_END_CALL).setPackage(target))
    }

    private fun send(context: Context, intent: Intent) {
        try {
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Broadcast ${intent.action} failed: ${t.message}")
        }
    }
}
