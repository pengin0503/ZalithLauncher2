/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.content.home.version

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.utils.hasStoragePermission
import com.movtery.zalithlauncher.utils.logging.Logger
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val TAG = "VersionCardManager"

/** 版本卡片记录的持久化数据 */
private data class VersionCardRecordDto(
    @SerializedName("cardId")
    val cardId: String = "",
    @SerializedName("versionName")
    val versionName: String = "",
    @SerializedName("dirType")
    val dirType: String = "",
    @SerializedName("dirPath")
    val dirPath: String = ""
) {
    fun toRecord() = VersionCardRecord(
        cardId = cardId,
        versionName = versionName,
        dir = when (dirType) {
            VersionCardManager.DIR_TYPE_CUSTOM -> VersionCardDir.Custom(dirPath)
            else -> VersionCardDir.Default
        }
    )

    companion object {
        fun fromRecord(record: VersionCardRecord) = VersionCardRecordDto(
            cardId = record.cardId,
            versionName = record.versionName,
            dirType = when (record.dir) {
                is VersionCardDir.Custom -> VersionCardManager.DIR_TYPE_CUSTOM
                VersionCardDir.Default -> VersionCardManager.DIR_TYPE_DEFAULT
            },
            dirPath = (record.dir as? VersionCardDir.Custom)?.path ?: ""
        )
    }
}

/**
 * 版本卡片管理器
 */
object VersionCardManager {
    const val DIR_TYPE_DEFAULT = "default"
    const val DIR_TYPE_CUSTOM = "custom"

    private const val KEY_RECORDS = "versionCards"

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID("home_version_cards") }
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _cards = MutableStateFlow<List<VersionCardState>>(emptyList())

    /** 全部版本卡片及其当前可用性状态 */
    val cards: StateFlow<List<VersionCardState>> = _cards.asStateFlow()

    /**
     * 每张卡片各自的独立状态流：单张卡片变化只通知对应卡片，
     * 避免主页网格里每张卡都扫描整张列表并重组。
     */
    private val cardFlows = mutableMapOf<String, MutableStateFlow<VersionCardState?>>()

    /** 取指定卡片的独立状态流；卡片被移除后流会发射 null */
    fun cardFlow(cardId: String): StateFlow<VersionCardState?> = synchronized(this) {
        cardFlows.getOrPut(cardId) {
            MutableStateFlow(_cards.value.firstOrNull { it.record.cardId == cardId })
        }
    }

    /** 统一更新列表并把变化同步到各卡片流，只有真正变化的卡片才发射 */
    private fun updateCards(transform: (List<VersionCardState>) -> List<VersionCardState>) {
        var updated: List<VersionCardState> = emptyList()
        _cards.update { states ->
            transform(states).also { updated = it }
        }
        syncCardFlows(updated)
    }

    private fun setCards(states: List<VersionCardState>) {
        _cards.value = states
        syncCardFlows(states)
    }

    /** 已删除的卡片先置 null 再移除流，保证仍在收集的旧可组合项能看到移除 */
    private fun syncCardFlows(states: List<VersionCardState>) {
        synchronized(this) {
            val alive = HashSet<String>(states.size)
            states.forEach { state ->
                val id = state.record.cardId
                alive += id
                val flow = cardFlows[id]
                if (flow == null) {
                    cardFlows[id] = MutableStateFlow(state)
                } else {
                    flow.value = state
                }
            }
            val iterator = cardFlows.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in alive) {
                    entry.value.value = null
                    iterator.remove()
                }
            }
        }
    }

    init {
        setCards(loadRecords().map { record ->
            VersionCardState(record, VersionCardStatus.Loading)
        })
        recheck()
        //版本列表刷新后同步卡片可用性
        VersionsManager.registerListener {
            recheck()
        }
    }

    /** 指定版本是否已存在对应卡片 */
    fun hasCard(versionName: String, gameHome: String): Boolean {
        val dir = VersionCardDir.fromGameHome(gameHome)
        return _cards.value.any { it.record.versionName == versionName && it.record.dir == dir }
    }

    /**
     * 为指定版本创建卡片，版本已存在对应卡片时不做任何事
     * @return 是否成功创建
     */
    fun addCard(version: Version): Boolean {
        val dir = VersionCardDir.fromGameHome(version.getGameHome())
        synchronized(this) {
            if (hasCard(version.getVersionName(), version.getGameHome())) return false
            val record = VersionCardRecord(
                cardId = UUID.randomUUID().toString(),
                versionName = version.getVersionName(),
                dir = dir
            )
            updateCards { states ->
                states + VersionCardState(record, VersionCardStatus.Available(version))
            }
            saveRecords()
        }
        return true
    }

    /** 移除指定卡片 */
    fun removeCard(cardId: String) {
        synchronized(this) {
            val removed = _cards.value.any { it.record.cardId == cardId }
            if (!removed) return
            updateCards { states -> states.filterNot { it.record.cardId == cardId } }
            saveRecords()
        }
    }

    /**
     * 版本重命名后同步卡片记录，使卡片继续指向重命名后的版本
     */
    fun onVersionRenamed(gameHome: String, oldName: String, newName: String) {
        val dir = VersionCardDir.fromGameHome(gameHome)
        synchronized(this) {
            val changed = _cards.value.any { it.record.versionName == oldName && it.record.dir == dir }
            if (!changed) return
            updateCards { states ->
                states.map { state ->
                    if (state.record.versionName == oldName && state.record.dir == dir) {
                        state.copy(record = state.record.copy(versionName = newName))
                    } else {
                        state
                    }
                }
            }
            saveRecords()
        }
        recheck()
    }

    /**
     * 重新检查全部卡片的可用性
     */
    private fun recheck() {
        scope.launch {
            val current = _cards.value
            if (current.isEmpty()) return@launch
            val updated = current.map { state ->
                state.copy(status = resolveStatus(state.record))
            }
            //状态无变化时不发射，避免触发主页网格的无效重组
            if (updated != current) setCards(updated)
        }
    }

    /** 依据记录定位并加载版本，推导卡片可用性状态 */
    private fun resolveStatus(record: VersionCardRecord): VersionCardStatus {
        val gameHome = record.dir.resolveGameHome()
        if (record.dir is VersionCardDir.Custom && !hasStoragePermission) {
            return VersionCardStatus.Inaccessible
        }
        if (!File(gameHome).exists()) return VersionCardStatus.Inaccessible
        val version = VersionsManager.loadVersion(gameHome, record.versionName)
            ?.takeIf { it.isValid() }
            ?: return VersionCardStatus.Deleted
        Logger.info(
            TAG,
            "Version card loaded version: ${version.getVersionName()}, " +
                    "Path: (${version.getVersionPath()}), " +
                    "Info: ${version.getVersionInfo()?.getInfoString()}"
        )
        return VersionCardStatus.Available(version)
    }

    private fun loadRecords(): List<VersionCardRecord> {
        val json = mmkv.decodeString(KEY_RECORDS, "").orEmpty()
        if (json.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson(json, Array<VersionCardRecordDto>::class.java)
                .orEmpty()
                .map { it.toRecord() }
                .filter { it.cardId.isNotBlank() && it.versionName.isNotBlank() }
        }.onFailure { e ->
            Logger.error(TAG, "Failed to parse version card records.", e)
        }.getOrDefault(emptyList())
    }

    private fun saveRecords() {
        val json = gson.toJson(_cards.value.map { VersionCardRecordDto.fromRecord(it.record) })
        mmkv.encode(KEY_RECORDS, json)
    }
}
