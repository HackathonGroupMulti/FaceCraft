package com.facemorphai

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.facemorphai.bridge.WebViewBridge
import com.facemorphai.model.FaceRegion
import com.facemorphai.model.MorphRequest
import com.facemorphai.service.FaceMorphService
import com.facemorphai.service.NexaService
import com.facemorphai.ui.theme.FaceCraftTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var nexaService: NexaService
    private lateinit var faceMorphService: FaceMorphService
    private var webViewBridge: WebViewBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nexaService = NexaService.getInstance(this)
        faceMorphService = FaceMorphService(this)

        setContent {
            FaceCraftTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FaceMorphScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun FaceMorphScreen() {
        var selectedRegion by remember { mutableStateOf(FaceRegion.ALL) }
        var promptText by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var statusMessage by remember { mutableStateOf("Initializing...") }
        var isModelLoaded by remember { mutableStateOf(false) }
        var expanded by remember { mutableStateOf(false) }

        val context = LocalContext.current

        // Check model status
        LaunchedEffect(Unit) {
            if (nexaService.hasModelLoaded()) {
                statusMessage = "Ready"
                isModelLoaded = true
            } else {
                statusMessage = "Model not loaded - Load a model to start"
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = "FaceCraft",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 3D Viewer (WebView)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 16.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).also { webView ->
                            webViewBridge = WebViewBridge(webView, lifecycleScope).apply {
                                initialize()
                                loadFaceViewer()

                                onModelLoaded = {
                                    Log.d(TAG, "3D Model loaded in WebView")
                                }

                                onModelLoadError = { error ->
                                    Log.e(TAG, "3D Model load error: $error")
                                    lifecycleScope.launch {
                                        Toast.makeText(ctx, "Model load error: $error", Toast.LENGTH_SHORT).show()
                                    }
                                }

                                onMorphApplied = {
                                    Log.d(TAG, "Morphs applied successfully")
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Controls Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Status
                    Text(
                        text = "Status: $statusMessage",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isModelLoaded) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Region Selector
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedRegion.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Face Region") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            FaceRegion.entries.forEach { region ->
                                DropdownMenuItem(
                                    text = { Text(region.displayName) },
                                    onClick = {
                                        selectedRegion = region
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Prompt Input
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        label = { Text("Describe the modification") },
                        placeholder = { Text("e.g., make the eyes look more mysterious") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Generate Button
                    Button(
                        onClick = {
                            if (promptText.isNotBlank()) {
                                isLoading = true
                                statusMessage = "Generating..."

                                lifecycleScope.launch {
                                    val request = MorphRequest(
                                        region = selectedRegion,
                                        prompt = promptText
                                    )

                                    val result = faceMorphService.generateMorph(request)

                                    isLoading = false

                                    if (result.success) {
                                        statusMessage = "Applied in ${result.generationTimeMs}ms"
                                        webViewBridge?.animateMorphs(result.parameters)
                                    } else {
                                        statusMessage = result.errorMessage ?: "Error"
                                        Toast.makeText(
                                            context,
                                            result.errorMessage ?: "Generation failed",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },
                        enabled = !isLoading && promptText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isLoading) "Generating..." else "Apply Changes")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Reset Button
                    OutlinedButton(
                        onClick = {
                            faceMorphService.resetParameters()
                            webViewBridge?.resetMorphs()
                            statusMessage = "Reset to default"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset Face")
                    }

                    // Template Models Row
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Template Models",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("male", "female", "stylized").forEach { template ->
                            OutlinedButton(
                                onClick = {
                                    webViewBridge?.loadTemplateModel(template)
                                    statusMessage = "Loading $template template..."
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(template.replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webViewBridge?.destroy()
    }
}
