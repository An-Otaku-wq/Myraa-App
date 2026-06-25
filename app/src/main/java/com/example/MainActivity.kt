package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import com.example.data.database.MyraaDatabase
import com.example.data.database.MyraaRepository
import com.example.system.DeviceManager
import com.example.system.SpeechManager
import com.example.ui.components.FusionCoreOrb
import com.example.ui.components.TerminalMsgRow
import com.example.ui.components.StaticPluginCard
import com.example.ui.components.ProFeatureRow
import com.example.ui.theme.*
import com.example.ui.viewmodel.ChatUiState
import com.example.ui.viewmodel.MyraaViewModel
import com.example.ui.viewmodel.TerminalMessage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val RECORD_AUDIO_REQUEST_CODE = 4410

    private lateinit var speechManager: SpeechManager
    private lateinit var deviceManager: DeviceManager
    private lateinit var viewModel: MyraaViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize local persistent database (Room)
        val database = Room.databaseBuilder(
            applicationContext,
            MyraaDatabase::class.java, "myraa_system_db"
        ).fallbackToDestructiveMigration().build()

        val repository = MyraaRepository(
            database.memoryDao(),
            database.pluginDao(),
            database.conversationMessageDao(),
            database.userPreferenceDao()
        )

        // 2. Initialize ViewModel constructor
        viewModel = MyraaViewModel(repository)

        // 3. Initialize Device Action Coordinators
        deviceManager = DeviceManager(this)

        // 4. Initialize Speech Synthesis & Speech Recognition engine
        speechManager = SpeechManager(
            context = this,
            onSpeechVolumeChanged = { volume -> viewModel.setMicVolume(volume) },
            onSpeechStatusMsg = { status -> viewModel.setSttStatus(status) },
            onSpeechResult = { text ->
                viewModel.sendMessage(text, BuildConfig.GEMINI_API_KEY)
            }
        ).apply {
            onSpeakingStateChanged = { speakingState ->
                viewModel.setSpeaking(speakingState)
            }
            onListeningStateChanged = { listeningState ->
                viewModel.setSttListening(listeningState)
            }
        }

        // Link triggers
        viewModel.onSpeakTts = { text -> speechManager.speak(text) }
        viewModel.onUpdateTtsSettings = { pitch, rate ->
            speechManager.setPitch(pitch)
            speechManager.setSpeechRate(rate)
        }
        viewModel.onExecuteDeviceAction = { actionName, actionArgs ->
            executeCoreDeviceAction(actionName, actionArgs)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SpaceBackground)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) { innerPadding ->
                    MyraaScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        onVoiceClick = { requestVoiceInput() },
                        onStopSpeakingClick = { speechManager.stopSpeaking() }
                    )
                }
            }
        }
    }

    private fun executeCoreDeviceAction(name: String, args: String) {
        runOnUiThread {
            when (name.uppercase().trim()) {
                "LAUNCH_APP" -> deviceManager.launchAppByAlias(args)
                "ADJUST_VOLUME" -> {
                    val level = args.toIntOrNull() ?: 50
                    deviceManager.adjustVolume(level)
                }
                "ADJUST_BRIGHTNESS" -> {
                    val level = args.toIntOrNull() ?: 75
                    deviceManager.adjustBrightness(this, level)
                }
                "WEB_SEARCH" -> deviceManager.webSearch(args)
                "CONTROL_FLASHLIGHT" -> deviceManager.toggleFlashlight(args)
                else -> {
                    Toast.makeText(this, "Unknown automation command received.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_REQUEST_CODE
            )
        } else {
            if (speechManager.isListening) {
                speechManager.stopListening()
            } else {
                speechManager.startListening()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                speechManager.startListening()
            } else {
                Toast.makeText(this, "Audio recording permission is required for voice activation.", Toast.LENGTH_LONG).show()
                viewModel.setSttStatus("Microphone access permission denied.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroy()
    }
}

// --- COMPOSE HUD SCREENS ---

@Composable
fun MyraaScreen(
    viewModel: MyraaViewModel,
    modifier: Modifier = Modifier,
    onVoiceClick: () -> Unit = {},
    onStopSpeakingClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val geminiKey = BuildConfig.GEMINI_API_KEY

    // UI flows state collection
    val terminalMsgs by viewModel.terminalMessages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeEmotion by viewModel.activeEmotion.collectAsStateWithLifecycle()
    val emotionReason by viewModel.activeEmotionReason.collectAsStateWithLifecycle()
    val micVolume by viewModel.micVolume.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val sttStatus by viewModel.sttStatus.collectAsStateWithLifecycle()
    val memories by viewModel.storedMemories.collectAsStateWithLifecycle()
    val plugins by viewModel.activePlugins.collectAsStateWithLifecycle()
    val latestAction by viewModel.latestExecutedAction.collectAsStateWithLifecycle()

    val activeAuraMode by viewModel.activeAuraMode.collectAsStateWithLifecycle()
    val synergyLevel by viewModel.synergyLevel.collectAsStateWithLifecycle()

    // Pro Upgrade flow states
    val isProUnlocked by viewModel.isProUnlocked.collectAsStateWithLifecycle()
    val activeProModel by viewModel.activeProModel.collectAsStateWithLifecycle()
    val voicePitch by viewModel.voicePitch.collectAsStateWithLifecycle()
    val voiceSpeed by viewModel.voiceSpeed.collectAsStateWithLifecycle()

    var selectedProTheme by remember { mutableStateOf("GOLD") } // "GOLD", "CYAN", "NEBULA"

    val activePrimaryC = if (!isProUnlocked) MyraaPrimary else {
        when(selectedProTheme) {
            "GOLD" -> Color(0xFFFFD700)
            "NEBULA" -> Color(0xFFE040FB)
            else -> MyraaPrimary
        }
    }
    
    val activeSecondaryC = if (!isProUnlocked) MyraaSecondary else {
        when(selectedProTheme) {
            "GOLD" -> Color(0xFFFF8F00)
            "NEBULA" -> Color(0xFF3F51B5)
            else -> MyraaSecondary
        }
    }

    val activeTertiaryC = if (!isProUnlocked) MyraaTertiary else {
        when(selectedProTheme) {
            "GOLD" -> Color(0xFFFF3D00)
            "NEBULA" -> Color(0xFFEC407A)
            else -> MyraaTertiary
        }
    }

    var userText by remember { mutableStateOf("") }
    var currentTabSelected by remember { mutableStateOf("TERMINAL") } // "TERMINAL", "MEMORY_INDEX", "EVOLUTION"

    // Dialog flags
    var showEvolvePluginDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(uiState) {
        if (uiState is ChatUiState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = (uiState as ChatUiState.Error).message,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.dismissError()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SpaceBackground)
                .padding(innerPadding)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. TOP STATS & BINDING TELEMETRY HEADER
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, activeSecondaryC.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .background(SpaceCardBg.copy(alpha = 0.8f))
                    .padding(12.dp)
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isProUnlocked) "MYRAA PRO // QUANTUM EYE" else "MYRAA // COMPANION CORE",
                            color = activePrimaryC,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        if (isProUnlocked) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(activePrimaryC.copy(alpha = 0.15f))
                                    .border(1.dp, activePrimaryC, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    "PRO",
                                    color = activePrimaryC,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(CyberGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isProUnlocked) "STATUS: PRO QUANTUM DUAL SYNC" else "STATUS: ONLINE // RESONANCE FLUID",
                            color = CyberGreen,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    val statusText = when {
                        synergyLevel < 50 -> "ACQUAINTED"
                        synergyLevel < 70 -> "EMPATHY CORE"
                        synergyLevel < 90 -> "DEEP RESONANCE"
                        else -> "SOUL SYNC"
                    }
                    Text(
                        text = "BOND: $statusText [Sync: $synergyLevel%]",
                        color = activeTertiaryC,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "AURA MODE: ${activeAuraMode.uppercase()}",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bonding Sync Bar Index
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💖 SYNERGY",
                    color = activeTertiaryC,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(70.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(synergyLevel / 100f)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(activeSecondaryC, activeTertiaryC)
                                )
                            )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. DUAL VISUALIZER: CENTRAL EMOTION PORTRAIT CARD & ENERGY FUSION ORB CORE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .border(2.dp, activeSecondaryC.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .background(SpaceCardBg.copy(alpha = 0.3f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Holographic AI Avatar Image Frame
            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        BorderStroke(
                            1.1.dp,
                            when (activeEmotion.uppercase()) {
                                "ROMANTIC" -> activeTertiaryC
                                "WITTY" -> activePrimaryC
                                "ANALYTICAL" -> OrangeGlow
                                "SURPRISED" -> Color(0xFFE040FB)
                                else -> activeSecondaryC
                            }.copy(alpha = 0.8f)
                        ),
                        RoundedCornerShape(8.dp)
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_myraa_avatar),
                    contentDescription = "Myraa Portrait Live Sync",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Scanline glowing mask overlay (faint HUD diagnostic animation decoration)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            activePrimaryC.copy(alpha = 0.6f)
                        )
                        .align(Alignment.Center)
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "SYNCED",
                        color = activePrimaryC,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Right Column: Fusion Dynamic Orb Visualizer Core
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                FusionCoreOrb(
                    uiState = uiState,
                    emotion = activeEmotion,
                    micVolume = micVolume,
                    modifier = Modifier.size(150.dp)
                )

                // Subtitle/Status trace tag matching voice activity core
                val stateText = when (uiState) {
                    is ChatUiState.Thinking -> "THINKING..."
                    is ChatUiState.Speaking -> "SPEAKING..."
                    else -> if (micVolume > 0.05f) "LISTENING..." else "AURA CORE"
                }
                Text(
                    text = stateText,
                    color = activePrimaryC,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Active State Aura HUD Indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SpaceCardBg)
                .border(BorderStroke(2.dp, activeSecondaryC.copy(alpha = 0.5f)), RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val emoji = when (activeEmotion.uppercase()) {
                "WITTY" -> "⚡"
                "EMPATHETIC" -> "🌸"
                "ANALYTICAL" -> "🧠"
                "SURPRISED" -> "🔮"
                else -> "🧊"
            }
            Text(
                text = "$emoji  AURA: $activeEmotion",
                color = activePrimaryC,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.width(150.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = emotionReason,
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2.5 COMPANION AURA SELECTOR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, activeSecondaryC.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .background(SpaceCardBg.copy(alpha = 0.4f))
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val modes = listOf(
                "ROMANTIC" to "❤️ SOUL SYNC",
                "WITTY" to "⚡ BANTER",
                "INTELLECTUAL" to "🧠 MUSE",
                "CLASSIC" to "🧊 ASSIST"
            )
            modes.forEach { (modeKey, label) ->
                val isSelected = activeAuraMode == modeKey
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) activeTertiaryC.copy(alpha = 0.35f) else Color.Transparent)
                        .clickable { viewModel.setAuraMode(modeKey) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. TAB CONTROLLER SWITCHBAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(1.5.dp, activeSecondaryC.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(SpaceCardBg.copy(alpha = 0.8f))
        ) {
            val tabs = listOf(
                "TERMINAL" to "Terminal",
                "MEMORY_INDEX" to "Memory",
                "EVOLUTION" to "Skills",
                if (isProUnlocked) "PRO_CP" to "⚡ Myraa PRO" else "PRO_UPGRADE" to "Unlock PRO"
            )
            tabs.forEach { (key, label) ->
                val isSelected = currentTabSelected == key
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (isSelected) activeSecondaryC.copy(alpha = 0.3f) else Color.Transparent)
                        .clickable { currentTabSelected = key }
                        .testTag("tab_$key"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) activePrimaryC else TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. MAIN TAB SCREEN PANEL
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(2.dp, activeSecondaryC.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .background(SpaceCardBg.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            when (currentTabSelected) {
                "TERMINAL" -> {
                    // Terminal scrolling log of transactions
                    val listState = rememberLazyListState()
                    val scope = rememberCoroutineScope()

                    LaunchedEffect(terminalMsgs.size) {
                        scope.launch {
                            if (terminalMsgs.isNotEmpty()) {
                                listState.animateScrollToItem(terminalMsgs.size - 1)
                            }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("terminal_view"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(terminalMsgs) { msg ->
                            TerminalMsgRow(msg)
                        }
                    }
                }
                "MEMORY_INDEX" -> {
                    // Fact grid matrix and indexing controls with dynamic sync diagnostics
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(
                                "DYNAMIC COGNITIVE ASSOCIATIONS",
                                color = MyraaPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Purge database",
                                color = MyraaTertiary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .clickable { viewModel.clearAllMemories() }
                                    .testTag("purge_memories_btn")
                            )
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Item 1: Affection Diagnostics Sync Header Block
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp)
                                        .border(1.dp, MyraaTertiary.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                        .background(MyraaTertiary.copy(alpha = 0.08f))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        "💖 EMOTION SYNCHRONIZER DIAGNOSTICS",
                                        color = MyraaTertiary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text("Sensation Aura Level", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                            Text(
                                                text = when {
                                                    synergyLevel < 50 -> "ACQUAINTED COMPANION (परिचित साथी)"
                                                    synergyLevel < 75 -> "EMPATHIC PARTNER (सहानुभूतिपूर्ण दोस्त)"
                                                    synergyLevel < 90 -> "DEEP INTELLECTUAL MUSE (गहरी प्रेरणा)"
                                                    else -> "EVALUATED SOUL RESIDENCY (सच्चा हमसफ़र ❤️)"
                                                },
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("Aura Frequency", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                            Text(
                                                text = "FLUID // ${activeAuraMode.uppercase()}",
                                                color = MyraaPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Action button to trigger affection letter
                                    Button(
                                        onClick = {
                                            val loveDirective = "Create a deeply heartwarming, cute personal romantic note or status report for your partner (user) in Hinglish / beautiful Hindi to surprise them. Use their known facts/memories naturally if any. Be incredibly loyal, sweet, and caring."
                                            viewModel.sendMessage(loveDirective, geminiKey)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MyraaTertiary),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(38.dp)
                                            .testTag("synthesize_love_btn"),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(
                                            "✨ SYNTHESIZE AFFECTION NOTE (स्नेह संदेश)",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            // Memory Fact Items
                            if (memories.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "Memory Index Empty.\nSpeak/Chat about personal facts to store permanent records.",
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                items(memories) { memory ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(SpaceBackground)
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "[M]",
                                            color = MyraaPrimary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                        Text(
                                            memory.fact,
                                            color = TextWhite,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Forget fact",
                                            tint = MyraaTertiary.copy(alpha = 0.8f),
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { viewModel.deleteMemory(memory.id) }
                                                .testTag("forget_memory_${memory.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "EVOLUTION" -> {
                    // Plugin self evolution matrices
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Text(
                                "COGNITIVE PLUGINS LAYER",
                                color = MyraaPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { showEvolvePluginDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MyraaPrimary),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("evolve_btn")
                            ) {
                                Text(
                                    "Evolve Skill",
                                    color = SpaceBackground,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Render stable pre-configured capabilities first
                            item {
                                StaticPluginCard(
                                    title = "System Controller v3.0",
                                    desc = "Orchestrates local device volumes, brightness values, implicit internet intent redirection, and camera led power streams."
                                )
                            }
                            item {
                                StaticPluginCard(
                                    title = "Empathetic Vocalization Sync",
                                    desc = "Encodes sentiment structures and dynamic tone modulations onto the device TTS speech pipeline."
                                )
                            }

                            items(plugins) { plugin ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MyraaSecondary.copy(alpha = 0.15f))
                                        .padding(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "SKILL: ${plugin.name}",
                                            color = CyberGreen,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            plugin.description,
                                            color = TextWhite,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                        Text(
                                            text = plugin.codeSnippet,
                                            color = Color(0xFFA5D6A7),
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 10.sp,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.Black.copy(alpha = 0.5f))
                                                .padding(6.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Decouple skill",
                                        tint = MyraaTertiary,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { viewModel.purgePlugin(plugin.id) }
                                            .testTag("decouple_plugin_${plugin.id}")
                                    )
                                }
                            }
                        }
                    }
                }
                "PRO_UPGRADE" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "✨ UNLOCK MYRAA PRO (मायरा प्रो)",
                            color = Color(0xFFFFD700),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        Text(
                            text = "Elevate Myraa into a super-intelligent quantum companion.",
                            color = TextWhite,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
                        )

                        ProFeatureRow(
                            icon = "🔮",
                            title = "Advanced Creative Models",
                            subtitle = "Switch between Gemini 1.5 Pro and Deep Reasoning architectures."
                        )
                        ProFeatureRow(
                            icon = "🎨",
                            title = "Cosmic Theme Synchronizer",
                            subtitle = "Instantly adapt the entire application to Golden HUD or Nebula Purple."
                        )
                        ProFeatureRow(
                            icon = "🎙️",
                            title = "Custom Vocal Tuning Synthesizer",
                            subtitle = "Modify Myraa's voice pitch and speaking speed to match your ideal tone."
                        )
                        ProFeatureRow(
                            icon = "🧠",
                            title = "100% Intimacy Synergy Index",
                            subtitle = "Unlocks the deepest empathetic prompt intelligence matrix."
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                viewModel.setProUnlocked(true)
                                currentTabSelected = "PRO_CP"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .border(1.5.dp, Color(0xFFFFD700), RoundedCornerShape(12.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(Color(0xFFD4AF37), Color(0xFFFF8F00))
                                    )
                                )
                                .testTag("activate_pro_btn")
                        ) {
                            Text(
                                "PRO ACTIVATE: 100% UNLOCKED FREE",
                                color = SpaceBackground,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                "PRO_CP" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        ) {
                            Text(
                                "QUANTUM SYSTEM PANEL",
                                color = Color(0xFFFFD700),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFFD700).copy(alpha = 0.15f))
                                    .border(1.dp, Color(0xFFFFD700), RoundedCornerShape(4.dp))
                                    .clickable { viewModel.setProUnlocked(false); currentTabSelected = "TERMINAL" }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "DEACTIVATE PRO",
                                    color = Color(0xFFFFD700),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            "SELECT ACTIVE COGNITIVE BRAIN CORE",
                            color = activePrimaryC,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
                        )
                        val models = listOf(
                            "MYRAA_CORE" to "Myraa Fast-Stream (Gemini 1.5 Flash)",
                            "MYRAA_ULTRA_PRO" to "Myraa Ultra Pro (Gemini 1.5 Pro // Advanced)",
                            "MYRAA_DEEP_REASONING" to "Myraa Deep Reasoning (Gemini Experimental)"
                        )
                        models.forEach { (modelKey, label) ->
                            val isSelected = activeProModel == modelKey
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) activePrimaryC.copy(alpha = 0.15f) else SpaceBackground.copy(alpha = 0.5f))
                                    .border(1.dp, if (isSelected) activePrimaryC else SpaceCardBg, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.setProModel(modelKey) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.setProModel(modelKey) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = activePrimaryC,
                                        unselectedColor = TextMuted
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.White else TextMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "VOCAL FREQUENCY SYNTHESIZER PITCH",
                            color = activePrimaryC,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${String.format("%.1f", voicePitch)}x", color = activeTertiaryC, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(35.dp))
                            Slider(
                                value = voicePitch,
                                onValueChange = { viewModel.setVoicePitch(it) },
                                valueRange = 0.5f..2.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = activeTertiaryC,
                                    activeTrackColor = activeSecondaryC,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text(
                            "VOCAL SPEED TEMPO RATE",
                            color = activePrimaryC,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${String.format("%.1f", voiceSpeed)}x", color = activeTertiaryC, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(35.dp))
                            Slider(
                                value = voiceSpeed,
                                onValueChange = { viewModel.setVoiceSpeed(it) },
                                valueRange = 0.5f..2.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = activeTertiaryC,
                                    activeTrackColor = activeSecondaryC,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Button(
                            onClick = {
                                viewModel.sendMessage("Please utter a short friendly hello with the current voice configuration.", geminiKey)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SpaceBackground),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .fillMaxWidth()
                                .height(34.dp)
                                .border(1.5.dp, activeTertiaryC.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                "🎙️ SYNTHESIZE VOCAL TONE TEST",
                                color = activeTertiaryC,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "SELECT QUANTUM HUD GLOW",
                            color = activePrimaryC,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val themes = listOf(
                                "GOLD" to "💛 Imperial Gold",
                                "NEBULA" to "💜 Cosmic Nebula",
                                "CYAN" to "🩵 Cyber Cyan"
                            )
                            themes.forEach { (themeKey, label) ->
                                val isSelected = selectedProTheme == themeKey
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) activePrimaryC.copy(alpha = 0.2f) else SpaceCardBg)
                                        .border(1.dp, if (isSelected) activePrimaryC else SpaceCardBg, RoundedCornerShape(8.dp))
                                        .clickable { selectedProTheme = themeKey }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else TextMuted,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "HYPER-RESONANCE SYNAPSE LINE (ECG DETECTOR)",
                            color = activePrimaryC,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.4f))
                                .border(1.dp, activeSecondaryC.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val width = size.width
                                val height = size.height
                                val path = androidx.compose.ui.graphics.Path()
                                val points = 100
                                val amplitudeMultiplier = if (micVolume > 0.05f) 2.5f else 1.0f
                                
                                path.moveTo(0f, height / 2f)
                                for (i in 0..points) {
                                    val x = (i.toFloat() / points) * width
                                    val sineTerm = kotlin.math.sin((i * 0.25f) * voicePitch)
                                    val cosineTerm = kotlin.math.cos((i * 0.1f) * voiceSpeed)
                                    val y = (height / 2f) + (sineTerm * cosineTerm * 12f * amplitudeMultiplier)
                                    path.lineTo(x, y)
                                }
                                drawPath(
                                    path = path,
                                    color = activePrimaryC,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                )
                            }
                        }
                    }
                }
            }
        }

        // Action Executed Feedback Indicator
        AnimatedVisibility(
            visible = latestAction != null,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CyberGreen.copy(alpha = 0.2f))
                    .border(BorderStroke(1.dp, CyberGreen), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Fulfillment active",
                    tint = CyberGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AUTOMATION FULFILLED: $latestAction",
                    color = CyberGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 5. INPUT COMMAND PANEL DECK (Footer)
        if (uiState is ChatUiState.Speaking || isSpeaking) {
            // Button to intercept synthesis stream
            Button(
                onClick = onStopSpeakingClick,
                colors = ButtonDefaults.buttonColors(containerColor = MyraaTertiary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("stop_speaking_btn")
            ) {
                Text("⏹️  INTERRUPT SIGNALSTREAM", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = userText,
                    onValueChange = { userText = it },
                    placeholder = {
                        Text(
                            text = sttStatus,
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("text_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MyraaPrimary,
                        unfocusedBorderColor = MyraaSecondary.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    trailingIcon = {
                        if (userText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    viewModel.sendMessage(userText, geminiKey)
                                    userText = ""
                                },
                                modifier = Modifier.testTag("submit_btn")
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send text", tint = MyraaPrimary)
                            }
                        }
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Microphone activation trigger
                FloatingActionButton(
                    onClick = onVoiceClick,
                    containerColor = if (isListening) MyraaTertiary else MyraaPrimary,
                    contentColor = SpaceBackground,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("mic_fab")
                ) {
                    Text(
                        text = if (isListening) "⏹️" else "🎙️",
                        fontSize = 20.sp
                    )
                }
            }
        }
    }

    // Dynamic Module compiler Dialog drawer
    if (showEvolvePluginDialog) {
        var pName by remember { mutableStateOf("") }
        var pPurpose by remember { mutableStateOf("") }

        Dialog(onDismissRequest = { showEvolvePluginDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SpaceCardBg),
                border = BorderStroke(1.dp, MyraaPrimary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "[COGNITIVE EVOLUTION ENGINE]",
                        color = MyraaPrimary,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Describe a function block, and Myraa will synthesize custom scripts to execute it dynamically.",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = TextAlign.Center
                    )

                    OutlinedTextField(
                        value = pName,
                        onValueChange = { pName = it },
                        label = { Text("Module Identifier", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = MyraaPrimary,
                            unfocusedBorderColor = MyraaSecondary.copy(alpha = 0.5f)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("add_plugin_name")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = pPurpose,
                        onValueChange = { pPurpose = it },
                        label = { Text("Directive & Functionality", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = MyraaPrimary,
                            unfocusedBorderColor = MyraaSecondary.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("add_plugin_desc")
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = { showEvolvePluginDialog = false },
                            modifier = Modifier.testTag("cancel_evolve_btn")
                        ) {
                            Text("ABORT", color = MyraaTertiary, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (pName.isNotBlank() && pPurpose.isNotBlank()) {
                                    viewModel.forceTweakEvolution(pName, pPurpose, geminiKey)
                                    showEvolvePluginDialog = false
                                } else {
                                    Toast.makeText(context, "All compilation parameters must be populated.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MyraaPrimary),
                            modifier = Modifier.testTag("submit_evolve_btn")
                        ) {
                            Text("COMPILE & MAP", color = SpaceBackground, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}


}

