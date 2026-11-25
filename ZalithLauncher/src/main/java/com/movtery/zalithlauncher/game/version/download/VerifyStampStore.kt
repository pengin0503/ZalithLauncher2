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
 * along with this program. If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.version.download

import com.movtery.zalithlauncher.components.ComponentFile
import com.movtery.zalithlauncher.components.componentDirectoryMatches
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.codec.digest.DigestUtils
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "VerifyStampStore"

/**
 * 验证策略版本。
 * 任何会改变“stamp 命中含义”的修改都必须自增此值，
 * 使旧 stamp 全部失效并在下次启动时强制全量哈希。
 */
const val VERIFY_STAMP_POLICY_VERSION = 1

/** stamp 文件自身的格式版本 */
const val VERIFY_STAMP_FORMAT_VERSION = 1

/** 上一游戏会话是否正常退出的标记文件名 */
private const val SESSION_FLAG_FILE = "session.running"

private val stampJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 单个文件的验证记录
 * @property path 相对于游戏根目录（游戏 stamp）或组件目录（组件 stamp）的路径，使用 '/' 分隔
 * @property size 记录时的文件大小(bytes)
 * @property mtime 记录时的最后修改时间（[File.lastModified]，毫秒）
 * @property sha1 文件内容的 SHA-1，小写十六进制
 */
@Serializable
data class VerifyFileRecord(
    val path: String,
    val size: Long,
    val mtime: Long,
    val sha1: String
)

/**
 * 一组文件的验证 stamp
 * @property generationKey 世代键；键变化意味着清单内容变化，必须全量重算
 * @property files 相对路径 -> 文件记录
 */
@Serializable
data class VerifyStamp(
    val formatVersion: Int = VERIFY_STAMP_FORMAT_VERSION,
    val policyVersion: Int = VERIFY_STAMP_POLICY_VERSION,
    val generationKey: String,
    val files: Map<String, VerifyFileRecord> = emptyMap()
)

/**
 * 验证 stamp 的持久化与公共工具。
 *
 * 语义：stamp 只证明“上次校验通过后，文件的大小与 mtime 没有变化”。
 * 因此 client jar 等关键文件永远整文件哈希，且世代键不一致或上次会话非正常退出时全量哈希。
 */
object VerifyStampStore {
    /** 游戏版本文件的 stamp 位置；[stampKey] 由 [gameStampKey] 生成 */
    fun gameStampFile(stampKey: String): File =
        File(PathManager.DIR_VERIFY_STAMPS, "$stampKey.json")

    /** 组件（LWJGL / caciocavallo / jna 等）文件的 stamp 位置 */
    fun componentStampFile(component: String): File =
        File(File(PathManager.DIR_VERIFY_STAMPS, "components"), "${safeFileName(component)}.json")

    /**
     * 读取 stamp；格式/策略版本不符或内容损坏时返回 null（调用方将回退到全量校验）
     */
    fun load(file: File): VerifyStamp? {
        if (!file.isFile) return null
        return runCatching {
            stampJson.decodeFromString(VerifyStamp.serializer(), file.readText())
        }.onFailure { e ->
            Logger.warning(TAG, "Failed to read verify stamp, falling back to full verification: ${file.absolutePath}", e)
        }.getOrNull()?.takeIf { stamp ->
            stamp.formatVersion == VERIFY_STAMP_FORMAT_VERSION &&
                    stamp.policyVersion == VERIFY_STAMP_POLICY_VERSION
        }
    }

