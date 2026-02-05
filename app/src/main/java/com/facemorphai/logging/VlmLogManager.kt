package com.facemorphai.logging

import android.util.Log
import com.facemorphai.config.AppConfig
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton to capture and store VLM request/response logs for debugging.
 */
object VlmLogManager {

    private const val TAG = "VlmLogManager"
    private val MAX_LOGS = AppConfig.Logging.MAX_LOG_ENTRIES

    private val logs = CopyOnWriteArrayList<VlmLogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    data class VlmLogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val requestNumber: Int,
        val prompt: String,
        val promptLength: Int,
        val vlmRawOutput: String?,
        val vlmOutputLength: Int?,
        val parseSuccess: Boolean,
        val parseError: String?,
        val parsedParamCount: Int?,
        val generationTimeMs: Long,
        val attempt: Int,
        val streamTokenCount: Int? = null,
        val streamRawResults: List<String>? = null
    ) {
        fun getFormattedTime(): String = dateFormat.format(Date(timestamp))

        fun toDebugString(): String = buildString {
            appendLine("═══════════════════════════════════════")
            appendLine("📋 Request #$requestNumber (Attempt $attempt)")
            appendLine("🕐 Time: ${getFormattedTime()}")
            appendLine("⏱️ Duration: ${generationTimeMs}ms")
            appendLine("───────────────────────────────────────")
            appendLine("📤 PROMPT ($promptLength chars):")
            appendLine(prompt)
            appendLine("───────────────────────────────────────")
            if (streamTokenCount != null) {
                appendLine("🔢 Stream tokens received: $streamTokenCount")
            }
            if (vlmRawOutput != null) {
                appendLine("📥 VLM RAW OUTPUT ($vlmOutputLength chars):")
                appendLine("\"$vlmRawOutput\"")
                if (vlmRawOutput.isEmpty()) {
                    appendLine("⚠️ OUTPUT IS EMPTY - Model returned nothing!")
                }
                appendLine("───────────────────────────────────────")
            } else {
                appendLine("📥 VLM OUTPUT: <null/error>")
            }
            if (!streamRawResults.isNullOrEmpty()) {
                appendLine("🔍 RAW STREAM RESULTS (${streamRawResults.size} items):")
                streamRawResults.forEachIndexed { idx, raw ->
                    appendLine("  [$idx]: $raw")
                }
                appendLine("───────────────────────────────────────")
            }
            if (parseSuccess) {
                appendLine("✅ PARSE SUCCESS: $parsedParamCount parameters")
            } else {
                appendLine("❌ PARSE FAILED: $parseError")
            }
            appendLine("═══════════════════════════════════════")
        }
    }

    private var requestCounter = 0

    fun logVlmInteraction(
        prompt: String,
        vlmOutput: String?,
        parseSuccess: Boolean,
        parseError: String?,
        parsedParamCount: Int?,
        generationTimeMs: Long,
        attempt: Int,
        streamTokenCount: Int? = null,
        streamRawResults: List<String>? = null
    ) {
        requestCounter++
        val entry = VlmLogEntry(
            requestNumber = requestCounter,
            prompt = prompt,
            promptLength = prompt.length,
            vlmRawOutput = vlmOutput,
            vlmOutputLength = vlmOutput?.length,
            parseSuccess = parseSuccess,
            parseError = parseError,
            parsedParamCount = parsedParamCount,
            generationTimeMs = generationTimeMs,
            attempt = attempt,
            streamTokenCount = streamTokenCount,
            streamRawResults = streamRawResults
        )

        logs.add(entry)

        // Keep only the last MAX_LOGS entries
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }

        Log.d(TAG, entry.toDebugString())
    }

    fun getLogs(): List<VlmLogEntry> = logs.toList()

    fun getLogsReversed(): List<VlmLogEntry> = logs.reversed()

    fun clearLogs() {
        logs.clear()
        requestCounter = 0
    }

    fun getLogCount(): Int = logs.size

    fun exportLogsAsText(): String = buildString {
        appendLine("VLM Debug Logs - Exported ${dateFormat.format(Date())}")
        appendLine("Total entries: ${logs.size}")
        appendLine()
        logs.forEach { entry ->
            append(entry.toDebugString())
            appendLine()
        }
    }

    fun getLastFailedLog(): VlmLogEntry? = logs.lastOrNull { !it.parseSuccess }

    fun getFailureCount(): Int = logs.count { !it.parseSuccess }

    fun getSuccessCount(): Int = logs.count { it.parseSuccess }
}
