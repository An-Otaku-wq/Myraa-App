package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val category: String, // "USER_FACT", "CONTEXT", "SYSTEM"
    val fact: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "plugins")
data class Plugin(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String,
    val codeSnippet: String, // Simulated generated plugin code
    val isActive: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<Memory>>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY timestamp DESC")
    suspend fun getMemoriesByCategory(category: String): List<Memory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: Memory)

    @Delete
    suspend fun deleteMemory(memory: Memory)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Int)

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()
}

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY timestamp DESC")
    fun getAllPluginsFlow(): Flow<List<Plugin>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlugin(plugin: Plugin)

    @Update
    suspend fun updatePlugin(plugin: Plugin)

    @Query("DELETE FROM plugins WHERE id = :id")
    suspend fun deletePluginById(id: Int)
}

@Entity(tableName = "conversation_messages")
data class ConversationMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sender: String,
    val text: String,
    val emotion: String = "CALM",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_preferences")
data class UserPreference(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface ConversationMessageDao {
    @Query("SELECT * FROM conversation_messages ORDER BY timestamp ASC")
    fun getAllMessagesFlow(): Flow<List<ConversationMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ConversationMessage)

    @Query("DELETE FROM conversation_messages")
    suspend fun clearAllMessages()
}

@Dao
interface UserPreferenceDao {
    @Query("SELECT * FROM user_preferences")
    fun getAllPreferencesFlow(): Flow<List<UserPreference>>

    @Query("SELECT * FROM user_preferences WHERE `key` = :key LIMIT 1")
    suspend fun getPreferenceByKey(key: String): UserPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreference(preference: UserPreference)

    @Query("DELETE FROM user_preferences WHERE `key` = :key")
    suspend fun deletePreferenceByKey(key: String)
}

@Database(entities = [Memory::class, Plugin::class, ConversationMessage::class, UserPreference::class], version = 2, exportSchema = false)
abstract class MyraaDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun pluginDao(): PluginDao
    abstract fun conversationMessageDao(): ConversationMessageDao
    abstract fun userPreferenceDao(): UserPreferenceDao
}
