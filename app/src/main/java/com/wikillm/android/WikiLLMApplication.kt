package com.wikillm.android

import android.app.Application
import com.wikillm.android.diag.DiagLog

/**
 * Loads libkiwix's native code up front. The published Maven AAR doesn't auto-load
 * its .so, so all org.kiwix.libzim.* JNI methods get UnsatisfiedLinkError until
 * we call System.loadLibrary explicitly. We don't know the exact .so name across
 * versions, so we try the documented ones in order.
 */
class WikiLLMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.attach(this)
        DiagLog.installCrashHandler()
        loadKiwixNatives()
    }

    private fun loadKiwixNatives() {
        // Order matches what kiwix-android uses; first one that loads wins.
        val candidates = listOf("kiwix", "kiwixjni", "libkiwix", "zim", "libzim")
        var loaded = false
        for (name in candidates) {
            try {
                System.loadLibrary(name)
                DiagLog.i(TAG, "System.loadLibrary($name) OK")
                loaded = true
                break
            } catch (t: Throwable) {
                DiagLog.w(TAG, "System.loadLibrary($name) failed: ${t.javaClass.simpleName} ${t.message?.take(140)}")
            }
        }
        if (!loaded) {
            DiagLog.e(TAG, "Could not load any libkiwix .so — ZIM search will not work.")
        }
    }

    companion object { private const val TAG = "WikiLLMApp" }
}
