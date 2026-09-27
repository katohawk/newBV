package dev.frost819.newbv.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本机最后实际播放的分集。
 *
 * @property aid 视频 AV 号。
 * @property cid 分 P 标识。
 * @property epid 番剧分集标识，普通视频为 null。
 * @property seasonId 番剧季标识，普通视频为 0。
 * @property position 秒级进度，-1 表示已看完。
 */
@Serializable
data class PlaybackProgress(
    val aid: Long,
    val cid: Long,
    val epid: Int? = null,
    val seasonId: Int = 0,
    val position: Int = 0,
)

/** 按账号和季／视频隔离的本机续播记录，内存立即可见，磁盘写入随应用存续。 */
@Singleton
class PlaybackProgressRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        private val memory = ConcurrentHashMap<String, PlaybackProgress>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val json = Json { ignoreUnknownKeys = true }

        /** 读取本机记录；磁盘读取期间的新播放记录优先于旧快照。 */
        suspend fun get(
            uid: Long,
            aid: Long,
            seasonId: Int = 0,
        ): PlaybackProgress? {
            val key = key(uid, aid, seasonId)
            memory[key]?.let { return it }
            val raw =
                try {
                    dataStore.data.first()[stringPreferencesKey(key)]
                } catch (_: java.io.IOException) {
                    return memory[key]
                }
            val saved =
                raw
                    ?.let { runCatching { json.decodeFromString<PlaybackProgress>(it) }.getOrNull() }
                    ?.takeIf {
                        it.aid > 0 &&
                            it.cid > 0 &&
                            it.position >= -1 &&
                            if (seasonId > 0) it.seasonId == seasonId else it.seasonId == 0 && it.aid == aid
                    }
            return memory[key] ?: saved
        }

        /** 同步更新内存并异步落盘；无痕播放不保存。返回写入任务供调用方等待持久化。 */
        fun save(
            uid: Long,
            progress: PlaybackProgress,
            incognito: Boolean,
        ): Job? {
            if (incognito || progress.aid <= 0 || progress.cid <= 0 || progress.position < -1) return null
            val key = key(uid, progress.aid, progress.seasonId)
            memory[key] = progress
            return scope.launch {
                // 写任务可能交错；只允许当前最新快照提交，避免上一集覆盖新集。
                try {
                    dataStore.edit { prefs ->
                        if (memory[key] === progress) prefs[stringPreferencesKey(key)] = json.encodeToString(progress)
                    }
                } catch (_: java.io.IOException) {
                    // 磁盘不可写时保留本会话记录，下一次进度更新会再次尝试。
                }
            }
        }

        private fun key(
            uid: Long,
            aid: Long,
            seasonId: Int,
        ): String = "playback_v1_${uid}_" + if (seasonId > 0) "season_$seasonId" else "video_$aid"
    }
