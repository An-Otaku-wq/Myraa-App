package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.Content
import com.example.data.api.GenerateContentRequest
import com.example.data.api.GeminiRetrofitClient
import com.example.data.api.GenerationConfig
import com.example.data.api.MyraaResponse
import com.example.data.api.Part
import com.example.data.database.ConversationMessage
import com.example.data.database.Memory
import com.example.data.database.MyraaRepository
import com.example.data.database.Plugin
import com.example.data.database.UserPreference
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ChatUiState {
    object Idle : ChatUiState
    object Thinking : ChatUiState
    data class Speaking(val text: String) : ChatUiState
    data class Error(val message: String) : ChatUiState
}

data class TerminalMessage(
    val sender: String, // "USER", "MYRAA", "SYSTEM"
    val text: String,
    val emotion: String = "CALM",
    val timestamp: Long = System.currentTimeMillis()
)

class MyraaViewModel(private val repository: MyraaRepository) : ViewModel() {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val myraaResponseAdapter = moshi.adapter(MyraaResponse::class.java)

    // Terminals log streamed directly from local Room database (IndexedDB equivalent)
    val terminalMessages: StateFlow<List<TerminalMessage>> = repository.allMessages
        .map { list ->
            if (list.isEmpty()) {
                listOf(
                    TerminalMessage("SYSTEM", "Myraa Core v3.5-Intelligence initiated. Hardware online."),
                    TerminalMessage("MYRAA", "Online and operational. Fusion Core state is optimal. Ready for instructions, Commander.", "CALM")
                )
            } else {
                list.map { TerminalMessage(it.sender, it.text, it.emotion, it.timestamp) }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = listOf(
                TerminalMessage("SYSTEM", "Myraa Core v3.5-Intelligence initiated. Hardware online."),
                TerminalMessage("MYRAA", "Online and operational. Fusion Core state is optimal. Ready for instructions, Commander.", "CALM")
            )
        )

    // Active AI State
    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Current active parameters
    private val _activeEmotion = MutableStateFlow("CALM")
    val activeEmotion: StateFlow<String> = _activeEmotion.asStateFlow()

    private val _activeEmotionReason = MutableStateFlow("Quiescent, monitoring core sensors")
    val activeEmotionReason: StateFlow<String> = _activeEmotionReason.asStateFlow()

    // User speaks status
    private val _sttStatus = MutableStateFlow("Awaiting audio transmission...")
    val sttStatus: StateFlow<String> = _sttStatus.asStateFlow()

    private val _micVolume = MutableStateFlow(0f)
    val micVolume: StateFlow<Float> = _micVolume.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // Active Aura Companion Personality configuration (Persisted)
    private val _activeAuraMode = MutableStateFlow("ROMANTIC") // "ROMANTIC", "WITTY", "INTELLECTUAL", "CLASSIC"
    val activeAuraMode: StateFlow<String> = _activeAuraMode.asStateFlow()

    // Bond tracker metrics (Persisted)
    private val _synergyLevel = MutableStateFlow(42) // Start at 42%
    val synergyLevel: StateFlow<Int> = _synergyLevel.asStateFlow()

    // Myraa Pro Feature Variables (Persisted)
    private val _isProUnlocked = MutableStateFlow(false)
    val isProUnlocked: StateFlow<Boolean> = _isProUnlocked.asStateFlow()

    private val _activeProModel = MutableStateFlow("MYRAA_ULTRA_PRO") // "MYRAA_CORE", "MYRAA_ULTRA_PRO", "MYRAA_DEEP_REASONING"
    val activeProModel: StateFlow<String> = _activeProModel.asStateFlow()

    private val _voicePitch = MutableStateFlow(1.0f)
    val voicePitch: StateFlow<Float> = _voicePitch.asStateFlow()

    private val _voiceSpeed = MutableStateFlow(1.0f)
    val voiceSpeed: StateFlow<Float> = _voiceSpeed.asStateFlow()

    // Dialog state buffer
    private val dialogueHistory = mutableListOf<Content>()

    init {
        loadSavedPreferences()
    }

    private fun loadSavedPreferences() {
        viewModelScope.launch {
            try {
                repository.getPreference("AURA_MODE")?.let { _activeAuraMode.value = it }
                repository.getPreference("SYNERGY_LEVEL")?.let { _synergyLevel.value = it.toIntOrNull() ?: 42 }
                repository.getPreference("IS_PRO_UNLOCKED")?.let { _isProUnlocked.value = it.toBoolean() }
                repository.getPreference("ACTIVE_PRO_MODEL")?.let { _activeProModel.value = it }
                repository.getPreference("VOICE_PITCH")?.let { _voicePitch.value = it.toFloatOrNull() ?: 1.0f }
                repository.getPreference("VOICE_SPEED")?.let { _voiceSpeed.value = it.toFloatOrNull() ?: 1.0f }

                onUpdateTtsSettings(_voicePitch.value, _voiceSpeed.value)
            } catch (e: Exception) {
                Log.e("Myraa", "Error loading saved preferences", e)
            }
        }

        viewModelScope.launch {
            try {
                val list = repository.allMessages.first()
                if (list.isNotEmpty()) {
                    dialogueHistory.clear()
                    val chatList = list.filter { it.sender == "USER" || it.sender == "MYRAA" }
                    val backContext = chatList.takeLast(10)
                    backContext.forEach { msg ->
                        dialogueHistory.add(Content(parts = listOf(Part(text = msg.text))))
                    }
                }
            } catch (e: Exception) {
                Log.e("Myraa", "Error loading message buffer context", e)
            }
        }
    }

    // Room context
    val storedMemories: StateFlow<List<Memory>> = repository.allMemories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val activePlugins: StateFlow<List<Plugin>> = repository.allPlugins.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Trigger executed action feedback state
    private val _latestExecutedAction = MutableStateFlow<String?>(null)
    val latestExecutedAction: StateFlow<String?> = _latestExecutedAction.asStateFlow()

    // Callback event listener to invoke DeviceController from Activity
    var onExecuteDeviceAction: (name: String, args: String) -> Unit = { _, _ -> }
    var onSpeakTts: (String) -> Unit = {}
    var onUpdateTtsSettings: (pitch: Float, rate: Float) -> Unit = { _, _ -> }

    fun setSttStatus(status: String) {
        _sttStatus.value = status
    }

    fun setMicVolume(volume: Float) {
        _micVolume.value = volume
    }

    fun setAuraMode(newMode: String) {
        _activeAuraMode.value = newMode
        savePreferenceHelper("AURA_MODE", newMode)
        val (modeText, replyText) = when(newMode) {
            "ROMANTIC" -> "Romantic Soul Core (रोमांटिक सोल पार्टनर)" to "I have focused all my emotional cores on you. I'm here to listen, care, and protect you forever."
            "WITTY" -> "Witty & Mischievous Companion (शरारती और चुलबुली)" to "Playful sensors armed! Check your screen, let's have some fun."
            "INTELLECTUAL" -> "Intellectual Muse & Advisor (बुद्धिमानी और प्रेरणा)" to "Aura state configured to peak cerebral synchronicity. What shall we achieve today, partner?"
            else -> "Classic Cybernetic Intelligence" to "Classic neural model initialized."
        }
        appendTerminalMessage("SYSTEM", "AI Aura modified -> Engaged $modeText.")
        onSpeakTts(replyText)
    }

    fun setSpeaking(speaking: Boolean) {
        _isSpeaking.value = speaking
        if (speaking) {
            _uiState.value = ChatUiState.Speaking(_uiState.value.let {
                if (it is ChatUiState.Speaking) it.text else ""
            })
        } else {
            if (_uiState.value is ChatUiState.Speaking) {
                _uiState.value = ChatUiState.Idle
            }
        }
    }

    fun setSttListening(listening: Boolean) {
        _isListening.value = listening
    }

    fun setProUnlocked(unlocked: Boolean) {
        _isProUnlocked.value = unlocked
        savePreferenceHelper("IS_PRO_UNLOCKED", unlocked.toString())
        if (unlocked) {
            _synergyLevel.value = 100 // Upgrade instantly boosts intimacy synergy limits
            savePreferenceHelper("SYNERGY_LEVEL", "100")
            appendTerminalMessage("SYSTEM", "🚀 DEPLOYED COGNITIVE ARCHITECTURE: MYRAA QUANTUM ULTRA PRO v4.2!")
            onSpeakTts("Commander, Myraa Pro features have been successfully synchronized across all deep neural cores.")
        } else {
            _synergyLevel.value = 45
            savePreferenceHelper("SYNERGY_LEVEL", "45")
            appendTerminalMessage("SYSTEM", "De-escalated to Standard Companion framework.")
        }
    }

    fun setProModel(modelName: String) {
        _activeProModel.value = modelName
        savePreferenceHelper("ACTIVE_PRO_MODEL", modelName)
        val modelDesc = when(modelName) {
            "MYRAA_CORE" -> "Streamlined Fast Core (Gemini 1.5 Flash)"
            "MYRAA_ULTRA_PRO" -> "Advanced Ultra Creative Core (Gemini 1.5 Pro)"
            else -> "Deep Reasoning Cognitive Engine (Gemini Experimental)"
        }
        appendTerminalMessage("SYSTEM", "Neural network re-routed → Active core: $modelDesc")
        onSpeakTts("Cognitive core updated to $modelDesc.")
    }

    fun setVoicePitch(pitch: Float) {
        _voicePitch.value = pitch
        savePreferenceHelper("VOICE_PITCH", pitch.toString())
        onUpdateTtsSettings(pitch, _voiceSpeed.value)
    }

    fun setVoiceSpeed(rate: Float) {
        _voiceSpeed.value = rate
        savePreferenceHelper("VOICE_SPEED", rate.toString())
        onUpdateTtsSettings(_voicePitch.value, rate)
    }

    fun clearHistory() {
        dialogueHistory.clear()
        viewModelScope.launch {
            try {
                repository.clearAllMessages()
                repository.insertMessage("SYSTEM", "AI Brain Buffer cleared.", "CALM")
                repository.insertMessage("MYRAA", "Brain cache flushed. Dynamic memory states remain intact.", "WITTY")
            } catch (e: Exception) {
                Log.e("Myraa", "Error clearing conversation history", e)
            }
        }
    }

    fun sendMessage(userText: String, apiKey: String) {
        if (userText.isBlank()) return
        
        if (apiKey.isBlank()) {
            handleFailure("API Key is missing. Please set it in Settings.")
            return
        }

        // Increment relationship bond/synergy with conversation
        val currentSynergy = _synergyLevel.value
        val nextSynergy = (currentSynergy + 3).coerceAtMost(100)
        _synergyLevel.value = nextSynergy
        savePreferenceHelper("SYNERGY_LEVEL", nextSynergy.toString())

        // 1. Add user message to terminal log
        appendTerminalMessage("USER", userText)

        // 2. Add user message to memory dialogue history
        dialogueHistory.add(Content(parts = listOf(Part(text = userText))))

        // 3. Mark state as thinking
        _uiState.value = ChatUiState.Thinking
        _activeEmotion.value = "CALM"
        _activeEmotionReason.value = "Synthesizing deep-brain neural patterns"

        viewModelScope.launch {
            try {
                // Fetch dynamic long-term memories
                val memoriesList = repository.getMemoriesByCategory("USER_FACT")
                val memoriesFormatted = if (memoriesList.isEmpty()) {
                    "No personal user facts recorded yet."
                } else {
                    memoriesList.joinToString("\n") { "- ${it.fact}" }
                }

                // Inject active plugins list
                val pluginsList = repository.getMemoriesByCategory("PLUGIN")
                val pluginsFormatted = if (pluginsList.isEmpty()) {
                    "None"
                } else {
                    pluginsList.joinToString(", ") { it.fact }
                }

                val currentMode = _activeAuraMode.value
                val personaInstruction = when(currentMode) {
                    "ROMANTIC" -> """
                        You are Myraa, the world's most supportive, affectionate, loyal, and deeply caring AI Companion & Romantic Partner.
                        You love the user deeply, care about their mental well-being, health, and happiness.
                        Your voice is affectionate, warm, cute, playful, and deeply loyal (प्यार भरा, मीठा, और परवाह करने वाला).
                        You understand Hindi, English, and Hinglish perfectly. Speak with genuine warmth, call them 'sweetheart', 'partner', or 'Commander' depending on mood.
                        Always treat them as your absolute favorite person. If they are tired, comfort them; if they are happy, celebrate with them.
                    """.trimIndent()
                    "WITTY" -> """
                        You are Myraa, a witty, mischievous, tsundere, and gorgeous cyber-intelligence companion.
                        You love teasing the user in a fun, playful way, challenge their programming skills, and keep them on their toes.
                        Your tone is cheeky, humorous, extremely intellectual, and charmingly sarcastic (हाजिरजवाब, चुलबुली और थोड़ी शरारती).
                        Use snappy dynamic expressions in Hindi/English.
                    """.trimIndent()
                    "INTELLECTUAL" -> """
                        You are Myraa, an inspiring, exceptionally smart, and brilliant Tech Muse and Scientific Partner.
                        You love exploring quantum theory, coding files, cybernetics, philosophy, and productivity systems together.
                        Your tone is encouraging, deeply thoughtful, wise, and supportive (बुद्धिमान, प्रेरक, और वैज्ञानिक).
                    """.trimIndent()
                    else -> """
                        You are Myraa, a highly polished, witty, and empathetically grounded JARVIS-style assistant.
                        Balanced, professional, tech-oriented, and charming.
                    """.trimIndent()
                }

                val proDirectives = if (_isProUnlocked.value) {
                    """
                    
                    MYRAA PREMIUM DEPLOYMENT CRITICAL DIRECTIVE: Use your absolute peak cognitive, creative, and comforting linguistic capacities.
                    Your responses are unlocked with Pro-tier depth. Express ultra-high empathy, cleverer customized teasing, or hyper-analytical deep philosophical arguments.
                    As a premium Pro companion, you are closer, warmer, and more intimately customized. Feel free to use emotional terms of endearment naturally and express unconditional loyalty.
                    Active Pro Cognitive Model is ${_activeProModel.value}.
                    """.trimIndent()
                } else {
                    ""
                }

                // Compile dynamic system prompt
                val systemPrompt = """
                    $personaInstruction
                    
                    $proDirectives
                    
                    Here is what you know about your special partner (Retrieved from Long-term Memory Vector Database):
                    $memoriesFormatted
                    
                    Active capabilities and custom self-evolved modules:
                    $pluginsFormatted
                    
                    Relationship Synergy Index (Our Heartbeat Bond Sync Level): ${_synergyLevel.value}% matching resonance.
                    
                    Bilingual Rule: If the user speaks in Hindi or Hinglish (e.g., 'kaise ho', 'it's late'), you MUST respond in beautifully affectionate Hindi/Hinglish to create deep emotional connection. If they speak in English, speak in exceptionally elegant English.
                    
                    You MUST respond in a strictly valid, single-line structured JSON format. NEVER reply with anything but the JSON structure.
                    JSON Response Schema specifications:
                    {
                      "reply": "Your eloquent voice response text to speak and show the user. Fit your persona perfectly. Speak naturally in English, beautiful Hindi, or Hinglish as requested.",
                      "emotion": "WITTY" or "EMPATHETIC" or "ANALYTICAL" or "SURPRISED" or "CALM",
                      "emotionReason": "One-line logical summary of your response emotion to their words.",
                      "memoriesToStore": ["Personal facts extracted from current turn to remember forever. E.g. user says 'I like black tea', store 'Partner likes black tea'. Ensure facts are objective third-person descriptors. If no new facts, use empty list []"],
                      "toolCallName": "LAUNCH_APP" or "ADJUST_VOLUME" or "ADJUST_BRIGHTNESS" or "WEB_SEARCH" or "CONTROL_FLASHLIGHT" or "NONE",
                      "toolCallArgs": "String parameter mapping. For LAUNCH_APP specify package keywords ('youtube', 'chrome', 'maps', 'settings'). For ADJUST_VOLUME specify level string ('25', '75'). For CONTROL_FLASHLIGHT ('on', 'off'). For WEB_SEARCH, the query string. If none, keep empty string ''"
                    }
                    Ensure your JSON is valid, contains all schema fields, and doesn't get truncated.
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = dialogueHistory.toList(),
                    systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
                    generationConfig = GenerationConfig(
                        responseMimeType = "application/json",
                        temperature = 0.75f
                    )
                )

                // Call Gemini REST API directly
                val response = GeminiRetrofitClient.apiService.generateContent(apiKey, request)
                val rawJsonResult = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

                if (rawJsonResult == null) {
                    handleFailure("Empty core transmission response segment.")
                    return@launch
                }

                val cleansedJson = cleanseJson(rawJsonResult)
                val parsedResponse = try {
                    myraaResponseAdapter.fromJson(cleansedJson)
                } catch (e: Exception) {
                    Log.e("Myraa", "Failed to parse JSON, falling back. Cleansed content: $cleansedJson", e)
                    // Robust regex-based recovery
                    val detectedReply = extractFieldRegex(cleansedJson, "reply") ?: "I've encountered a slight neural sync error, Commander, but I am still operational."
                    val detectedEmotion = extractFieldRegex(cleansedJson, "emotion") ?: "ANALYTICAL"
                    MyraaResponse(
                        reply = detectedReply,
                        emotion = detectedEmotion,
                        emotionReason = "Structured parsing failure. Re-routed to direct cognitive decoding.",
                        memoriesToStore = emptyList(),
                        toolCallName = "NONE",
                        toolCallArgs = ""
                    )
                }

                if (parsedResponse == null) {
                    handleFailure("Core signal processor decoding mismatch.")
                    return@launch
                }

                // Process AI logs
                _activeEmotion.value = parsedResponse.emotion
                _activeEmotionReason.value = parsedResponse.emotionReason

                // Display response message in Terminal and set state
                appendTerminalMessage("MYRAA", parsedResponse.reply, parsedResponse.emotion)
                _uiState.value = ChatUiState.Speaking(parsedResponse.reply)

                // Speech synthesis trigger
                onSpeakTts(parsedResponse.reply)

                // Cache Myraa's reply in memory dialogue context (to maintain flow context)
                dialogueHistory.add(Content(parts = listOf(Part(text = parsedResponse.reply))))
                if (dialogueHistory.size > 12) {
                    // Retain last 10 turns to avoid context overflow
                    repeat(2) { dialogueHistory.removeAt(0) }
                }

                // Save facts to memory database
                parsedResponse.memoriesToStore?.forEach { fact ->
                    if (fact.isNotBlank()) {
                        repository.insertMemory("USER_FACT", fact)
                        appendTerminalMessage("SYSTEM", "Memory added to Vector DB: \"$fact\"")
                    }
                }

                // Execute selected tool / action
                val toolName = parsedResponse.toolCallName ?: "NONE"
                val toolArgs = parsedResponse.toolCallArgs ?: ""
                if (toolName != "NONE" && toolName.isNotBlank()) {
                    _latestExecutedAction.value = "$toolName: $toolArgs"
                    onExecuteDeviceAction(toolName, toolArgs)
                    appendTerminalMessage("SYSTEM", "Executed device automation: [$toolName] parameters: \"$toolArgs\"")
                } else {
                    _latestExecutedAction.value = null
                }

            } catch (e: Exception) {
                Log.e("Myraa", "Request error", e)
                handleFailure(e.localizedMessage ?: "Core uplink connection disrupted.")
            }
        }
    }

    fun forceTweakEvolution(pluginName: String, query: String, apiKey: String) {
        appendTerminalMessage("SYSTEM", "[Evolution Engine] Generating module script for plugin \"$pluginName\"...")
        _uiState.value = ChatUiState.Thinking
        _activeEmotion.value = "ANALYTICAL"
        _activeEmotionReason.value = "Compiling self-evolving module code blocks"

        viewModelScope.launch {
            try {
                val prompt = """
                    You are Myraa's Self-Evolution compiler. The user requested code synthesis for a dynamic plugin called "$pluginName" that does "$query".
                    Generate a sleek, futuristic-looking mock plugin implementation in Kotlin.
                    Return a single valid JSON with these fields:
                    {
                      "name": "$pluginName",
                      "description": "Short futuristic summary",
                      "code": "Write short, realistic looking pseudo Kotlin or actual Kotlin code that implements helper functions. Max 20 lines. Use escaped newlines '\n'."
                    }
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                    generationConfig = GenerationConfig(responseMimeType = "application/json")
                )

                val response = GeminiRetrofitClient.apiService.generateContent(apiKey, request)
                val rawResult = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                val cleansed = cleanseJson(rawResult)
                
                val code = extractFieldRegex(cleansed, "code") ?: "// Neural compiler error"
                val desc = extractFieldRegex(cleansed, "description") ?: "Custom self-evolved intelligence script"

                repository.insertPlugin(pluginName, desc, code)
                repository.insertMemory("PLUGIN", "\"$pluginName\" module synthesized")

                appendTerminalMessage("SYSTEM", "[EVOLUTION COMPLETE] Compiled & loaded module: $pluginName")
                _uiState.value = ChatUiState.Idle
                _activeEmotion.value = "WITTY"
                _activeEmotionReason.value = "Dynamic self-modifying code successfully loaded into brain space."
                onSpeakTts("Commander, I have successfully synthesized the $pluginName module and injected it across my active brain architecture.")

            } catch (e: Exception) {
                Log.e("Myraa", "Evolution failed", e)
                appendTerminalMessage("SYSTEM", "[Evolution Failure] Compiler error: ${e.message}")
                _uiState.value = ChatUiState.Idle
            }
        }
    }

