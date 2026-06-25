package com.example.data.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MyraaRepository(
    private val memoryDao: MemoryDao,
    private val pluginDao: PluginDao,
    private val conversationMessageDao: ConversationMessageDao,
    private val userPreferenceDao: UserPreferenceDao
) {
    val allMemories: Flow<List<Memory>> = memoryDao.getAllMemoriesFlow()
    val allPlugins: Flow<List<Plugin>> = pluginDao.getAllPluginsFlow()
    val allMessages: Flow<List<ConversationMessage>> = conversationMessageDao.getAllMessagesFlow()
    val allPreferences: Flow<List<UserPreference>> = userPreferenceDao.getAllPreferencesFlow()

    suspend fun getMemoriesByCategory(category: String): List<Memory> = withContext(Dispatchers.IO) {
        memoryDao.getMemoriesByCategory(category)
    }

    suspend fun insertMemory(category: String, fact: String) = withContext(Dispatchers.IO) {
        memoryDao.insertMemory(Memory(category = category, fact = fact))
    }

    suspend fun deleteMemory(id: Int) = withContext(Dispatchers.IO) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun clearAllMemories() = withContext(Dispatchers.IO) {
        memoryDao.clearAllMemories()
    }

    suspend fun insertPlugin(name: String, description: String, codeSnippet: String) = withContext(Dispatchers.IO) {
        pluginDao.insertPlugin(Plugin(name = name, description = description, codeSnippet = codeSnippet))
    }

    suspend fun togglePluginActive(plugin: Plugin) = withContext(Dispatchers.IO) {
        pluginDao.updatePlugin(plugin.copy(isActive = !plugin.isActive))
    }

    suspend fun deletePlugin(id: Int) = withContext(Dispatchers.IO) {
        pluginDao.deletePluginById(id)
    }

    suspend fun insertMessage(sender: String, text: String, emotion: String) = withContext(Dispatchers.IO) {
        conversationMessageDao.insertMessage(ConversationMessage(sender = sender, text = text, emotion = emotion))
    }

    suspend fun clearAllMessages() = withContext(Dispatchers.IO) {
        conversationMessageDao.clearAllMessages()
    }

    suspend fun getPreference(key: String): String? = withContext(Dispatchers.IO) {
        userPreferenceDao.getPreferenceByKey(key)?.value
    }

    suspend fun savePreference(key: String, value: String) = withContext(Dispatchers.IO) {
        userPreferenceDao.insertPreference(UserPreference(key = key, value = value))
    }

    suspend fun deletePreference(key: String) = withContext(Dispatchers.IO) {
        userPreferenceDao.deletePreferenceByKey(key)
    }
}
