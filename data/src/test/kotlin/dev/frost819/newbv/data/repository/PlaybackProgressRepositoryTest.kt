package dev.frost819.newbv.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** 验证本地续播的即时可见性、持久化、账号／季隔离及无痕模式。 */
class PlaybackProgressRepositoryTest {
    @Test
    fun `latest record is visible immediately and survives store recreation`() =
        runBlocking {
            val directory = Files.createTempDirectory("newbv-progress").toFile()
            val file = directory.resolve("progress.preferences_pb")
            var job = SupervisorJob()

            fun repository() =
                PlaybackProgressRepository(
                    PreferenceDataStoreFactory.create(
                        scope = CoroutineScope(job + Dispatchers.IO),
                        produceFile = { file },
                    ),
                )
            try {
                val store = repository()
                val first = PlaybackProgress(1, 10, 11, 100, 30)
                val latest = PlaybackProgress(2, 20, 12, 100, 80)
                val oldWrite = store.save(7, first, false)
                val newWrite = store.save(7, latest, false)
                assertThat(store.get(7, 0, 100)).isEqualTo(latest)
                oldWrite?.join()
                newWrite?.join()
                job.cancelAndJoin()
                job = SupervisorJob()
                assertThat(repository().get(7, 0, 100)).isEqualTo(latest)
            } finally {
                job.cancelAndJoin()
                directory.deleteRecursively()
            }
        }

    @Test
    fun `accounts seasons multipart videos and incognito remain isolated`() =
        runBlocking {
            val directory = Files.createTempDirectory("newbv-progress").toFile()
            val job = SupervisorJob()
            val store =
                PlaybackProgressRepository(
                    PreferenceDataStoreFactory.create(
                        scope = CoroutineScope(job + Dispatchers.IO),
                        produceFile = { directory.resolve("progress.preferences_pb") },
                    ),
                )
            try {
                val pgc = PlaybackProgress(1, 10, 11, 100, -1)
                val ugc = PlaybackProgress(1, 50, position = 60)
                store.save(7, pgc, false)?.join()
                store.save(7, ugc, false)?.join()
                assertThat(store.get(7, 1)).isEqualTo(ugc)
                assertThat(store.get(7, 0, 100)).isEqualTo(pgc)
                assertThat(store.get(8, 0, 100)).isNull()
                assertThat(store.get(7, 0, 200)).isNull()
                assertThat(store.save(7, pgc.copy(cid = 30), true)).isNull()
                assertThat(store.get(7, 0, 100)).isEqualTo(pgc)
                store.save(7, ugc.copy(cid = 60, position = 120), false)?.join()
                assertThat(store.get(7, 1)?.cid).isEqualTo(60)
                assertThat(store.save(7, ugc.copy(cid = 0), false)).isNull()
            } finally {
                job.cancelAndJoin()
                directory.deleteRecursively()
            }
        }

    @Test
    fun `corrupt data and disk errors cannot prevent playback`() =
        runBlocking {
            val dataStore =
                io.mockk
                    .mockk<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>>()
            val prefs =
                androidx.datastore.preferences.core.mutablePreferencesOf(
                    androidx.datastore.preferences.core
                        .stringPreferencesKey("playback_v1_7_video_1") to "broken JSON",
                )
            io.mockk.every { dataStore.data } returns kotlinx.coroutines.flow.flowOf(prefs)
            val store = PlaybackProgressRepository(dataStore)
            assertThat(store.get(7, 1)).isNull()
            io.mockk.every { dataStore.data } returns kotlinx.coroutines.flow.flow { throw java.io.IOException("disk") }
            assertThat(store.get(7, 1)).isNull()
        }
}
