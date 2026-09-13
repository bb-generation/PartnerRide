package net.bbgen.karoo.partnerride

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.bbgen.karoo.partnerride.screens.MainScreen
import net.bbgen.karoo.partnerride.service.ServiceController
import net.bbgen.karoo.partnerride.theme.AppTheme

class MainActivity : ComponentActivity() {
    private var missingPermissions by mutableStateOf<List<String>>(emptyList())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Only record the result (clearing NO PERM on the field). Granting does not start the
            // link — the rider does, with the switch or a tap on the field.
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
        setContent {
            AppTheme {
                MainScreen(
                    missingPermissions = missingPermissions,
                    onRequestPermissions = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can be revoked in system settings while we are away. Opening the app does
        // not start the link: that stays a deliberate switch or field tap (see ServiceController).
        refreshPermissions()
    }

    private fun refreshPermissions() {
        missingPermissions = ServiceController.recordMissingPermissions(applicationContext)
    }
}