    /**
     * 原子写入 stamp；写失败只记录日志，绝不影响启动/安装流程
     */
    fun save(file: File, stamp: VerifyStamp) {
        runCatching {
            val parent = file.parentFile
                ?: throw IOException("Stamp file has no parent: ${file.absolutePath}")
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Failed to create stamp directory: ${parent.absolutePath}")
            }
            val tempFile = File(parent, "${file.name}.tmp")
            tempFile.writeText(stampJson.encodeToString(VerifyStamp.serializer(), stamp))
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: UnsupportedOperationException) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { e ->
            Logger.warning(TAG, "Failed to write verify stamp: ${file.absolutePath}", e)
        }
    }

    /**
     * 由若干组成部分计算世代键（内部排序去重，与遍历顺序无关）
     */
    fun generationKey(parts: Collection<String>): String =
        sha1Hex(parts.distinct().sorted().joinToString("\n"))

    fun sha1Hex(text: String): String = sha1Hex(text.toByteArray(Charsets.UTF_8))

    fun sha1Hex(bytes: ByteArray): String = DigestUtils.sha1Hex(bytes)

    /**
     * @return 相对于 [baseDir] 的 '/' 分隔路径；文件不在 baseDir 之下时返回 null
     */
    fun relativePath(baseDir: File, file: File): String? {
        val basePath = baseDir.absoluteFile.path
        val targetPath = file.absoluteFile.path
        if (targetPath == basePath) return null
        if (!targetPath.startsWith(basePath + File.separator)) return null
        return targetPath.substring(basePath.length)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
    }

    /**
     * 版本 stamp 的定位键：同名版本可能位于不同的游戏目录，因此把游戏目录也纳入键中
     */
    fun gameStampKey(gameHome: String, versionId: String): String =
        "${safeFileName(versionId)}-${sha1Hex(File(gameHome).absolutePath).take(8)}"

    /**
     * 标记游戏 JVM 已启动（按版本区分）。
     * 若进程在清除标记前死亡（崩溃/被系统杀死），下次校验该版本时会看到该标记并强制全量校验。
     */
    fun markSessionRunning(stampKey: String) {
        runCatching {
            val file = sessionFlagFile(stampKey)
            if (!file.parentFile.isDirectory) file.parentFile.mkdirs()
            file.writeText(System.currentTimeMillis().toString())
        }.onFailure { e ->
            Logger.warning(TAG, "Failed to write session running flag", e)
        }
    }

    /**
     * 游戏 JVM 退出时调用；仅在非信号且退出码为 0 时清除标记，其余情况保留以触发下次全量校验。
     * 注意：正常退出路径走 native nominal_exit -> ZLNativeInvoker.jvmExit，进程随后被结束，
     * launchJvm 通常不会返回，因此必须在 onExit 回调中调用本函数。
     */
    fun onGameSessionExited(stampKey: String, exitCode: Int, isSignal: Boolean = false) {
        if (forceClosed) {
            Logger.info(TAG, "Game was force-closed, next launch will force full verification")
        } else if (exitCode == 0 && !isSignal) {
            clearUncleanSession(stampKey)
        } else {
            Logger.info(TAG, "Game exit code $exitCode (signal=$isSignal), next launch will force full verification")
        }
    }

    /** 当前 :game 进程内用户是否通过“强制关闭”结束了游戏（其退出码同样为 0） */
    @Volatile
    private var forceClosed = false

    /** 强制关闭游戏前调用：使随后的 jvmExit(0) 不被视为正常退出 */
    fun markForceClosed() {
        forceClosed = true
    }

    /** 该版本的上次会话是否非正常结束（旧版全局标记视为对所有版本生效） */
    fun hasUncleanSession(stampKey: String): Boolean =
        sessionFlagFile(stampKey).isFile || legacySessionFlagFile().isFile

    fun clearUncleanSession(stampKey: String) {
        listOf(sessionFlagFile(stampKey), legacySessionFlagFile()).forEach { file ->
            if (file.isFile && !file.delete()) {
                Logger.warning(TAG, "Failed to clear session running flag: ${file.absolutePath}")
            }
        }
    }

    /**
     * 用 stamp 快速校验组件目录：
     * 清单哈希一致 + 每个文件 size/mtime 与记录一致 + 目录内文件集合与清单一致 → 无需哈希
     */
    fun isComponentStampValid(
        stampFile: File,
        manifestHash: String,
        directory: File,
        files: List<ComponentFile>,
        ignoredFiles: Set<String> = emptySet()
    ): Boolean {
        if (!directory.isDirectory) return false
        val stamp = load(stampFile) ?: return false
        if (stamp.generationKey != manifestHash) return false

        files.forEach { expected ->
            val file = File(directory, expected.outputPath)
            if (!file.isFile || file.length() != expected.size) return false
            val record = stamp.files[expected.outputPath] ?: return false
            if (!record.sha1.equals(expected.sha1, ignoreCase = true)) return false
            if (record.size != file.length() || record.mtime != file.lastModified()) return false
        }
        return componentDirectoryMatches(directory, files, ignoredFiles)
    }

    /**
     * 组件安装/全量校验成功后写入 stamp
     */
    fun saveComponentStamp(
        stampFile: File,
        manifestHash: String,
        directory: File,
        files: List<ComponentFile>
    ) {
        val records = HashMap<String, VerifyFileRecord>(files.size)
        files.forEach { expected ->
            val file = File(directory, expected.outputPath)
            if (file.isFile && file.length() == expected.size) {
                records[expected.outputPath] = VerifyFileRecord(
                    path = expected.outputPath,
                    size = file.length(),
                    mtime = file.lastModified(),
                    sha1 = expected.sha1
                )
            }
        }
        if (records.size != files.size) {
            //有文件缺失/大小不符时不能写 stamp，否则会掩盖损坏
            Logger.warning(TAG, "Incomplete component, skip writing stamp: ${directory.absolutePath}")
            return
        }
        save(
            stampFile,
            VerifyStamp(
                generationKey = manifestHash,
                files = records.toSortedMap()
            )
        )
    }

    private fun sessionFlagFile(stampKey: String): File =
        File(PathManager.DIR_VERIFY_STAMPS, "$SESSION_FLAG_FILE.$stampKey")

    /** Stage 4a 初版使用的全局标记，仅用于兼容读取/清理 */
    private fun legacySessionFlagFile(): File = File(PathManager.DIR_VERIFY_STAMPS, SESSION_FLAG_FILE)

    private fun safeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        //名称被清洗过时追加哈希，避免 "a/b" 与 "a_b" 落到同一个 stamp
        return if (sanitized == name && sanitized.isNotEmpty()) {
            sanitized
        } else {
            "${sanitized.ifEmpty { "version" }}-${sha1Hex(name.toByteArray(Charsets.UTF_8)).take(8)}"
        }
    }
}

