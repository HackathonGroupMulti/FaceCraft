# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep NexaSDK classes
-keep class ai.nexa.** { *; }
-keepclassmembers class ai.nexa.** { *; }

# Keep serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep model classes for serialization
-keep class com.facemorphai.model.** { *; }
-keepclassmembers class com.facemorphai.model.** { *; }

# Keep WebView JavaScript interface
-keepclassmembers class com.facemorphai.bridge.WebViewBridge$JsInterface {
    public *;
}
-keepattributes JavascriptInterface
