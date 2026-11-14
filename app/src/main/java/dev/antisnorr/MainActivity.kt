package dev.antisnorr

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antisnorr.alert.AlertDispatcher
import dev.antisnorr.alert.AlertReason
import dev.antisnorr.alert.GadgetbridgeBridge
import dev.antisnorr.core.MonitorBus
import dev.antisnorr.core.NightLogger
import dev.antisnorr.core.Settings
import dev.antisnorr.service.DetectionService
import dev.antisnorr.ui.AntiSnorrTheme
import dev.antisnorr.ui.AppActions
import dev.antisnorr.ui.MonitorScreen

class MainActivity : ComponentActivity() {

    private lateinit var dispatcher: AlertDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(applicationContext)
        dispatcher = AlertDispatcher(this)

        setContent {
            AntiSnorrTheme {
                val state by MonitorBus.state.collectAsStateWithLifecycle()
                val config by Settings.config.collectAsStateWithLifecycle()
                val context = LocalContext.current

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    if (result[Manifest.permission.RECORD_AUDIO] == true) {
                        DetectionService.start(context)
                    }
                }

                MonitorScreen(
                    state = state,
                    config = config,
                    onToggle = {
                        if (state.running) {
                            DetectionService.stop(context)
                            MonitorBus.reset()
                        } else {
                            val missing = AppActions.requiredPermissions().filterNot { permission ->
                                ContextCompat.checkSelfPermission(context, permission) ==
                                    PackageManager.PERMISSION_GRANTED
                            }
                            if (missing.isEmpty()) {
                                DetectionService.start(context)
                            } else {
                                permissionLauncher.launch(missing.toTypedArray())
                            }
                        }
                    },
                    onTest = { dispatcher.alert(AlertReason.TEST) },
                    onModeChange = { mode -> Settings.update { it.copy(alertMode = mode) } },
                    onThresholdChange = { value -> Settings.update { it.copy(threshold = value) } },
                    onRepeatChange = { value -> Settings.update { it.copy(repeatMs = value) } },
                    onOpenGadgetbridge = { AppActions.openGadgetbridge(context) },
                    onBatterySettings = { AppActions.requestBatteryExemption(context) },
                    onExportLog = {
                        NightLogger.flush()
                        val file = NightLogger.file(context)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "AntiSnorr night log")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Экспорт лога"))
                    },
                    onClearLog = { NightLogger.clear() },
                    gadgetbridgeInstalled = remember { GadgetbridgeBridge.isInstalled(context) },
                )
            }
        }
    }

    override fun onDestroy() {
        dispatcher.shutdown()
        super.onDestroy()
    }
}
