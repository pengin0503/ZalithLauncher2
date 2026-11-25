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

package com.movtery.zalithlauncher.game.launch

import android.content.Context
import android.os.Build
import android.os.FileObserver
import com.movtery.zalithlauncher.context.copyAssetFile
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.utils.isLowerVer
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.string.splitPreservingQuotes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MCOptions"
private const val OBSERVER_DEBOUNCE_MS = 200L

/** 新实例首次创建 options.txt 时使用的完整预设（含旧版本键，兼顾老版本） */
private const val OPTIONS_PRESET_ASSET = "game/options.txt"

/**
 * 已存在的 options.txt 补全缺失项时使用的模板（1.18+）。只含现代版本保存时会保留的键：
 * 若混入 fancyGraphics/clouds/anaglyph3d 等旧键，游戏每次保存都会丢掉它们，
 * 导致每次启动都“补全”一次并重写文件。
 */
private const val OPTIONS_FILL_MODERN_ASSET = "game/options_fill_modern.txt"

/** simulationDistance 自 1.18 起存在；更早的版本沿用完整预设补全 */
private const val OPTIONS_FILL_MODERN_SINCE = "1.18"

object MCOptions {
    private val lock = Any()
    private val parameterMap = ConcurrentHashMap<String, String>()
    /**
     * 自上次 load/save 以来经由 [set] 修改过的键。save() 时若有尚未执行的防抖重载，
     * 会先从磁盘重载（拿到游戏刚写入的内容），再把这些键覆盖回去，避免互相覆盖。
     */
    private val dirtyKeys = HashSet<String>()
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fileObserver: FileObserver? = null
    private var debounceJob: Job? = null
    private var lastLoadedSize: Long = -1L
    private var lastLoadedMtime: Long = -1L
    private lateinit var version: Version

    private val _refreshKey = MutableStateFlow(false)
    /** options.txt 文件刷新 */
    val refreshKey = _refreshKey.asStateFlow()

    /**
     * 初始化 Minecraft 选项配置
     */
    fun setup(context: Context, version: Version) {
        this.version = version
        synchronized(lock) {
            parameterMap.clear()
            dirtyKeys.clear()
            debounceJob?.cancel()
            debounceJob = null
            lastLoadedSize = -1L
            lastLoadedMtime = -1L
            fileObserver?.stopWatching()

            val optionsFile = getOptionsFile()
            optionsFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
            val existed = optionsFile.exists()
            if (!existed) {
                optionsFile.createWithDefaults(context)
            }

            loadInternal()
            if (existed) {
                fillMissingDefaults(context)
            }
            setupFileObserver()
        }
    }

    private fun File.createWithDefaults(context: Context) {
        runCatching {
            context.copyAssetFile(
                OPTIONS_PRESET_ASSET,
                parentFile?.absolutePath ?: return,
                false
            )
        }.onFailure {
            Logger.warning(TAG, "Failed to unpack options.txt!", it)
        }
    }

