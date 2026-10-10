package com.roleplay.harness

object NativeLib {
    init {
        System.loadLibrary("harness")
    }

    interface TokenCallback {
        fun onToken(token: String)
    }

    external fun hello(): String

    external fun loadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int): Boolean

    external fun freeModel()

    external fun generate(
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        maxTokens: Int,
        callback: TokenCallback
    ): String

    external fun abort()
}