# Rules applied to any app that uses media_downloader.

# The page scripts call these by name through the JavaScript bridge; a renamed or removed method is
# a silent TypeError that stops the rest of the script.
-keepclassmembers class com.markhoor.mediadownloader.presentation.browser.PageScriptBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# WorkManager builds this by name through reflection, and the class is internal: R8 in a minified
# host may otherwise rename or drop it, and every download then fails to start.
-keep class com.markhoor.mediadownloader.data.work.DownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