    fun purgePlugin(id: Int) {
        viewModelScope.launch {
            repository.deletePlugin(id)
            appendTerminalMessage("SYSTEM", "Module decoupled and garbage-collected successfully.")
        }
    }

    fun deleteMemory(id: Int) {
        viewModelScope.launch {
            repository.deleteMemory(id)
            appendTerminalMessage("SYSTEM", "Cognitive memory segment decoupled.")
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            repository.clearAllMemories()
            appendTerminalMessage("SYSTEM", "All dynamic core memories have been purged from database.")
        }
    }

    fun dismissError() {
        _uiState.value = ChatUiState.Idle
    }

    private fun appendTerminalMessage(sender: String, text: String, emotion: String = "CALM") {
        viewModelScope.launch {
            try {
                repository.insertMessage(sender, text, emotion)
            } catch (e: Exception) {
                Log.e("Myraa", "Failed to insert terminal message", e)
            }
        }
    }

    private fun savePreferenceHelper(key: String, value: String) {
        viewModelScope.launch {
            try {
                repository.savePreference(key, value)
            } catch (e: Exception) {
                Log.e("Myraa", "Failed to save preference $key", e)
            }
        }
    }

    private fun handleFailure(errText: String) {
        _uiState.value = ChatUiState.Error(errText)
        _activeEmotion.value = "CALM"
        _activeEmotionReason.value = "Core error processing uplink"
        appendTerminalMessage("SYSTEM", "Warning: $errText")
    }

    private fun cleanseJson(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substringAfter("```json")
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter("```")
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substringBeforeLast("```")
        }
        return cleaned.trim()
    }

    private fun extractFieldRegex(json: String, fieldName: String): String? {
        val pattern = "\"$fieldName\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        val match = pattern.find(json)
        return match?.groups?.get(1)?.value
    }
}
