package com.roleplay.harness

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object ModelLoader {
    private const val TAG = "ModelLoader"
    private const val ASSET_PATH = "models/Dirty-Alice-RP-NSFW-llama-3.2-1B.i1-Q4_K_M.gguf"
    private const val TARGET_NAME = "Dirty-Alice-RP-NSFW-llama-3.2-1B.i1-Q4_K_M.gguf"
    private const val EXPECTED_MIN_BYTES = 700_000_000L

    private const val CHUNK_BYTES = 4 * 1024 * 1024
    private const val YIELD_MS = 5L

    fun ensureExtracted(context: Context): File {
        Log.i(TAG, "ensureExtracted called from thread: ${Thread.currentThread().name}")
        val target = File(context.filesDir, TARGET_NAME)

        if (target.exists() && target.length() >= EXPECTED_MIN_BYTES) {
            Log.i(TAG, "Model already extracted: ${target.absolutePath} (${target.length()} bytes)")
            return target
        }
        if (target.exists()) {
            Log.w(TAG, "Existing file too small (${target.length()}), re-copying")
            target.delete()
        }

        Log.i(TAG, "Extracting model from assets (throttled)")
        val start = System.currentTimeMillis()

        try {
            context.assets.open(ASSET_PATH).use { input ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(CHUNK_BYTES)
                    var total = 0L
                    var lastLogged = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        output.flush()
                        output.fd.sync()
                        total += n
                        if (total - lastLogged >= 50_000_000L) {
                            Log.i(TAG, "  …${total / 1_000_000} MB copied")
                            lastLogged = total
                        }
                        Thread.sleep(YIELD_MS)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(
                TAG, "Extraction failed at byte position " +
                        "${if (target.exists()) target.length() else 0}", t
            )
            throw t
        }

        val elapsed = (System.currentTimeMillis() - start) / 1000
        Log.i(TAG, "Extraction complete: ${target.length()} bytes in ${elapsed}s")
        return target
    }
}
