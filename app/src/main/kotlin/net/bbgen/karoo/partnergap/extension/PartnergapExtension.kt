package net.bbgen.karoo.partnergap.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.bbgen.karoo.partnergap.BuildConfig
import net.bbgen.karoo.partnergap.data.streamSettings
import net.bbgen.karoo.partnergap.service.ServiceController

// The extension id ("partnergap") must match id= in res/xml/extension_info.xml
// and must not contain dots.
class PartnergapExtension : KarooExtension("partnergap", BuildConfig.VERSION_NAME) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val types: List<DataTypeImpl> by lazy {
        listOf(PartnerGapDataType(extension))
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