/**
 * 一次游戏文件验证会话：负责读取/写回版本 stamp，并分别统计 stamp 跳过与全量哈希的文件数。
 */
class GameVerifySession private constructor(
    private val baseDir: File,
    private val stampKey: String,
    private val stampFile: File,
    private val generationKey: String,
    private val previous: VerifyStamp?,
    /** false 表示本次忽略旧记录（崩溃、世代键变化或没有 stamp） */
    private val useStamps: Boolean
) {
    private val records = ConcurrentHashMap<String, VerifyFileRecord>()
    private val stampSkipped = AtomicInteger()
    private val hashed = AtomicInteger()

    /** 仅凭 size/mtime 判定合格的文件数 */
    val stampSkippedCount: Int get() = stampSkipped.get()

    /** 实际走了哈希（或存在性）校验的文件数 */
    val hashedCount: Int get() = hashed.get()

    /**
     * stamp 命中时不读文件内容；client jar 永远返回 false 以确保被哈希
     */
    fun isStampValid(task: DownloadTask): Boolean {
        if (!useStamps || task.alwaysHash) return false
        val sha1 = task.sha1 ?: return false
        val file = task.targetFile
        val path = VerifyStampStore.relativePath(baseDir, file) ?: return false
        val record = previous?.files?.get(path) ?: return false
        if (!record.sha1.equals(sha1, ignoreCase = true)) return false
        if (!file.isFile) return false
        if (file.length() != record.size) return false
        if (file.lastModified() != record.mtime) return false

        records[path] = record
        stampSkipped.incrementAndGet()
        return true
    }

    /**
     * 记录一个走了普通校验的文件；只有校验通过才写入新记录
     */
    fun onHashed(task: DownloadTask, valid: Boolean) {
        hashed.incrementAndGet()
        if (valid) record(task)
    }

    /**
     * 下载/修复完成后记录文件（下载引擎已校验过 SHA-1）
     */
    fun onDownloaded(task: DownloadTask) {
        record(task)
    }

    /**
     * 本地校验阶段结束时调用：若本次已全量哈希，说明上次非正常退出/世代匹配问题已处理完毕
     */
    fun onVerificationPassFinished() {
        if (!useStamps) VerifyStampStore.clearUncleanSession(stampKey)
    }

    /**
     * 持久化本次验证结果；只保留当前任务集合内的记录，顺带清理陈旧条目
     */
    fun finish() {
        val snapshot = records.toMap()
        if (snapshot.isEmpty()) return
        VerifyStampStore.save(
            stampFile,
            VerifyStamp(
                generationKey = generationKey,
                files = snapshot.toSortedMap()
            )
        )
    }

    private fun record(task: DownloadTask) {
        val sha1 = task.sha1 ?: return
        val file = task.targetFile
        if (!file.isFile) return
        val path = VerifyStampStore.relativePath(baseDir, file) ?: return
        records[path] = VerifyFileRecord(
            path = path,
            size = file.length(),
            mtime = file.lastModified(),
            sha1 = sha1
        )
    }

    companion object {
        private const val SESSION_TAG = "GameVerifySession"

        fun open(versionId: String, baseDir: File, generationKey: String): GameVerifySession {
            val stampKey = VerifyStampStore.gameStampKey(baseDir.absolutePath, versionId)
            val stampFile = VerifyStampStore.gameStampFile(stampKey)
            val previous = VerifyStampStore.load(stampFile)
            val unclean = VerifyStampStore.hasUncleanSession(stampKey)
            if (unclean) {
                Logger.info(SESSION_TAG, "Previous game session did not exit cleanly, forcing full verification")
            }
            if (previous != null && previous.generationKey != generationKey) {
                Logger.info(SESSION_TAG, "Verify stamp generation changed for $versionId, forcing full hashing")
            }
            val useStamps = !unclean &&
                    previous != null &&
                    previous.generationKey == generationKey
            return GameVerifySession(
                baseDir = baseDir,
                stampKey = stampKey,
                stampFile = stampFile,
                generationKey = generationKey,
                previous = previous,
                useStamps = useStamps
            )
        }
    }
}
