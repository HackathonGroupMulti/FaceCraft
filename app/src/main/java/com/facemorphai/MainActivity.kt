package com.facemorphai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.facemorphai.bridge.WebViewBridge
import com.facemorphai.config.AppConfig
import com.facemorphai.logging.VlmLogManager
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
        var showLogDialog by remember { mutableStateOf(false) }

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
            repeat(AppConfig.Animation.BUBBLE_COUNT) { index ->
                val size = (60 + Random.nextInt(120)).dp
                val startX = Random.nextFloat() * screenWidth.value
                val startY = Random.nextFloat() * screenHeight.value

                AnimatedBubble(
                    color = if (index % 2 == 0) primaryPurple.copy(alpha = 0.08f) else accentPurple.copy(alpha = 0.05f),
                    size = size,
                    initialX = startX,
                    initialY = startY,
                    duration = AppConfig.Animation.BUBBLE_BASE_DURATION_MS + (index * AppConfig.Animation.BUBBLE_DURATION_INCREMENT_MS)
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
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Log Button
                        val logCount = VlmLogManager.getLogCount()
                        val failureCount = VlmLogManager.getFailureCount()
                        Surface(
                            onClick = { showLogDialog = true },
                            color = if (failureCount > 0) Color(0xFF7F1D1D).copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (failureCount > 0) Color(0xFFEF4444).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.1f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "LOG",
                                    style = TextStyle(fontFamily = techFont, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                )
                                if (logCount > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Surface(
                                        color = if (failureCount > 0) Color(0xFFEF4444) else primaryPurple,
                                        shape = CircleShape
                                    ) {
                                        Text(
                                            text = "$logCount",
                                            style = TextStyle(fontFamily = techFont, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
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
                                    onBlendShapeNamesReceived = { names ->
                                        Log.d(TAG, "BlendShape names received: ${names.size} shapes")
                                        faceMorphService.updateBlendShapeNames(names)
                                    }
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
                                                                override fun onSuccess() {
                                                                    isModelLoading = false
                                                                    isModelReady = true
                                                                    // Trigger VLM-based blendshape categorization
                                                                    lifecycleScope.launch {
                                                                        faceMorphService.categorizeBlendshapes()
                                                                    }
                                                                }
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
                                            else lifecycleScope.launch(Dispatchers.Main) { Toast.makeText(context, result.errorMessage ?: "Unknown error", Toast.LENGTH_SHORT).show() }
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
                                    onClick = { faceMorphService.resetParameters(); webViewBridge?.loadTemplateModel(template) },
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

            // Log Dialog
            if (showLogDialog) {
                VlmLogDialog(
                    onDismiss = { showLogDialog = false },
                    primaryPurple = primaryPurple,
                    accentPurple = accentPurple,
                    surfacePurple = surfacePurple,
                    techFont = techFont
                )
            }
        }
    }

    @Composable
    fun VlmLogDialog(
        onDismiss: () -> Unit,
        primaryPurple: Color,
        accentPurple: Color,
        surfacePurple: Color,
        techFont: FontFamily
    ) {
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        var logs by remember { mutableStateOf(VlmLogManager.getLogsReversed()) }
        var expandedLogIndex by remember { mutableStateOf<Int?>(null) }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f)
                    .padding(8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = surfacePurple)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "VLM DEBUG LOGS",
                                style = TextStyle(
                                    fontFamily = techFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color.White,
                                    letterSpacing = 1.sp
                                )
                            )
                            Text(
                                text = "${VlmLogManager.getSuccessCount()} success / ${VlmLogManager.getFailureCount()} failed",
                                style = TextStyle(
                                    fontFamily = techFont,
                                    fontSize = 10.sp,
                                    color = accentPurple.copy(alpha = 0.7f)
                                )
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Copy All Button
                            Surface(
                                onClick = {
                                    val exportText = VlmLogManager.exportLogsAsText()
                                    clipboardManager.setText(AnnotatedString(exportText))
                                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                color = primaryPurple.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "COPY ALL",
                                    style = TextStyle(fontFamily = techFont, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }

                            // Clear Button
                            Surface(
                                onClick = {
                                    VlmLogManager.clearLogs()
                                    logs = VlmLogManager.getLogsReversed()
                                },
                                color = Color(0xFF7F1D1D).copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "CLEAR",
                                    style = TextStyle(fontFamily = techFont, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444)),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }

                            // Close Button
                            Surface(
                                onClick = onDismiss,
                                color = Color.White.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "✕",
                                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Log List
                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No VLM logs yet.\nTry executing a morph to see logs here.",
                                style = TextStyle(
                                    fontFamily = techFont,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            logs.forEachIndexed { index, log ->
                                val isExpanded = expandedLogIndex == index
                                LogEntryCard(
                                    log = log,
                                    isExpanded = isExpanded,
                                    onToggle = { expandedLogIndex = if (isExpanded) null else index },
                                    onCopy = {
                                        clipboardManager.setText(AnnotatedString(log.toDebugString()))
                                        Toast.makeText(context, "Log entry copied", Toast.LENGTH_SHORT).show()
                                    },
                                    primaryPurple = primaryPurple,
                                    techFont = techFont
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun LogEntryCard(
        log: VlmLogManager.VlmLogEntry,
        isExpanded: Boolean,
        onToggle: () -> Unit,
        onCopy: () -> Unit,
        primaryPurple: Color,
        techFont: FontFamily
    ) {
        val statusColor = if (log.parseSuccess) Color(0xFF22C55E) else Color(0xFFEF4444)
        val statusBgColor = if (log.parseSuccess) Color(0xFF14532D).copy(alpha = 0.3f) else Color(0xFF7F1D1D).copy(alpha = 0.3f)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Status Badge
                        Surface(color = statusBgColor, shape = RoundedCornerShape(4.dp)) {
                            Text(
                                text = if (log.parseSuccess) "✓" else "✗",
                                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = statusColor),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "#${log.requestNumber}",
                            style = TextStyle(fontFamily = techFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                        Text(
                            text = "Attempt ${log.attempt}",
                            style = TextStyle(fontFamily = techFont, fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = log.getFormattedTime(),
                            style = TextStyle(fontFamily = techFont, fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                        )
                        Text(
                            text = "${log.generationTimeMs}ms",
                            style = TextStyle(fontFamily = techFont, fontSize = 10.sp, color = primaryPurple)
                        )
                    }
                }

                // Summary
                Spacer(modifier = Modifier.height(8.dp))
                if (log.parseSuccess) {
                    Text(
                        text = "Parsed ${log.parsedParamCount} parameters",
                        style = TextStyle(fontFamily = techFont, fontSize = 11.sp, color = Color(0xFF22C55E))
                    )
                } else {
                    Text(
                        text = log.parseError ?: "Unknown error",
                        style = TextStyle(fontFamily = techFont, fontSize = 11.sp, color = Color(0xFFEF4444)),
                        maxLines = if (isExpanded) Int.MAX_VALUE else 1
                    )
                }

                // Expanded Details
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Stream Stats
                    if (log.streamTokenCount != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Stream tokens: ${log.streamTokenCount}",
                                style = TextStyle(fontFamily = techFont, fontSize = 10.sp, color = if (log.streamTokenCount == 0) Color(0xFFEF4444) else Color(0xFF22C55E))
                            )
                            if (log.streamTokenCount == 0) {
                                Text(
                                    text = "MODEL RETURNED NO TOKENS",
                                    style = TextStyle(fontFamily = techFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // VLM Raw Output Section
                    Text(
                        text = "VLM RAW OUTPUT (${log.vlmOutputLength ?: 0} chars):",
                        style = TextStyle(fontFamily = techFont, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Black.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val outputText = log.vlmRawOutput ?: "<null>"
                        Text(
                            text = if (outputText.isEmpty()) "<EMPTY - Model returned blank output>" else outputText,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = if (outputText.isEmpty()) Color(0xFFEF4444) else Color(0xFFFBBF24)
                            ),
                            modifier = Modifier
                                .padding(8.dp)
                                .horizontalScroll(rememberScrollState())
                        )
                    }

                    // Raw Stream Results (if available)
                    if (!log.streamRawResults.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "RAW STREAM RESULTS (${log.streamRawResults.size} items):",
                            style = TextStyle(fontFamily = techFont, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp),
                            color = Color.Black.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                log.streamRawResults.forEachIndexed { idx, raw ->
                                    Text(
                                        text = "[$idx]: $raw",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color(0xFF94A3B8)),
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Prompt Section
                    Text(
                        text = "PROMPT (${log.promptLength} chars):",
                        style = TextStyle(fontFamily = techFont, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp),
                        color = Color.Black.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = log.prompt,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color.White.copy(alpha = 0.8f)),
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Copy Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            onClick = onCopy,
                            color = primaryPurple.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "COPY ENTRY",
                                style = TextStyle(fontFamily = techFont, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
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
