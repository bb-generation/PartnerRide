package net.bbgen.karoo.partnerride.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import net.bbgen.karoo.partnerride.BuildConfig

// The extension id ("partnerride") must match id= in res/xml/extension_info.xml
// and must not contain dots.
//
// Deliberately does not start the link service when Karoo OS binds it: that is a background start,
// which gets no GPS on Android 11+ — see ServiceController.
class PartnerRideExtension : KarooExtension("partnerride", BuildConfig.VERSION_NAME) {
    override val types: List<DataTypeImpl> by lazy {
        listOf(PartnerRideDataType(extension))
    }
}
