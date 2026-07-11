package net.bbgen.karoo.partnerride.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.bbgen.karoo.partnerride.BuildConfig
import net.bbgen.karoo.partnerride.data.streamSettings
import net.bbgen.karoo.partnerride.service.ServiceController

// The extension id ("partnerride") must match id= in res/xml/extension_info.xml
// and must not contain dots.
class PartnerRideExtension : KarooExtension("partnerride", BuildConfig.VERSION_NAME) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val types: List<DataTypeImpl> by lazy {
        listOf(PartnerRideDataType(extension))
    }

    override fun onCreate() {
        super.onCreate()
        // Karoo OS binds this service on boot: keep the link service in sync with the enable
        // toggle so broadcasting runs whenever the extension is enabled, ride or no ride.
        scope.launch {
            applicationContext.streamSettings().collect { settings ->
                ServiceController.sync(applicationContext, settings)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