    private fun fillMissingDefaults(context: Context) {
        runCatching {
            var added = false
            val mcVer = version.getVersionInfo()?.minecraftVersion ?: version.getVersionName()
            val template = if (mcVer.isLowerVer(OPTIONS_FILL_MODERN_SINCE)) {
                OPTIONS_PRESET_ASSET
            } else {
                OPTIONS_FILL_MODERN_ASSET
            }
            context.assets.open(template).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    line.indexOf(':').takeIf { it > 0 }?.let { idx ->
                        val key = line.take(idx)
                        val value = line.substring(idx + 1)
                        if (parameterMap.putIfAbsent(key, value) == null) {
                            added = true
                        }
                    }
                }
            }
            if (added) {
                val optionsFile = getOptionsFile()
                writeFileAtomically(optionsFile)
                updateLoadedMeta(optionsFile)
            }
        }.onFailure {
            Logger.warning(TAG, "Failed to merge default options.txt!", it)
        }
    }

    private fun loadInternal() {
        val optionsFile = getOptionsFile()
        if (!optionsFile.exists()) return
        val size = optionsFile.length()
        val mtime = optionsFile.lastModified()
        if (size == lastLoadedSize && mtime == lastLoadedMtime) {
            return
        }
        runCatching {
            val newMap = optionsFile.readLines()
                .mapNotNull { line ->
                    line.indexOf(':').takeIf { it > 0 }?.let { idx ->
                        line.take(idx) to line.substring(idx + 1)
                    }
                }.toMap()

            parameterMap.clear()
            parameterMap.putAll(newMap)
            //内存中未保存的修改已被磁盘内容替换
            dirtyKeys.clear()
            lastLoadedSize = size
            lastLoadedMtime = mtime

            _refreshKey.update { it.not() }
        }.onFailure {
            Logger.warning(TAG, "Failed to load options!", it)
        }
    }

    private fun updateLoadedMeta(file: File) {
        if (file.exists()) {
            lastLoadedSize = file.length()
            lastLoadedMtime = file.lastModified()
        }
    }

    fun set(key: String, value: String): String? = synchronized(lock) {
        dirtyKeys.add(key)
        parameterMap.put(key, value)
    }

    fun set(key: String, value: List<String>) {
        set(key, value.joinToString(prefix = "[", postfix = "]") { "\"$it\"" })
    }

    fun get(key: String): String? = parameterMap[key]

    fun getAsList(key: String): List<String> {
        val raw = get(key) ?: return emptyList()

        return raw
            .removeSurrounding("[", "]")
            .takeIf { it.isNotBlank() }
            ?.splitPreservingQuotes(',')
            ?.map { it.trim() }
            ?: emptyList()
    }

    fun containsKey(key: String): Boolean = parameterMap.containsKey(key)

    fun save() {
        synchronized(lock) {
            //防抖窗口内游戏刚写过 options.txt：先合并磁盘内容，再覆盖本地修改过的键
            val reloadPending = debounceJob?.isActive == true
            debounceJob?.cancel()
            debounceJob = null
            getOptionsFile().takeIf { it.exists() }?.let { file ->
                try {
                    fileObserver?.stopWatching()
                    if (reloadPending) {
                        val pendingChanges = dirtyKeys.mapNotNull { key ->
                            parameterMap[key]?.let { key to it }
                        }
                        loadInternal()
                        pendingChanges.forEach { (key, value) ->
                            parameterMap[key] = value
                            //loadInternal 会清空 dirtyKeys；写入成功前保持脏标记
                            dirtyKeys.add(key)
                        }
                    }
                    if (writeFileAtomically(file)) {
                        dirtyKeys.clear()
                    }
                    updateLoadedMeta(file)
                } finally {
                    fileObserver?.startWatching()
                }
            }
        }
    }

    /** @return 是否成功写入 */
    private fun writeFileAtomically(targetFile: File): Boolean {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp").apply {
            deleteOnExit()
        }

        return runCatching {
            tempFile.writeText(
                parameterMap.entries.joinToString("\n") { "${it.key}:${it.value}" }
            )
            check(tempFile.renameTo(targetFile)) { "Failed to rename ${tempFile.name} to ${targetFile.name}" }
        }.onFailure {
            Logger.error(TAG, "Failed to save options.txt!", it)
            tempFile.delete()
        }.isSuccess
    }

    private fun getOptionsFile() = File(version.getGameDir(), "options.txt")

    private fun setupFileObserver() {
        fileObserver?.stopWatching()
        fileObserver = createPlatformFileObserver().apply {
            startWatching()
        }
    }

    private fun scheduleReload() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = observerScope.launch {
                delay(OBSERVER_DEBOUNCE_MS)
                synchronized(lock) {
                    loadInternal()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createPlatformFileObserver(): FileObserver {
        val observeTarget = getOptionsFile()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(observeTarget, MODIFY) {
                override fun onEvent(event: Int, path: String?) {
                    if (event and MODIFY != 0) {
                        scheduleReload()
                    }
                }
            }
        } else {
            object : FileObserver(observeTarget.absolutePath, MODIFY) {
                override fun onEvent(event: Int, path: String?) {
                    if (event and MODIFY != 0) {
                        scheduleReload()
                    }
                }
            }
        }
    }
}