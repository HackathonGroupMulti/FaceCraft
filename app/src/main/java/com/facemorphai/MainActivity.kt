package com.facemorphai

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType as MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.facemorphai.bridge.WebViewBridge
import com.facemorphai.model.FaceRegion
import com.facemorphai.model.MorphRequest
import com.facemorphai.service.FaceMorphService
import com.facemorphai.service.ModelDownloader
import com.facemorphai.service.NexaService
import com.facemorphai.ui.theme.FaceCraftTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var nexaService: NexaService
    private lateinit var faceMorphService: FaceMorphService
    private lateinit var modelDownloader: ModelDownloader
    private var webViewBridge: WebViewBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install Splash Screen
        installSplashScreen()
        
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        nexaService = NexaService.getInstance(this)
        faceMorphService = FaceMorphService(this)
        modelDownloader = ModelDownloader(this)

        setContent {
            FaceCraftTheme {
                FaceMorphScreen()
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

        var isModelDownloaded by remember { mutableStateOf(modelDownloader.isModelDownloaded()) }
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf(0) }
        var downloadedMB by remember { mutableStateOf(0L) }
        var totalMB by remember { mutableStateOf(0L) }

        var isModelLoading by remember { mutableStateOf(false) }
        var isModelReady by remember { mutableStateOf(false) }

        val context = LocalContext.current

        // Theme Colors
        val primaryPurple = Color(0xFF9A64DE)
        val accentPurple = Color(0xFFB794F4)
        val deepBackground = Color(0xFF0A0514)
        val surfacePurple = Color(0xFF160D2B)

        // Modern Tech Fonts
        val titleFont = FontFamily.SansSerif
        val techFont = FontFamily.Monospace

        // Immersive Background
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(deepBackground, surfacePurple)))
        ) {
            val screenWidth = maxWidth
            val screenHeight = maxHeight

            // Neural Bubbles - Dynamic Background
            repeat(12) { index ->
                val size = (60 + Random.nextInt(120)).dp
                val startX = Random.nextFloat() * screenWidth.value
                val startY = Random.nextFloat() * screenHeight.value
                
                AnimatedBubble(
                    color = if (index % 2 == 0) primaryPurple.copy(alpha = 0.08f) else accentPurple.copy(alpha = 0.05f),
                    size = size,
                    initialX = startX,
                    initialY = startY,
                    duration = 10000 + (index * 1500)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                // Header Area
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "FACECRAFT",
                            style = TextStyle(
                                fontFamily = titleFont,
                                fontWeight = FontWeight.Black,
                                fontSize = 34.sp,
                                color = Color.White,
                                letterSpacing = (-2).sp
                            )
                        )
                        Text(
                            text = "NEURAL MORPHING ENGINE",
                            style = TextStyle(
                                fontFamily = techFont,
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp,
                                color = accentPurple.copy(alpha = 0.7f),
                                letterSpacing = 2.sp
                            )
                        )
                    }
                    
                    // AI Pulse Status
                    Surface(
                        color = if (isModelReady) primaryPurple.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (isModelReady) primaryPurple.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.1f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isModelReady) {
                                val infiniteTransition = rememberInfiniteTransition()
                                val scale by infiniteTransition.animateFloat(
                                    initialValue = 0.8f, targetValue = 1.2f,
                                    animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .graphicsLayer { scaleX = scale; scaleY = scale }
                                        .background(primaryPurple, CircleShape)
                                )
                            } else {
                                Box(modifier = Modifier.size(8.dp).background(Color.Gray.copy(alpha = 0.5f), CircleShape))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isModelReady) "CORE ACTIVE" else "STBY",
                                style = TextStyle(fontFamily = techFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            )
                        }
                    }
                }

                // Main Viewer
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.3f)
                        .padding(bottom = 16.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(32.dp)),
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.4f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).also { webView ->
                                webView.setBackgroundColor(0) 
                                webViewBridge = WebViewBridge(webView, lifecycleScope).apply {
                                    initialize()
                                    loadFaceViewer()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Interaction Panel
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(32.dp)),
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Setup Banner
                        if (!isModelReady) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                color = primaryPurple.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, primaryPurple.copy(alpha = 0.15f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isDownloading) "SYNCING..." else "CORE OFFLINE",
                                            style = TextStyle(fontFamily = techFont, fontWeight = FontWeight.Bold, color = Color.White)
                                        )
                                        Text(
                                            text = if (isDownloading) "${downloadedMB}MB RECOVERY" else "Initialize local AI core",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                    }

                                    if (isDownloading) {
                                        CircularProgressIndicator(
                                            progress = { downloadProgress / 100f },
                                            modifier = Modifier.size(24.dp),
                                            color = primaryPurple,
                                            strokeWidth = 3.dp
                                        )
                                    } else if (isModelDownloaded) {
                                        Button(
                                            onClick = {
                                                isModelLoading = true
                                                nexaService.initialize(object : NexaService.InitCallback {
                                                    override fun onSuccess() {
                                                        val manifestFile = File(modelDownloader.getModelPath(), "nexa.manifest")
                                                        nexaService.loadModel(manifestFile.absolutePath, preferNpu = true,
                                                            callback = object : NexaService.ModelLoadCallback {
                                                                override fun onSuccess() { isModelLoading = false; isModelReady = true }
                                                                override fun onFailure(reason: String) {
                                                                    isModelLoading = false
                                                                    lifecycleScope.launch(Dispatchers.Main) { Toast.makeText(context, "Link Fail: $reason", Toast.LENGTH_SHORT).show() }
                                                                }
                                                            }
                                                        )
                                                    }
                                                    override fun onFailure(reason: String) { isModelLoading = false }
                                                })
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = primaryPurple),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp)
                                        ) {
                                            if (isModelLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                            else Text("BOOT CORE", style = TextStyle(fontFamily = techFont, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 10.sp))
                                        }
                                    } else {
                                        TextButton(onClick = {
                                            isDownloading = true
                                            lifecycleScope.launch {
                                                modelDownloader.downloadModel().collect { state ->
                                                    when (state) {
                                                        is ModelDownloader.DownloadState.Progress -> {
                                                            downloadProgress = state.progress
                                                            downloadedMB = state.downloadedMB
                                                            totalMB = state.totalMB
                                                        }
                                                        is ModelDownloader.DownloadState.Completed -> { isDownloading = false; isModelDownloaded = true }
                                                        is ModelDownloader.DownloadState.Error -> { isDownloading = false }
                                                        else -> {}
                                                    }
                                                }
                                            }
                                        }) {
                                            Text("DOWNLOAD", color = accentPurple, style = TextStyle(fontFamily = techFont, fontWeight = FontWeight.Bold))
                                        }
                                    }
                                }
                            }
                        }

                        // Target Selector
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedRegion.displayName.uppercase(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("NEURAL TARGET", style = TextStyle(fontFamily = techFont, fontSize = 9.sp, color = accentPurple.copy(alpha = 0.5f))) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.03f),
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedIndicatorColor = primaryPurple.copy(alpha = 0.5f),
                                    unfocusedIndicatorColor = Color.White.copy(alpha = 0.1f)
                                ),
                                textStyle = TextStyle(fontFamily = techFont, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                                shape = RoundedCornerShape(16.dp)
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.background(surfacePurple).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            ) {
                                FaceRegion.entries.forEach { region ->
                                    DropdownMenuItem(
                                        text = { Text(region.displayName.uppercase(), color = Color.White, style = TextStyle(fontFamily = techFont, fontSize = 12.sp)) },
                                        onClick = { selectedRegion = region; expanded = false }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Prompt Input
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            placeholder = { Text("Command neural modification...", color = Color.White.copy(alpha = 0.2f), style = TextStyle(fontFamily = titleFont)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.02f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedIndicatorColor = primaryPurple,
                                unfocusedIndicatorColor = Color.White.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            textStyle = TextStyle(fontFamily = titleFont, fontSize = 16.sp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Action Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { faceMorphService.resetParameters(); webViewBridge?.resetMorphs() },
                                modifier = Modifier.weight(1f).height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                            ) {
                                Text("RESET", color = Color.White.copy(alpha = 0.6f), style = TextStyle(fontFamily = techFont, fontWeight = FontWeight.Bold))
                            }

                            val isApplyEnabled = !isLoading && promptText.isNotBlank()
                            val applyButtonBrush = if (isApplyEnabled) {
                                Brush.horizontalGradient(listOf(primaryPurple, Color(0xFF7C3AED)))
                            } else {
                                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.05f)))
                            }

                            Button(
                                onClick = {
                                    if (promptText.isNotBlank()) {
                                        isLoading = true
                                        lifecycleScope.launch {
                                            val result = faceMorphService.generateMorph(MorphRequest(region = selectedRegion, prompt = promptText))
                                            isLoading = false
                                            if (result.success) webViewBridge?.animateMorphs(result.parameters)
                                            else lifecycleScope.launch(Dispatchers.Main) { Toast.makeText(context, "Neural Error", Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                },
                                enabled = isApplyEnabled,
                                modifier = Modifier.weight(2f).height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent
                                ),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(applyButtonBrush),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                    else Text("EXECUTE MORPH", color = if (isApplyEnabled) Color.White else Color.White.copy(alpha = 0.3f), style = TextStyle(fontFamily = techFont, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Identity Presets
                        Text(
                            text = "IDENTITY PROTOCOLS",
                            style = TextStyle(fontFamily = techFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accentPurple.copy(alpha = 0.5f), letterSpacing = 1.sp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            listOf("ally", "lisa").forEach { template ->
                                Surface(
                                    onClick = { webViewBridge?.loadTemplateModel(template) },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.White.copy(alpha = 0.04f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(template.uppercase(), color = Color.White, style = TextStyle(fontFamily = techFont, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AnimatedBubble(
        color: Color,
        size: androidx.compose.ui.unit.Dp,
        initialX: Float,
        initialY: Float,
        duration: Int
    ) {
        val infiniteTransition = rememberInfiniteTransition()
        val animX by infiniteTransition.animateFloat(
            initialValue = initialX,
            targetValue = initialX + (Random.nextInt(100) - 50).toFloat(),
            animationSpec = infiniteRepeatable(animation = tween(duration, easing = LinearOutSlowInEasing), repeatMode = RepeatMode.Reverse)
        )
        val animY by infiniteTransition.animateFloat(
            initialValue = initialY,
            targetValue = initialY + (Random.nextInt(150) - 75).toFloat(),
            animationSpec = infiniteRepeatable(animation = tween(duration + 2000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse)
        )
        val alphaScale by infiniteTransition.animateFloat(
            initialValue = 0.6f, targetValue = 1.1f,
            animationSpec = infiniteRepeatable(animation = tween(duration / 2, easing = LinearEasing), repeatMode = RepeatMode.Reverse)
        )

        Box(
            modifier = Modifier
                .offset(x = animX.dp, y = animY.dp)
                .size(size)
                .graphicsLayer { alpha = alphaScale }
                .blur(size / 2)
                .background(color, CircleShape)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        webViewBridge?.destroy()
    }
}
