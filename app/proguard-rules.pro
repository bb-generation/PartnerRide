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

# No manual kotlinx.serialization keep rules needed: the library (1.8.0) ships its own R8
# consumer rules (META-INF/com.android.tools/r8/kotlinx-serialization-*.pro in
# kotlinx-serialization-core-jvm), auto-merged by AGP for every @Serializable class in the
# program — ours and karoo-ext's models alike. Verified against the release mapping.txt: both
# PartnerRideSettings$$serializer and karoo-ext's own model serializers (e.g. DataPoint) survive
# minification with all serialize/deserialize/descriptor methods intact.