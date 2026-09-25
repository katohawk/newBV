package dev.frost819.newbv.data.quickentry

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.repositories.SearchType
import java.nio.file.Files
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [QuickEntryRepository] 与收藏合并逻辑的单元测试。
 *
 * 覆盖：序列化往返、DataStore 重建后数据保留、增删去重、坏数据容错、
 * 首批合并去重、未知搜索类型回退。
 */
class QuickEntryRepositoryTest {
    private val video = QuickEntry(QuickEntryType.VIDEO, "视频 A", "https://example.com/a.jpg", aid = 123)
    private val season = QuickEntry(QuickEntryType.SEASON, "番剧 B", "https://example.com/b.jpg", seasonId = 456)
    private val search =
        QuickEntry(
            QuickEntryType.SEARCH,
            "小猪佩奇 · 番剧",
            keyword = "小猪佩奇",
            searchType = SearchType.MediaBangumi.name,
        )

    @Test
    fun `all navigation fields survive serialization`() {
        listOf(video, season, search).forEach {
            assertThat(Json.decodeFromString<QuickEntry>(Json.encodeToString(it))).isEqualTo(it)
        }
        assertThat(QuickEntry.initialSearchType(search.searchType)).isEqualTo(SearchType.MediaBangumi)
        assertThat(QuickEntry.initialSearchType(null)).isEqualTo(SearchType.Video)
        assertThat(QuickEntry.initialSearchType("future-type")).isEqualTo(SearchType.Video)
    }

    @Test
    fun `persistent store survives complete recreation and removal`() = runBlocking {
        val directory = Files.createTempDirectory("newbv-quick-entry").toFile()
        val file = directory.resolve("test.preferences_pb")
        var job = SupervisorJob()
        fun repository() =
            QuickEntryRepository(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + job),
                    produceFile = { file },
                ),
            )
        try {
            var store = repository()
            store.setSaved(video, true)
            store.setSaved(season, true)
            store.setSaved(search, true)
            // 重复收藏不产生重复数据，且最近收藏在前
            store.setSaved(search, true)
            assertThat(store.entries.first()).isEqualTo(listOf(search, season, video))

            // 模拟 App 数据层完全重建
            job.cancelAndJoin()
            job = SupervisorJob()
            store = repository()
            assertThat(store.entries.first()).isEqualTo(listOf(search, season, video))

            // 取消收藏
            store.setSaved(season, false)
            assertThat(store.entries.first()).isEqualTo(listOf(search, video))
            job.cancelAndJoin()
            job = SupervisorJob()
            assertThat(repository().entries.first()).isEqualTo(listOf(search, video))
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `bad record or unknown type does not hide other entries`() {
        val encoded = Json.encodeToString(video)
        val raw = "[$encoded,{\"type\":\"Future\",\"title\":\"unknown\"},{\"type\":\"Video\"}]"
        assertThat(QuickEntryRepository.decode(raw)).isEqualTo(listOf(video))
        assertThat(QuickEntryRepository.decode("broken JSON")).isEmpty()
        assertThat(QuickEntryRepository.decode(null)).isEmpty()
    }

    @Test
    fun `edits preserve future entries`() = runBlocking {
        val directory = Files.createTempDirectory("newbv-quick-entry-future").toFile()
        val job = SupervisorJob()
        try {
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(Dispatchers.IO + job),
                    produceFile = { directory.resolve("test.preferences_pb") },
                )
            val unknown = "{\"type\":\"Future\",\"title\":\"unknown\",\"extra\":42}"
            dataStore.edit { it[stringPreferencesKey("quick_entries_v1")] = "[$unknown]" }
            val repository = QuickEntryRepository(dataStore)
            repository.setSaved(video, true)
            repository.setSaved(video, false)
            assertThat(dataStore.data.first()[QuickEntryRepository.preferenceKey]).isEqualTo("[$unknown]")
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `first batch deduplication keeps later pages and source indexes`() {
        val recommendations = listOf(video.key, "video:789", "video:999", video.key, season.key)
        val merged = mergeQuickEntries(listOf(video, season), recommendations, firstBatchSize = 3)
        assertThat(merged.mapNotNull { it.favorite }).isEqualTo(listOf(video, season))
        assertThat(merged.filter { it.favorite == null }.map { it.recommendationIndex }).isEqualTo(listOf(1, 2, 3, 4))

        // 无收藏时与原推荐一一对应
        assertThat(mergeQuickEntries(emptyList(), recommendations, 3).map { it.recommendationIndex })
            .isEqualTo(recommendations.indices.toList())

        // 收藏与第一批完全重复时只保留收藏位置
        assertThat(mergeQuickEntries(listOf(season), listOf(season.key), 1)).hasSize(1)

        // 搜索入口不参与内容级去重
        assertThat(mergeQuickEntries(listOf(search), listOf(video.key), 1)).hasSize(2)

        // 重复收藏去重
        assertThat(mergeQuickEntries(listOf(video, video), emptyList(), 0).map { it.favorite })
            .isEqualTo(listOf(video))
    }
}
