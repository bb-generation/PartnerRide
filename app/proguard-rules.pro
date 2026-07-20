# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# kotlinx.serialization: PartnerRideSettings is JSON-encoded into DataStore (see
# data/PartnerRideSettings.kt). Without these, R8 can strip the generated serializer classes and
# the Companion.serializer() accessor, breaking decode at runtime (settings would silently reset).
# Rules per https://github.com/Kotlin/kotlinx.serialization#android
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class net.bbgen.karoo.partnerride.**$$serializer { *; }
-keepclassmembers class net.bbgen.karoo.partnerride.** {
    *** Companion;
}
-keepclasseswithmembers class net.bbgen.karoo.partnerride.** {
    kotlinx.serialization.KSerializer serializer(...);
}