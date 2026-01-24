package com.facemorphai.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.facemorphai.model.MorphParameters
import com.facemorphai.parser.MorphParameterParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bridge between Android (Kotlin) and Three.js (JavaScript) in WebView.
 * Handles bidirectional communication for face morphing.
 */
class WebViewBridge(
    private val webView: WebView,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "WebViewBridge"
        private const val JS_INTERFACE_NAME = "AndroidBridge"
        private const val FACE_VIEWER_URL = "file:///android_asset/face_viewer.html"
    }

    private val parser = MorphParameterParser()
    private var isLoaded = false
    private var pendingCommands = mutableListOf<String>()

    // Callbacks for events from JavaScript
    var onModelLoaded: (() -> Unit)? = null
    var onModelLoadError: ((String) -> Unit)? = null
    var onMorphApplied: (() -> Unit)? = null
    var onUserInteraction: ((String, Float) -> Unit)? = null  // Parameter name, new value

    /**
     * Initialize the WebView with Three.js face viewer.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun initialize() {
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            // Allow local file access for GLTF loader
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true

            // Disable zoom for better UX
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false

            // Enable hardware acceleration
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

            // Add JavaScript interface
            addJavascriptInterface(JsInterface(), JS_INTERFACE_NAME)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView page loaded: $url")
                    isLoaded = true

                    // Execute any pending commands
                    pendingCommands.forEach { executeJs(it) }
                    pendingCommands.clear()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(
                    message: String?,
                    lineNumber: Int,
                    sourceID: String?
                ) {
                    Log.d(TAG, "JS Console: $message (line $lineNumber)")
                }
            }
        }
    }

    /**
     * Load the face viewer HTML page.
     */
    fun loadFaceViewer() {
        webView.loadUrl(FACE_VIEWER_URL)
    }

    /**
     * Load a custom 3D model from a file path.
     */
    fun loadModel(modelPath: String) {
        executeJsSafe("loadModel('$modelPath')")
    }

    /**
     * Load one of the built-in template models.
     */
    fun loadTemplateModel(templateId: String) {
        executeJsSafe("loadTemplateModel('$templateId')")
    }

    /**
     * Apply morph parameters to the 3D model.
     */
    fun applyMorphParameters(params: MorphParameters) {
        val json = parser.toJson(params)
        Log.d(TAG, "Applying morphs: $json")
        executeJsSafe("applyMorphs($json)")
    }

    /**
     * Apply a single morph parameter.
     */
    fun applyMorph(paramName: String, value: Float) {
        executeJsSafe("setMorph('$paramName', $value)")
    }

    /**
     * Reset all morphs to default values.
     */
    fun resetMorphs() {
        executeJsSafe("resetMorphs()")
    }

    /**
     * Animate morph parameters smoothly over time.
     */
    fun animateMorphs(params: MorphParameters, durationMs: Int = 500) {
        val json = parser.toJson(params)
        executeJsSafe("animateMorphs($json, $durationMs)")
    }

    /**
     * Set camera position and target.
     */
    fun setCameraPosition(x: Float, y: Float, z: Float) {
        executeJsSafe("setCameraPosition($x, $y, $z)")
    }

    /**
     * Focus camera on a specific face region.
     */
    fun focusOnRegion(region: String) {
        executeJsSafe("focusOnRegion('$region')")
    }

    /**
     * Reset camera to default view.
     */
    fun resetCamera() {
        executeJsSafe("resetCamera()")
    }

    /**
     * Take a screenshot of the current view.
     */
    fun takeScreenshot(callback: (String) -> Unit) {
        screenshotCallback = callback
        executeJsSafe("takeScreenshot()")
    }

    private var screenshotCallback: ((String) -> Unit)? = null

    /**
     * Set the background color of the viewer.
     */
    fun setBackgroundColor(color: String) {
        executeJsSafe("setBackgroundColor('$color')")
    }

    /**
     * Toggle wireframe mode for debugging.
     */
    fun toggleWireframe(enabled: Boolean) {
        executeJsSafe("setWireframe($enabled)")
    }

    /**
     * Enable/disable orbit controls (rotation).
     */
    fun setOrbitControlsEnabled(enabled: Boolean) {
        executeJsSafe("setOrbitControls($enabled)")
    }

    /**
     * Get current morph values from Three.js.
     */
    fun getCurrentMorphs(callback: (MorphParameters) -> Unit) {
        morphCallback = callback
        executeJsSafe("getCurrentMorphs()")
    }

    private var morphCallback: ((MorphParameters) -> Unit)? = null

    /**
     * Execute JavaScript safely, queuing if page not yet loaded.
     */
    private fun executeJsSafe(js: String) {
        if (isLoaded) {
            executeJs(js)
        } else {
            pendingCommands.add(js)
        }
    }

    /**
     * Execute JavaScript code in the WebView.
     */
    private fun executeJs(js: String) {
        scope.launch(Dispatchers.Main) {
            webView.evaluateJavascript(js) { result ->
                Log.d(TAG, "JS result: $result")
            }
        }
    }

    /**
     * JavaScript interface exposed to the WebView.
     * Methods here can be called from JavaScript.
     */
    inner class JsInterface {

        @JavascriptInterface
        fun onModelLoaded() {
            Log.d(TAG, "JS: Model loaded")
            scope.launch(Dispatchers.Main) {
                onModelLoaded?.invoke()
            }
        }

        @JavascriptInterface
        fun onModelLoadError(error: String) {
            Log.e(TAG, "JS: Model load error: $error")
            scope.launch(Dispatchers.Main) {
                onModelLoadError?.invoke(error)
            }
        }

        @JavascriptInterface
        fun onMorphApplied() {
            Log.d(TAG, "JS: Morphs applied")
            scope.launch(Dispatchers.Main) {
                onMorphApplied?.invoke()
            }
        }

        @JavascriptInterface
        fun onParameterChanged(paramName: String, value: Float) {
            Log.d(TAG, "JS: Parameter changed: $paramName = $value")
            scope.launch(Dispatchers.Main) {
                onUserInteraction?.invoke(paramName, value)
            }
        }

        @JavascriptInterface
        fun onScreenshotReady(dataUrl: String) {
            Log.d(TAG, "JS: Screenshot ready")
            scope.launch(Dispatchers.Main) {
                screenshotCallback?.invoke(dataUrl)
                screenshotCallback = null
            }
        }

        @JavascriptInterface
        fun onCurrentMorphs(json: String) {
            Log.d(TAG, "JS: Current morphs: $json")
            scope.launch(Dispatchers.Main) {
                try {
                    val result = parser.parse(json)
                    result.onSuccess { params ->
                        morphCallback?.invoke(params)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse morphs from JS", e)
                }
                morphCallback = null
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "JS Log: $message")
        }

        @JavascriptInterface
        fun error(message: String) {
            Log.e(TAG, "JS Error: $message")
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        webView.removeJavascriptInterface(JS_INTERFACE_NAME)
        webView.destroy()
    }
}
