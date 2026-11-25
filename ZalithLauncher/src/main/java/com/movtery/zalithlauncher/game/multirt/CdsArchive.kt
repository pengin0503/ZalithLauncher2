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

package com.movtery.zalithlauncher.game.multirt

import android.content.Context
import androidx.compose.ui.unit.IntSize
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.components.jre.Jre
import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.game.download.jvm_server.listBlockingProcesses
import com.movtery.zalithlauncher.game.download.jvm_server.startJvmServiceAndWaitExit
import com.movtery.zalithlauncher.game.launch.JvmLaunchInfo
import com.movtery.zalithlauncher.game.launch.JvmLauncher
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.device.Architecture
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private const val TAG = "CdsArchive"

/** 单次归档生成的等待上限，超时后放弃并保留失败标记，避免反复拖慢启动 */
private val CDS_DUMP_TIMEOUT = 90.seconds

/** 同一 app 版本内最多尝试生成的次数（含首次） */
private const val CDS_MAX_ATTEMPTS = 3

/** 同一 app 版本内两次失败重试之间的最小间隔 */
private val CDS_RETRY_INTERVAL = 24.hours

/**
 * 游戏 JRE（21/25）的 CDS（Class Data Sharing）归档管理。
 *
 * 仓库内打包的 JRE 是 Android/bionic 版 HotSpot，仅带 `lib/classlist`、不带
 * `lib/server/classes.jsa`；归档与 CPU 架构相关，CI（x86）无法为 ARM 设备预生成，
 * 因此只能在设备上生成一次，保存在运行时目录内。
 *
 * 归档由独立的 `:jvm` 进程（复用安装器 JVM 机制）执行 `-Xshare:dump` 生成：
 * 主进程内直接 `JLI_Launch` 会让进程在 VM 初始化阶段退出，不能放在启动器进程里。
 */
object CdsArchive {
    private val supportedJres = setOf(Jre.JRE_21, Jre.JRE_25)
    private val supportedVersions = setOf(21, 25)

    /** 同一时刻只允许一个生成任务（`:jvm` 服务与 UDP 端口都是单例） */
    private val generationMutex = Mutex()

    fun archiveFile(runtimeHome: File): File = File(runtimeHome, "cds/classes.jsa")

    private fun tmpArchiveFile(runtimeHome: File): File = File(runtimeHome, "cds/classes.jsa.tmp")

    fun classListFile(runtimeHome: File): File = File(runtimeHome, "lib/classlist")

    private fun failureMarker(runtimeHome: File): File =
        File(runtimeHome, "cds/generation.failed")

    /**
     * 是否需要（重新）生成归档。缺失 classlist、本次运行已失败、或失败标记不允许重试时
     * 返回 false，避免让 Splash 的安装项永远处于 PENDING（见 [retryAllowed]）。
     */
    fun needsGeneration(jre: Jre): Boolean {
        if (jre !in supportedJres) return false
        val home = File(PathManager.DIR_MULTIRT, jre.jreName)
        if (!home.isDirectory) return false
        val archive = archiveFile(home)
        if (archive.isFile && archive.length() > 0) return false
        if (!retryAllowed(jre, home)) return false
        return classListFile(home).isFile
    }

    /**
     * 本次进程内已经失败过的运行时：同一次 app 运行里绝不重试，避免循环。
     */
    private val failedThisRun: MutableSet<Jre> = Collections.synchronizedSet(mutableSetOf())

    /**
     * 失败标记内容（key=value 行）。旧版本写入的纯文本标记无法解析出字段，
     * 视为“1 次失败、app 版本未知（=已变化）”，因此升级后会再尝试一次。
     */
    private data class FailureRecord(
        val attempts: Int,
        val lastFailureMs: Long,
        val appVersionCode: Long?
    )

