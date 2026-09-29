package com.wikillm.android

import android.app.Application
import com.getkeepsafe.relinker.ReLinker
import com.wikillm.android.diag.DiagLog
import com.wikillm.android.diag.TurnDump
import com.wikillm.android.ui.theme.ThemePrefs

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
        TurnDump.attach(this)
        ThemePrefs.init(this)
        setupHexagonEnv()
        loadKiwixNatives()
    }

    /**
     * The ggml-hexagon NPU backend loads its DSP skel (libggml-htp-vNN.so) via
     * FastRPC, which finds it through ADSP_LIBRARY_PATH. Point that at our own
     * native lib dir (where the htp libs are packaged) BEFORE libllm.so loads,
     * plus the standard vendor DSP dirs. Without this: "failed to open session
     * : error 0x80000406".
     */
    private fun setupHexagonEnv() {
        try {
            val libDir = applicationInfo.nativeLibraryDir
            val path = "$libDir;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp"
            android.system.Os.setenv("ADSP_LIBRARY_PATH", path, true)
            DiagLog.i(TAG, "ADSP_LIBRARY_PATH=$path")
        } catch (t: Throwable) {
            DiagLog.e(TAG, "setupHexagonEnv failed", t)
        }
        applyDebugEnv()
    }

    /**
     * Backend knobs from adb, no rebuild: ggml-hexagon reads its tuning and
     * workaround switches from the process environment (GGML_HEXAGON_MM_SELECT,
     * GGML_HEXAGON_OPFILTER, GGML_HEXAGON_NHVX, GGML_HEXAGON_DMA64, …), and an
     * Android app has no shell to set them in. So:
     *
     *     adb shell setprop debug.wikillm.env 'GGML_HEXAGON_MM_SELECT=1;GGML_HEXAGON_OPFILTER=^FLASH_ATTN_EXT$'
     *     adb shell am force-stop com.wikillm.android.debug
     *
     * Bisecting a v73 DSP crash on 2026-09-29 cost a CI build and a 10-minute
     * artifact download per hypothesis; the upstream workaround for the
     * related #29473 is exactly such an env var. Debug builds only — a release
     * must not take backend configuration from a world-writable property.
     */
    private fun applyDebugEnv() {
        if (!BuildConfig.DEBUG) return
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java)
            val raw = (get.invoke(null, "debug.wikillm.env") as? String).orEmpty()
            if (raw.isBlank()) return
            raw.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { kv ->
                val k = kv.substringBefore('=').trim()
                val v = kv.substringAfter('=').trim()
                android.system.Os.setenv(k, v, true)
                DiagLog.i(TAG, "debug env: $k=$v")
            }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "applyDebugEnv failed", t)
        }
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
