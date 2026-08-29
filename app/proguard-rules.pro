# Keep WebView JavascriptInterface annotations and members
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class ai.aibridge.** { *; }
