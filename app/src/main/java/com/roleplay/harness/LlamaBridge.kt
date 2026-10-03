package com.roleplay.harness

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

class LlamaBridge(
    private val context: Context,
    private val webView: WebView,
    private val mainHandler: Handler
) {

    private var modelLoaded = false
    private val loadLock = Any()
    private val modelPath: String by lazy {
        ModelLoader.ensureExtracted(context).absolutePath
    }

    private fun logMem(label: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            Log.i("Harness", "$label: avail=${mi.availMem / 1_000_000}MB " +
                    "low=${mi.lowMemory} threshold=${mi.threshold / 1_000_000}MB")
        } catch (_: Exception) {}
    }

    private fun ensureLoaded(): Boolean {
        synchronized(loadLock) {
            if (modelLoaded) return true
            logMem("before loadModel")
            val path = modelPath
            // n_ctx=1024 instead of 2048 to halve KV cache and lower load spike.
            val ok = NativeLib.loadModel(path, 1024, 4)
            logMem("after loadModel")
            modelLoaded = ok
            return ok
        }
    }

    @JavascriptInterface
    fun hello(): String = "bridge is alive"

    @JavascriptInterface
    fun abort() {
        NativeLib.abort()
    }

    @JavascriptInterface
    fun freeModel() {
        NativeLib.freeModel()
        modelLoaded = false
    }

    @JavascriptInterface
    fun isLoaded(): Boolean = modelLoaded

    @JavascriptInterface
    fun generate(prompt: String, requestId: String, paramsJson: String) {
        Thread {
            try {
                if (!ensureLoaded()) {
                    throw IllegalStateException("model failed to load")
                }

                val p = JSONObject(paramsJson)
                val callback = object : NativeLib.TokenCallback {
                    override fun onToken(token: String) {
                        val quoted = JSONObject.quote(token)
                        mainHandler.post {
                            webView.evaluateJavascript(
                                "window.__nativeOnToken && window.__nativeOnToken(" +
                                        "${JSONObject.quote(requestId)}, $quoted)",
                                null
                            )
                        }
                    }
                }

                val full = NativeLib.generate(
                    prompt,
                    p.optDouble("temperature", 0.7).toFloat(),
                    p.optDouble("topP", 0.9).toFloat(),
                    p.optInt("topK", 40),
                    p.optDouble("repetitionPenalty", 1.05).toFloat(),
                    p.optInt("maxTokens", 250),
                    callback
                )

                val quoted = JSONObject.quote(full)
                mainHandler.post {
                    webView.evaluateJavascript(
                        "window.__nativeOnDone && window.__nativeOnDone(" +
                                "${JSONObject.quote(requestId)}, $quoted)",
                        null
                    )
                }
            } catch (e: Exception) {
                Log.e("Harness", "generate failed", e)
                val msg = JSONObject.quote(e.message ?: "native error")
                mainHandler.post {
                    webView.evaluateJavascript(
                        "window.__nativeOnError && window.__nativeOnError(" +
                                "${JSONObject.quote(requestId)}, $msg)",
                        null
                    )
                }
            }
        }.start()
    }
}