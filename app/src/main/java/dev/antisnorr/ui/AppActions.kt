package dev.antisnorr.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dev.antisnorr.alert.GadgetbridgeBridge

object AppActions {
    private const val TAG = "AppActions"

    fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun openGadgetbridge(context: Context) {
        val pkg = GadgetbridgeBridge.installedPackage(context) ?: GadgetbridgeBridge.PACKAGE
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return
        }
        openUrl(context, "https://f-droid.org/packages/${GadgetbridgeBridge.PACKAGE}/")
    }

    fun requestBatteryExemption(context: Context) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val alreadyIgnoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

        val intent = if (alreadyIgnoring) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        }

        if (!startSafely(context, intent)) {
            startSafely(
                context,
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
    }

    private fun openUrl(context: Context, url: String) {
        startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun startSafely(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: ActivityNotFoundException) {
            Log.w(TAG, "No activity for ${intent.action}: ${t.message}")
            false
        }
    }
}