    private fun readFailure(marker: File): FailureRecord? {
        if (!marker.exists()) return null
        val fields = runCatching {
            marker.readLines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }.toMap()
        }.getOrDefault(emptyMap())
        return FailureRecord(
            attempts = fields["attempts"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            lastFailureMs = fields["lastFailure"]?.toLongOrNull() ?: marker.lastModified(),
            appVersionCode = fields["appVersion"]?.toLongOrNull()
        )
    }

    /**
     * 有限重试：本次运行已失败过 → 不重试；没有失败标记 → 允许；
     * app 版本与失败时不同 → 允许；否则需尝试次数 < [CDS_MAX_ATTEMPTS] 且距上次失败 ≥ [CDS_RETRY_INTERVAL]。
     */
    private fun retryAllowed(jre: Jre, home: File): Boolean {
        if (jre in failedThisRun) return false
        val record = readFailure(failureMarker(home)) ?: return true
        if (record.appVersionCode != BuildConfig.VERSION_CODE.toLong()) return true
        if (record.attempts >= CDS_MAX_ATTEMPTS) return false
        val elapsedMs = System.currentTimeMillis() - record.lastFailureMs
        // 时钟回拨（elapsed < 0）时按“未到间隔”处理，等下一次正常的时间点
        return elapsedMs >= CDS_RETRY_INTERVAL.inWholeMilliseconds
    }

    /**
     * 游戏启动时可用的归档；只对启动器自带的 21/25 运行时生效。
     */
    fun findExisting(runtime: Runtime, runtimeHome: String): File? {
        if (!runtime.isProvidedByLauncher || runtime.javaVersion !in supportedVersions) return null
        val archive = archiveFile(File(runtimeHome))
        return archive.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * 在运行时安装完成后调用。生成失败只记录日志并写入失败标记，绝不影响启动。
     */
    suspend fun ensureInstalled(jre: Jre) {
        if (!needsGeneration(jre)) return
        generationMutex.withLock {
            if (!needsGeneration(jre)) return@withLock
            try {
                generate(jre)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markFailed(jre, e)
                Logger.warning(
                    TAG,
                    "CDS generation failed for ${jre.jreName}; the game will start without CDS",
                    e
                )
            }
        }
    }

    /**
     * 等待上一次生成 CDS 时留下的 :jvm 进程退出。
     *
     * JvmService 发送退出码后还会等约 500ms 才 kill 自身，连续生成 21/25 时
     * 第二个运行时可能恰好看到这个尚未退出的 :jvm。若阻塞进程里还有 :game
     * （游戏仍在运行），则不做等待，直接返回让调用方放弃本次生成。
     */
    private suspend fun awaitDumpProcessGone(timeoutMs: Long = 2_000L): List<String> {
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val blocking = runCatching { listBlockingProcesses(GlobalContext) }.getOrNull().orEmpty()
            val onlyJvm = blocking.isNotEmpty() && blocking.all { it.contains(":jvm(") }
            if (!onlyJvm || System.nanoTime() >= deadlineNanos) return blocking
            delay(200L)
        }
    }

    private suspend fun generate(jre: Jre) {
        val home = File(PathManager.DIR_MULTIRT, jre.jreName)
        val archive = archiveFile(home)
        if (archive.isFile && archive.length() > 0) return

        val classList = classListFile(home)
        if (!classList.isFile) {
            // 21/25 的 universal 包内自带 lib/classlist，正常安装不会走到这里。
            // 不做 -XX:DumpLoadedClassList 兜底：那需要再跑一次完整 JVM，且没有可用的最小 main 类。
            Logger.warning(TAG, "CDS classlist missing at ${classList.absolutePath}; skipping ${jre.jreName}")
            return
        }

        // 不干扰正在运行的安装器 JVM / 游戏进程（两者都复用同一个 :jvm 服务与 UDP 端口）
        val blockingProcesses = awaitDumpProcessGone()
        if (blockingProcesses.isNotEmpty()) {
            Logger.info(TAG, "Blocking JVM process(es) $blockingProcesses; deferring CDS generation for ${jre.jreName}")
            return
        }

        val tmp = tmpArchiveFile(home)
        tmp.parentFile?.mkdirs()
        if (tmp.exists()) tmp.delete()

        Logger.info(TAG, "Generating CDS archive for ${jre.jreName} at ${archive.absolutePath}")
        val exitCode = startJvmServiceAndWaitExit(
            jvmArgs = "",
            jreName = jre.jreName,
            userHome = PathManager.DIR_FILES_EXTERNAL.absolutePath,
            cdsDump = true,
            cdsArchivePath = tmp.absolutePath,
            cdsClassListPath = classList.absolutePath,
            timeout = CDS_DUMP_TIMEOUT
        )

        if (exitCode != 0 || !tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            throw IOException("CDS dump did not produce an archive (exit=$exitCode)")
        }

        if (archive.exists()) archive.delete()
        archive.parentFile?.mkdirs()
        if (!tmp.renameTo(archive)) {
            tmp.delete()
            throw IOException("Failed to move CDS archive to ${archive.absolutePath}")
        }
        failureMarker(home).delete()
        Logger.info(TAG, "CDS archive generated for ${jre.jreName}: ${archive.length()} bytes")
    }

