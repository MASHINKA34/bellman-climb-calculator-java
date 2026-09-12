# Методы моста вызываются из JavaScript по именам, поэтому их нельзя
# переименовывать и выбрасывать даже при включённом сжатии.
-keepclassmembers class ru.mashinka.bellman.android.ClimbBridge {
    @android.webkit.JavascriptInterface <methods>;
}
