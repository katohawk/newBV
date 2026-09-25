package dev.frost819.newbv.data.quickentry

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * 首页快捷收藏仓库。
 *
 * 收藏仅保存在本地 DataStore（JSON 数组字符串），无账号云同步。
 * 新收藏排在最前；单条损坏不影响其他条目；编辑时保留未知未来类型的原始记录。
 *
 * @param dataStore Hilt 提供的应用级偏好 DataStore。
 */
@Singleton
class QuickEntryRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        /** 收藏列表流，按"最近收藏在前"排序；解析失败的记录被静默跳过。 */
        val entries: Flow<List<QuickEntry>> =
            dataStore.data
                .catch { error ->
                    if (error is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw error
                    }
                }.map { preferences -> decode(preferences[preferenceKey]) }
                .distinctUntilChanged()

        /** 收藏/取消收藏。重复收藏同一入口时替换位置并置顶。 */
        suspend fun setSaved(
            value: QuickEntry,
            saved: Boolean,
        ) {
            require(value.isValid)
            dataStore.edit { preferences ->
                // 保留未知未来类型/字段的原始记录，只改当前操作的书签
                val others =
                    records(preferences[preferenceKey]).filter { entry(it)?.key != value.key }
                val updated =
                    if (saved) {
                        listOf(json.encodeToJsonElement(QuickEntry.serializer(), value)) + others
                    } else {
                        others
                    }
                preferences[preferenceKey] = json.encodeToString(JsonArray(updated))
            }
        }

        companion object {
            val preferenceKey = stringPreferencesKey("quick_entries_v1")

            private val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }

            private fun records(value: String?): List<JsonElement> =
                runCatching { json.parseToJsonElement(value ?: "[]") as JsonArray }
                    .getOrDefault(JsonArray(emptyList()))

            private fun entry(value: JsonElement): QuickEntry? =
                runCatching { json.decodeFromJsonElement(QuickEntry.serializer(), value) }
                    .getOrNull()
                    ?.takeIf { it.isValid }

            /** 容错解码：任何一条损坏或类型未知都只跳过该条。 */
            fun decode(value: String?): List<QuickEntry> =
                records(value).mapNotNull { entry(it) }.distinctBy { it.key }
        }
    }
