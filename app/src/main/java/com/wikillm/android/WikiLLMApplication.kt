package com.wikillm.android

import android.app.Application
import com.getkeepsafe.relinker.ReLinker
import com.wikillm.android.diag.DiagLog

/**
 * libkiwix's published AAR packs its .so files under jniLibs/<abi>/libzim/
 * and jniLibs/<abi>/libkiwix/ sub-folders (not directly in jniLibs/<abi>/),
 * which is exactly the layout that ReLinker handles. A plain
 * System.loadLibrary("zim") would throw UnsatisfiedLinkError because the
 * runtime linker doesn't look in sub-directories.
 *
 * Load order: libzim first, then libkiwix (libkiwix depends on libzim symbols).
 */
class WikiLLMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.attach(this)
        DiagLog.installCrashHandler()
        loadKiwixNatives()
    }

    private fun loadKiwixNatives() {
        loadOne("zim")
        loadOne("kiwix")
    }

    private fun loadOne(name: String) {
        try {
            ReLinker.loadLibrary(this, name)
            DiagLog.i(TAG, "ReLinker.loadLibrary($name) OK")
        } catch (t: Throwable) {
            DiagLog.e(TAG, "ReLinker.loadLibrary($name) failed", t)
            // Fall back to the regular linker in case the layout is flat after all.
            try {
                System.loadLibrary(name)
                DiagLog.i(TAG, "System.loadLibrary($name) OK (fallback)")
            } catch (t2: Throwable) {
                DiagLog.e(TAG, "System.loadLibrary($name) also failed", t2)
            }
        }
    }

    companion object { private const val TAG = "WikiLLMApp" }
}
