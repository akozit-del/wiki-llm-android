package com.wikillm.android

import android.app.Application
import com.getkeepsafe.relinker.ReLinker
import com.wikillm.android.diag.DiagLog

/**
 * libkiwix's AAR ships FOUR native libraries that need to be loaded in order:
 *   1. libzim.so          — core ZIM library (under jniLibs/<abi>/libzim/ — sub-dir!)
 *   2. libkiwix.so        — core libkiwix (under jniLibs/<abi>/libkiwix/ — sub-dir!)
 *   3. libzim_wrapper.so  — JNI bridge: registers Archive/Searcher/Query native methods
 *   4. libkiwix_wrapper.so — JNI bridge: registers Library/Manager/Book native methods
 *
 * The "core" .so files live in non-standard sub-directories so the linker can't find
 * them by itself — that's why we use ReLinker (which scans sub-dirs inside the APK).
 * The "_wrapper" .so files live in the normal jni/<abi>/ folder, so plain
 * System.loadLibrary works for them.
 *
 * Until we load all four, every JNI call on org.kiwix.libzim.Archive throws
 * UnsatisfiedLinkError.
 */
class WikiLLMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.attach(this)
        DiagLog.installCrashHandler()
        loadKiwixNatives()
    }

    private fun loadKiwixNatives() {
        loadViaReLinker("zim")
        loadViaReLinker("kiwix")
        loadViaSystem("zim_wrapper")
        loadViaSystem("kiwix_wrapper")
    }

    private fun loadViaReLinker(name: String) {
        try {
            ReLinker.loadLibrary(this, name)
            DiagLog.i(TAG, "ReLinker.loadLibrary($name) OK")
        } catch (t: Throwable) {
            DiagLog.e(TAG, "ReLinker.loadLibrary($name) failed", t)
        }
    }

    private fun loadViaSystem(name: String) {
        try {
            System.loadLibrary(name)
            DiagLog.i(TAG, "System.loadLibrary($name) OK")
        } catch (t: Throwable) {
            DiagLog.e(TAG, "System.loadLibrary($name) failed", t)
            // Fallback: maybe wrapper is also in a sub-dir for some abi.
            try {
                ReLinker.loadLibrary(this, name)
                DiagLog.i(TAG, "ReLinker.loadLibrary($name) OK (fallback)")
            } catch (t2: Throwable) {
                DiagLog.e(TAG, "ReLinker.loadLibrary($name) also failed", t2)
            }
        }
    }

    companion object { private const val TAG = "WikiLLMApp" }
}
