package net.bbgen.karoo.partnerride

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import net.bbgen.karoo.partnerride.core.PermissionRequest
import net.bbgen.karoo.partnerride.data.streamSettings
import net.bbgen.karoo.partnerride.screens.MainScreen
import net.bbgen.karoo.partnerride.service.PartnerLinkService
import net.bbgen.karoo.partnerride.service.ServiceController
import net.bbgen.karoo.partnerride.theme.AppTheme

class MainActivity : ComponentActivity() {
    private var missingPermissions by mutableStateOf<List<String>>(emptyList())

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            onPermissionsResult(results.keys)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        requestMissingPermissions()
        setContent {
            AppTheme {
                MainScreen(
                    missingPermissions = missingPermissions,
                    onRequestPermissions = ::requestMissingPermissions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        // Opening the app is one of the redundant triggers that bring the link service up (with
        // the boot receiver and the data field view) — the enable toggle must not be the only way.
        lifecycleScope.launch {
            ServiceController.sync(applicationContext, applicationContext.streamSettings().first())
        }
    }

    private fun onPermissionsResult(requested: Set<String>) {
        refreshPermissions()
        // Foreground permissions just granted: go straight on to background location (a settings
        // page on Android 11+, not a dialog) instead of making the rider tap Grant a second time.
        val followUp = PermissionRequest.followUp(requested, missingPermissions)
        if (followUp.isNotEmpty()) permissionLauncher.launch(followUp.toTypedArray())
        // Permissions may have just been granted: bring the service up if enabled.
        lifecycleScope.launch {
            ServiceController.sync(applicationContext, applicationContext.streamSettings().first())
        }
    }

    /** Background location has to be its own, later request — see [PermissionRequest]. */
    private fun requestMissingPermissions() {
        val batch = PermissionRequest.nextBatch(missingPermissions)
        if (batch.isNotEmpty()) permissionLauncher.launch(batch.toTypedArray())
    }

    private fun refreshPermissions() {
        missingPermissions = PartnerLinkService.missingPermissions(this)
    }
}
