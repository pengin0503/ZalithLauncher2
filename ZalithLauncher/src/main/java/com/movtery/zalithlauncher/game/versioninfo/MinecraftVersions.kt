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

package com.movtery.zalithlauncher.game.versioninfo

import com.google.gson.reflect.TypeToken
import com.movtery.zalithlauncher.game.addons.mirror.mapBMCLMirrorUrls
import com.movtery.zalithlauncher.game.versioninfo.models.VersionManifest
import com.movtery.zalithlauncher.game.versioninfo.models.mapVersion
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.path.URL_MINECRAFT_VERSION_REPOS
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.file.readString
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.fetchStringFromUrls
import com.movtery.zalithlauncher.utils.network.fetchStringFromUrlsConditional
import com.movtery.zalithlauncher.utils.network.withRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private const val TAG = "MinecraftVersions"

/** HTTP 日期格式（RFC 1123），用于 If-Modified-Since 请求头。 */
private fun formatHttpDate(timeMillis: Long): String =
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }.format(Date(timeMillis))

object MinecraftVersions {
    private var manifest: VersionManifest? = null

    private val _allVersions = MutableStateFlow<List<MinecraftVersion>>(emptyList())
    val allVersions = _allVersions.asStateFlow()

    /**
     * 刷新Minecraft版本的版本号列表
     * @param force 强制下载更新版本列表
     */
    @Throws(IllegalStateException::class)
    suspend fun refreshVersions(force: Boolean = false) {
        if (!force && _allVersions.value.isNotEmpty()) return

        val vm = if (force) {
            getVersionManifest(force = true)
        } else {
            manifest ?: getVersionManifest(force = false)
        }
        val versions = vm.versions.mapVersion()
        _allVersions.update { versions }
    }

    /**
     * 获取Minecraft版本信息列表
     * @param force 强制下载更新版本列表
     */
    @Throws(IllegalStateException::class)
    suspend fun getVersionManifest(force: Boolean = false): VersionManifest {
        manifest?.takeIf { !force }?.let { return it }

        return withContext(Dispatchers.IO) {
            val localManifestFile = PathManager.FILE_MINECRAFT_VERSIONS
            val isOutdated = !localManifestFile.exists() || !localManifestFile.isFile ||
                    //一天更新一次版本信息列表
                    localManifestFile.lastModified() + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()

            val newManifest = if (force || isOutdated) {
                // 强制刷新必须绕过条件请求，否则 304 会让用户继续看到旧列表
                downloadVersionManifest(skipConditional = force)
            } else {
                try {
                    GSON.fromJson(localManifestFile.readText(), VersionManifest::class.java)
                } catch (e: Exception) {
                    Logger.warning(TAG, "Failed to parse version manifest, will redownload", e)
                    //读取失败则删除当前的版本信息文件
                    FileUtils.deleteQuietly(localManifestFile)
                    // 缓存已损坏，绝不能带上它的 mtime 去要 304
                    downloadVersionManifest(skipConditional = true)
                }
            }

            val newManifest0 = newManifest ?: throw IllegalStateException("Version manifest is null after all attempts")
            mergeUnlistVersions(newManifest0) ?: newManifest0
        }.also { newManifest ->
            manifest = newManifest
        }
    }

    /**
     * 从官方版本仓库获取版本信息。
     *
     * N3: 日常刷新带上 If-Modified-Since，未变化的多 MB manifest 只需一个约 1 KB 的
     * 304 响应；服务器/镜像忽略该请求头时仍按原样返回 200 + 全量内容。
     */
    private suspend fun downloadVersionManifest(skipConditional: Boolean = false): VersionManifest {
        return withContext(Dispatchers.IO) {
            withRetry("MinecraftVersions", maxRetries = 2) {
                val localManifestFile = PathManager.FILE_MINECRAFT_VERSIONS
                val cachedJson = if (localManifestFile.isFile) {
                    runCatching { localManifestFile.readText() }.getOrNull()
                } else null

                val headers = if (!skipConditional && cachedJson != null) {
                    mapOf("If-Modified-Since" to formatHttpDate(localManifestFile.lastModified()))
                } else emptyMap()

                val urls = URL_MINECRAFT_VERSION_REPOS.mapBMCLMirrorUrls()
                // null 表示某个源回答了 304：本地字节仍然有效
                val remoteJson = fetchStringFromUrlsConditional(urls, headers)

                val usedCache = remoteJson == null && cachedJson != null
                val rawJson = when {
                    remoteJson != null -> remoteJson
                    usedCache -> cachedJson!!
                    else -> {
                        // 镜像在没有任何可用缓存时回答了 304：退回不带条件头的全量请求
                        Logger.warning(TAG, "Manifest mirror returned 304 without usable cache, refetching")
                        fetchStringFromUrls(urls)
                    }
                }

                val versionManifest = GSON.fromJson(rawJson, VersionManifest::class.java)
                if (usedCache) {
                    // 304 保留原字节，只刷新 mtime，否则下次启动仍会判定为过期并重复请求
                    runCatching { localManifestFile.setLastModified(System.currentTimeMillis()) }
                        .onFailure { Logger.warning(TAG, "Failed to refresh manifest timestamp", it) }
                } else {
                    localManifestFile.writeText(rawJson)
                }
                versionManifest
            }
        }
    }

    /**
     * 尝试从本地合并官方隐藏的版本
     */
    private suspend fun mergeUnlistVersions(
        currentManifest: VersionManifest
    ): VersionManifest? {
        return withContext(Dispatchers.IO) {
            MinecraftVersions::class.java.getResourceAsStream("/assets/game/unlist_versions.json")?.use { input ->
                input.readString()
            }?.let { unlistVersionJson ->
                GSON.fromJson<List<VersionManifest.Version>>(
                    unlistVersionJson,
                    object : TypeToken<List<VersionManifest.Version>>() {}.type
                )
            }?.let { unlistVersions ->
                val versions = currentManifest.versions.toMutableList()
                versions.addAll(unlistVersions)
                versions.sortWith { version, other ->
                    other.releaseTime.compareTo(version.releaseTime)
                }

                currentManifest.copy(
                    versions = versions.toList()
                )
            }
        }
    }
}