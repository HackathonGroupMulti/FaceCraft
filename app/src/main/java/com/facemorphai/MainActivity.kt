package com.facemorphai

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType as MenuAnchorType
import androidx.compose.runtime.*
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

        // Enable edge-to-edge display
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

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
        var expanded by remember { mutableStateOf(false) }

        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 8.dp)
        ) {
            // Title
            Text(
                text = "FaceCraft",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // 3D Viewer (WebView)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f),
                shape = MaterialTheme.shapes.large
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

            Spacer(modifier = Modifier.height(12.dp))

            // Controls Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
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
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
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

                    Spacer(modifier = Modifier.height(10.dp))

                    // Prompt Input
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        label = { Text("Describe the modification") },
                        placeholder = { Text("e.g., make the eyes look more mysterious") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 2,
                        singleLine = false
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Reset Button
                        OutlinedButton(
                            onClick = {
                                faceMorphService.resetParameters()
                                webViewBridge?.resetMorphs()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reset")
                        }

                        // Generate Button
                        Button(
                            onClick = {
                                if (promptText.isNotBlank()) {
                                    isLoading = true

                                    lifecycleScope.launch {
                                        val request = MorphRequest(
                                            region = selectedRegion,
                                            prompt = promptText
                                        )

                                        val result = faceMorphService.generateMorph(request)

                                        isLoading = false

                                        if (result.success) {
                                            webViewBridge?.animateMorphs(result.parameters)
                                        } else {
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
                            modifier = Modifier.weight(2f)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (isLoading) "Generating..." else "Apply")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Template Models Row
                    Text(
                        text = "Templates",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("male", "female").forEach { template ->
                            OutlinedButton(
                                onClick = {
                                    webViewBridge?.loadTemplateModel(template)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    template.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelMedium
                                )
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
