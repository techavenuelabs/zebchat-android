# The WebView bridge fallback calls this method from JavaScript by name.
-keepclassmembers class com.zebchat.chat.internal.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# The WebView only calls methods that still carry the annotation at runtime.
-keepattributes JavascriptInterface
-keepattributes RuntimeVisibleAnnotations
