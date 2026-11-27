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

package com.movtery.zalithlauncher.game.version.mod

import androidx.annotation.Keep
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

private const val TAG = "ModMetadataCache"

/** 缓存格式版本，结构变化时递增，旧缓存会被直接丢弃并重建 */
private const val CACHE_VERSION = 1

/**
 * 模组元数据的持久缓存
 *
 * 以文件绝对路径为键，附带文件大小与最后修改时间做校验；
 * 命中时跳过打开 jar，未命中/已变化时重新解析并更新缓存。
 * 缓存格式版本或应用版本变化时整体失效，避免沿用旧解析逻辑的结果。
 */
internal object ModMetadataCache {
    /** 所有扫描目录共用一份缓存文件，放在应用私有 files 目录下 */
    private val cacheFile: File
        get() = File(PathManager.DIR_FILES_PRIVATE, "mod_metadata_cache.json")

    private var loaded = false
    private var dirty = false
    private val entries: MutableMap<String, CachedModMetadata> = mutableMapOf()

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true

        val file = cacheFile
        if (!file.isFile) return

        runCatching {
            val parsed = GSON.fromJson(file.readText(), CacheFile::class.java) ?: CacheFile()
            if (parsed.version == CACHE_VERSION && parsed.appVersion == BuildConfig.VERSION_CODE) {
                parsed.entries?.let { entries.putAll(it) }
            }
        }.onFailure { e ->
            //缓存文件损坏：忽略并重建，不缓存损坏结果
            Logger.warning(TAG, "Failed to read the mod metadata cache, rebuilding", e)
            entries.clear()
            file.delete()
        }
    }

    /**
     * 读取缓存，仅在文件大小与最后修改时间都一致时命中
     */
    @Synchronized
    fun get(file: File): CachedModMetadata? {
        ensureLoaded()
        return entries[file.absolutePath]?.takeIf { it.matches(file) }
    }

    /**
     * 更新单个文件的缓存
     */
    @Synchronized
    fun put(file: File, metadata: CachedModMetadata) {
        ensureLoaded()
        entries[file.absolutePath] = metadata
        dirty = true
    }

    /**
     * 清理指定扫描目录下已不存在文件的缓存条目
     *
     * 只处理当前目录，不会误删其他游戏目录的缓存。
     * @param directory 本次扫描的目录
     * @param existing 本次扫描到的文件绝对路径
     */
    @Synchronized
    fun prune(directory: File, existing: Set<String>) {
        ensureLoaded()
        val prefix = directory.absolutePath.trimEnd(File.separatorChar) + File.separator
        val removed = entries.keys.removeAll { path ->
            path.startsWith(prefix) && path !in existing
        }
        if (removed) dirty = true
    }

    /**
     * 将缓存写回磁盘，仅在缓存发生改动时写入
     */
    @Synchronized
    fun save() {
        if (!dirty) return

        runCatching {
            val file = cacheFile
            val parent = file.parentFile ?: throw IOException("Cache file has no parent")
            Files.createDirectories(parent.toPath())

            // Unique temp name: multiple mod scans / processes can save at once.
            val tmp = Files.createTempFile(parent.toPath(), "${file.name}.", ".tmp").toFile()
            try {
                tmp.writeText(
                    GSON.toJson(
                        CacheFile(
                            version = CACHE_VERSION,
                            appVersion = BuildConfig.VERSION_CODE,
                            entries = entries
                        )
                    )
                )
                try {
                    Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        if (!tmp.renameTo(file)) {
                            throw IllegalStateException("Failed to replace the mod metadata cache file")
                        }
                    }
                }
                dirty = false
            } finally {
                tmp.delete()
            }
        }.onFailure { e ->
            Logger.warning(TAG, "Failed to save the mod metadata cache", e)
        }
    }

    //通过 GSON 反射读写：保留字段名，避免 R8 混淆后同 versionCode 的不同构建互相读到错位/空字段
    @Keep
    private data class CacheFile(
        val version: Int = 0,
        val appVersion: Int = 0,
        val entries: Map<String, CachedModMetadata>? = null
    )
}

/**
 * 序列化后的模组元数据，足以还原一个 [LocalMod]
 */
@Keep
internal data class CachedModMetadata(
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val fileSize: Long = 0L,
    val id: String? = null,
    val loader: String? = null,
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    val authors: List<String>? = null,
    val iconBase64: String? = null,
    val notMod: Boolean = false,
    val checkRemote: Boolean = true
) {
    fun matches(file: File): Boolean =
        size == file.length() && lastModified == file.lastModified()

    fun toLocalMod(file: File): LocalMod = LocalMod(
        modFile = file,
        fileSize = fileSize.takeIf { it > 0L } ?: file.length(),
        id = id ?: "",
        loader = loader?.let { name -> runCatching { ModLoader.valueOf(name) }.getOrNull() }
            ?: ModLoader.UNKNOWN,
        name = name ?: file.name,
        description = description,
        version = version,
        authors = authors ?: emptyList(),
        icon = iconBase64?.let { encoded ->
            runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
        },
        notMod = notMod,
        checkRemote = checkRemote
    )

    companion object {
        fun fromLocalMod(file: File, mod: LocalMod): CachedModMetadata = CachedModMetadata(
            size = file.length(),
            lastModified = file.lastModified(),
            fileSize = mod.fileSize,
            id = mod.id,
            loader = mod.loader.name,
            name = mod.name,
            description = mod.description,
            version = mod.version,
            authors = mod.authors,
            iconBase64 = mod.icon?.let { Base64.getEncoder().encodeToString(it) },
            notMod = mod.notMod,
            checkRemote = mod.checkRemote
        )
    }
}
