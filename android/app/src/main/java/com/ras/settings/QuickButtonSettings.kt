package com.ras.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ras.data.terminal.AVAILABLE_QUICK_BUTTONS
import com.ras.data.terminal.DEFAULT_QUICK_BUTTONS
import com.ras.data.terminal.QuickButton
import com.ras.proto.KeyType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.quickButtonDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quick_buttons"
)

/**
 * Persists quick button configuration using DataStore.
 *
 * Buttons are stored as JSON to allow flexibility in button configuration
 * without requiring schema changes.
 */
@Singleton
class QuickButtonSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "QuickButtonSettings"
        private val BUTTONS_KEY = stringPreferencesKey("buttons_json")
    }

    /**
     * Get the current quick button configuration.
     * Returns default buttons if none configured.
     */
    fun getButtons(): List<QuickButton> {
        return runBlocking {
            try {
                val prefs = context.quickButtonDataStore.data.first()
                val json = prefs[BUTTONS_KEY]
                if (json != null) {
                    parseButtons(json)
                } else {
                    DEFAULT_QUICK_BUTTONS
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load buttons", e)
                DEFAULT_QUICK_BUTTONS
            }
        }
    }

    /**
     * Save quick button configuration.
     */
    fun saveButtons(buttons: List<QuickButton>) {
        runBlocking {
            try {
                val json = serializeButtons(buttons)
                context.quickButtonDataStore.edit { prefs ->
                    prefs[BUTTONS_KEY] = json
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save buttons", e)
            }
        }
    }

    /**
     * Reset to default button configuration.
     */
    fun resetToDefault() {
        saveButtons(DEFAULT_QUICK_BUTTONS)
    }

    /**
     * Clear all stored settings.
     */
    suspend fun clear() {
        context.quickButtonDataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun serializeButtons(buttons: List<QuickButton>): String {
        val array = JSONArray()
        for (button in buttons) {
            val obj = JSONObject().apply {
                put("id", button.id)
                put("label", button.label)
                button.keyType?.let { put("keyType", it.number) }
                button.character?.let { put("character", it) }
                put("isDefault", button.isDefault)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseButtons(json: String): List<QuickButton> {
        val result = mutableListOf<QuickButton>()
        val array = JSONArray(json)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            try {
                val id = obj.getString("id")
                val label = obj.getString("label")
                val keyTypeNum = if (obj.has("keyType")) obj.getInt("keyType") else null
                val character = if (obj.has("character")) obj.getString("character") else null
                val isDefault = obj.optBoolean("isDefault", false)

                // Map keyType number to enum
                val keyType = keyTypeNum?.let { num ->
                    KeyType.forNumber(num)
                }

                // Validate button has either keyType or character
                if (keyType != null || character != null) {
                    result.add(
                        QuickButton(
                            id = id,
                            label = label,
                            keyType = keyType,
                            character = character,
                            isDefault = isDefault
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse button at index $i", e)
                // Skip invalid buttons
            }
        }

        return result.ifEmpty { DEFAULT_QUICK_BUTTONS }
    }

    /**
     * Get a button by ID from the available buttons list.
     */
    fun getAvailableButton(id: String): QuickButton? {
        return AVAILABLE_QUICK_BUTTONS.find { it.id == id }
    }

    /**
     * Get all available buttons that are not currently configured.
     */
    fun getUnusedButtons(currentButtons: List<QuickButton>): List<QuickButton> {
        val currentIds = currentButtons.map { it.id }.toSet()
        return AVAILABLE_QUICK_BUTTONS.filter { it.id !in currentIds }
    }
}
