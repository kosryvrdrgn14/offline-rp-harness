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

    // Last-used load-time params. If a generate call arrives with different
    // values, we free and reload the model rather than silently keeping the
    // old configuration.
    private var lastNCtx: Int = -1
    private var lastNThreads: Int = -1
    private var lastNBatch: Int = -1

    private fun logMem(label: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            Log.i("Harness", "$label: avail=${mi.availMem / 1_000_000}MB " +
                    "low=${mi.lowMemory} threshold=${mi.threshold / 1_000_000}MB")
        } catch (_: Exception) {}
    }

    private fun ensureLoaded(nCtx: Int, nThreads: Int, nBatch: Int): Boolean {
        synchronized(loadLock) {
            val paramsChanged = modelLoaded && (
                    nCtx      != lastNCtx ||
                            nThreads  != lastNThreads ||
                            nBatch    != lastNBatch
                    )
            if (paramsChanged) {
                Log.i("Harness",
                    "load params changed (nCtx=$nCtx nThreads=$nThreads nBatch=$nBatch) — reloading")
                NativeLib.freeModel()
                modelLoaded = false
            }

            if (modelLoaded) return true

            logMem("before loadModel")
            val path = modelPath
            val ok = NativeLib.loadModel(path, nCtx, nThreads, nBatch)
            logMem("after loadModel")

            if (ok) {
                lastNCtx     = nCtx
                lastNThreads = nThreads
                lastNBatch   = nBatch
            }
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
                val p = JSONObject(paramsJson)

                val nCtx      = p.optInt("nCtx", 1024)
                val nThreads  = p.optInt("nThreads", 4)
                val nBatch    = p.optInt("nBatch", 256)

                if (!ensureLoaded(nCtx, nThreads, nBatch)) {
                    throw IllegalStateException("model failed to load")
                }

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