    private fun markFailed(jre: Jre, error: Exception) {
        failedThisRun.add(jre)
        runCatching {
            val home = File(PathManager.DIR_MULTIRT, jre.jreName)
            // 运行时已被删除（用户取消/卸载）时不要重新创建目录
            if (!home.isDirectory) return@runCatching
            val marker = failureMarker(home)
            val versionCode = BuildConfig.VERSION_CODE.toLong()
            val previous = readFailure(marker)
            // app 版本变化后重新计数
            val attempts = if (previous != null && previous.appVersionCode == versionCode) {
                previous.attempts + 1
            } else 1
            val message = (error.message ?: error.javaClass.name).replace('\n', ' ')
            marker.parentFile?.mkdirs()
            marker.writeText(
                "attempts=$attempts\n" +
                    "lastFailure=${System.currentTimeMillis()}\n" +
                    "appVersion=$versionCode\n" +
                    "message=$message\n"
            )
        }.onFailure {
            Logger.warning(TAG, "Failed to write CDS failure marker for ${jre.jreName}", it)
        }
    }
}

/**
 * 只在 `:jvm` 进程里使用的启动器：用运行时自带的 classlist 执行 `-Xshare:dump`。
 *
 * 不带 main class 是可行的：`-Xshare:dump` 在 `JNI_CreateJavaVM` 的 VM 初始化阶段
 * 就完成归档并退出，`JavaMain` 的主类检查不会被执行到（与 `java -Xshare:dump` 一致）。
 */
internal class CdsDumpLauncher(
    private val appContext: Context,
    private val runtimeName: String,
    private val targetArchive: File,
    private val targetClassList: File,
    onExit: (code: Int, isSignal: Boolean) -> Unit
) : JvmLauncher(
    context = appContext,
    jvmLaunchInfo = JvmLaunchInfo(
        jvmArgs = "",
        jreName = runtimeName,
        userHome = null,
        useUserJvm = false
    ),
    onExit = onExit,
    openPath = { /* 忽略 */ }
) {
    override suspend fun launch(screenSize: IntSize): Int {
        runtime = RuntimesManager.forceReload(runtimeName)
        // dump 不加载 LWJGL，也不应触发组件解包；仅给通用启动参数填一个合法路径
        lwjglNativesDir = File(
            PathManager.DIR_COMPONENTS,
            "lwjgl/3.3.3/natives/${Architecture.archAsStringAndroid(Architecture.getDeviceArchitecture())}"
        ).absolutePath

        return launchJvm(
            context = appContext,
            jvmArgs = listOf(
                "-Xshare:dump",
                "-XX:SharedClassListFile=${targetClassList.absolutePath}",
                "-XX:SharedArchiveFile=${targetArchive.absolutePath}"
            ),
            userHome = PathManager.DIR_FILES_EXTERNAL.absolutePath,
            userArgs = "",
            screenSize = screenSize,
            useLocalLanguage = false
        )
    }
}
