package com.facemorphai.config

/**
 * Centralized configuration for FaceCraft application.
 * All hardcoded values are extracted here for easier maintenance.
 */
object AppConfig {

    /**
     * Model download configuration.
     */
    object Download {
        /** Estimated total model size in bytes (4.76 GB) */
        const val ESTIMATED_MODEL_SIZE_BYTES = 4_760_000_000L

        /** Connection timeout for downloads in milliseconds */
        const val CONNECT_TIMEOUT_MS = 60_000

        /** Read timeout for downloads in milliseconds */
        const val READ_TIMEOUT_MS = 60_000
    }

    /**
     * VLM model configuration.
     */
    object Model {
        /** Context window size for the VLM */
        const val CONTEXT_SIZE = 2048

        /** Number of threads for model inference */
        const val NUM_THREADS = 4
    }

    /**
     * Token generation limits for different operations.
     */
    object Generation {
        /** Max tokens for streaming generation */
        const val STREAM_MAX_TOKENS = 512

        /** Max tokens for morph parameter generation */
        const val MORPH_MAX_TOKENS = 256

        /** Max tokens for blendshape categorization */
        const val CATEGORIZATION_MAX_TOKENS = 1024

        /** Number of raw results to keep for debugging */
        const val DEBUG_RAW_RESULTS_LIMIT = 20
    }

    /**
     * Morph generation retry configuration.
     */
    object Retry {
        /** Maximum number of VLM generation attempts */
        const val MAX_ATTEMPTS = 2

        /** Delay between retry attempts in milliseconds */
        const val DELAY_MS = 500L

        /** Max number of active parameters to show in prompt for context reduction */
        const val MAX_ACTIVE_PARAMS_IN_PROMPT = 5
    }

    /**
     * Logging configuration.
     */
    object Logging {
        /** Maximum number of VLM log entries to retain */
        const val MAX_LOG_ENTRIES = 50
    }

    /**
     * UI animation configuration.
     */
    object Animation {
        /** Number of neural bubbles in background */
        const val BUBBLE_COUNT = 12

        /** Base duration for bubble animation in milliseconds */
        const val BUBBLE_BASE_DURATION_MS = 10_000

        /** Additional duration per bubble index in milliseconds */
        const val BUBBLE_DURATION_INCREMENT_MS = 1_500
    }
}
