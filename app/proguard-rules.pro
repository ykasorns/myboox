# Methods called from reader.js through WebView.addJavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# jsoup and commonmark refer to optional classes that are not on Android.
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.re2j.**
