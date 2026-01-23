package com.facemorphai

import android.app.Application
import android.util.Log
import com.facemorphai.service.NexaService

/**
 * Application class for FaceMorphAI.
 * Handles global initialization.
 */
class FaceMorphApplication : Application() {

    companion object {
        private const val TAG = "FaceMorphApplication"

        lateinit var instance: FaceMorphApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.d(TAG, "FaceMorphAI Application started")

        // Initialize NexaSDK early
        initializeNexaSDK()
    }

    private fun initializeNexaSDK() {
        NexaService.getInstance(this).initialize(object : NexaService.InitCallback {
            override fun onSuccess() {
                Log.d(TAG, "NexaSDK initialized successfully")
            }

            override fun onFailure(reason: String) {
                Log.e(TAG, "NexaSDK initialization failed: $reason")
            }
        })
    }

    override fun onTerminate() {
        super.onTerminate()
        NexaService.getInstance(this).destroy()
    }
}
