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
import android.content.Intent
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.coroutine.Task
import com.movtery.zalithlauncher.coroutine.TaskFlowExecutor
import com.movtery.zalithlauncher.coroutine.TitledTask
import com.movtery.zalithlauncher.coroutine.addTask
import com.movtery.zalithlauncher.coroutine.buildPhase
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.auth_server.AuthServerHelper
import com.movtery.zalithlauncher.game.account.isLocalAccount
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.game.account.isReloginRequired
import com.movtery.zalithlauncher.game.account.microsoft.validateAccessToken
import com.movtery.zalithlauncher.game.account.refreshMicrosoft
import com.movtery.zalithlauncher.game.download.game.GameLibDownloader
import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.renderer.detectFpeEmulatorMods
import com.movtery.zalithlauncher.game.renderer.isSfpewConfiguredFor
import com.movtery.zalithlauncher.game.renderer.loadRenderer
import com.movtery.zalithlauncher.game.support.lwjgl3ify.patchLwjgl3ifyIfNeeded
import com.movtery.zalithlauncher.game.version.download.BaseMinecraftDownloader
import com.movtery.zalithlauncher.game.version.download.DownloadMode
import com.movtery.zalithlauncher.game.version.download.MinecraftDownloader
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.game.version.installed.VersionInfoParser
import com.movtery.zalithlauncher.game.version.installed.hasVulkanBackend
import com.movtery.zalithlauncher.game.version.installed.utils.isBiggerVer
import com.movtery.zalithlauncher.game.version.installed.utils.isLowerVer
import com.movtery.zalithlauncher.game.version.mod.AllModReader
import com.movtery.zalithlauncher.game.version.mod.isEnabled
import com.movtery.zalithlauncher.game.versioninfo.models.GameManifest
import com.movtery.zalithlauncher.ui.activities.runGame
import com.movtery.zalithlauncher.ui.activities.startGameService
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.network.isNetworkAvailable
import com.movtery.zalithlauncher.utils.string.getMessageOrToString
import com.movtery.zalithlauncher.viewmodel.ErrorViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 账号凭据已被服务端拒绝 */
private class LaunchReloginRequired(
    val account: Account
) : RuntimeException()

/** 账号校验或刷新失败时抛出 */
private class LaunchCheckFailed(
    val account: Account,
    cause: Throwable
) : RuntimeException(cause)

/** 用户在渲染器不兼容确认框选择取消时抛出 */
private class LaunchRendererDeclined : RuntimeException("Launch declined at renderer compatibility check")


/**
 * 游戏启动器
 */
class GameLaunchFlow(scope: CoroutineScope) {
    private val taskExecutor = TaskFlowExecutor(scope)
    val tasksFlow: StateFlow<List<TitledTask>> = taskExecutor.tasksFlow

    /** FF-F1-1：本次启动是否已提前拉起 :game 服务，用于失败/取消时回收 */
    @Volatile
    private var servicePrewarmed = false

    /** 是否已走到 runGame；进入后不再回收 :game 服务 */
    @Volatile
    private var gameStarted = false

    /** 提前启动服务时使用的 Context，用于 stopService */
    @Volatile
    private var prewarmContext: Context? = null

    /** FF-F1-3：与校验任务并行的账号校验任务 */
    @Volatile
    private var accountCheckJob: Job? = null

    /**
     * 启动游戏
     * @param version 指定版本
     * @param skipAccountRefresh 跳过启动前的账号校验，直接使用现有凭据
     * @param ensureVulkanSupported 校验设备 Vulkan 能力，不满足时挂起展示检测流程
     * @param onRendererUnsupported 挂起展示渲染器不兼容确认框，返回是否忽略风险继续启动
     * @param onRendererDeclined 用户在渲染器不兼容确认框选择取消
     */
    fun launch(
        context: Context,
        version: Version,
        skipAccountRefresh: Boolean = false,
        exitActivity: () -> Unit,
        ensureVulkanSupported: suspend (Version) -> Boolean = { true },
        onRendererUnsupported: suspend (RendererInterface, Version) -> Boolean = { _, _ -> true },
        onRendererDeclined: () -> Unit = {},
        onReloginRequired: (Account) -> Unit = {},
        onRefreshFailed: (Account, Throwable) -> Unit = { _, _ -> },
        isRunning: () -> Unit = {},
        onComplete: () -> Unit,
        onError: (ErrorViewModel.ThrowableMessage) -> Unit,
    ) {
        if (taskExecutor.isRunning()) {
            //正在启动中，阻止这次启动请求
            isRunning()
            return
        }

        val account = AccountsManager.currentAccountFlow.value ?: return
        var errorReported = false
        val handleError: (ErrorViewModel.ThrowableMessage) -> Unit = { message ->
            if (!errorReported) {
                errorReported = true
                onError(message)
            }
        }

        // FF-F1-1：在账号校验、模组扫描与完整性校验开始前先拉起 :game 服务，
        // 让 :game 进程 fork 与 ZLApplication.onCreate 与这些任务并行；
        // runGame 不再重复启动服务（重复 startService 本身也是幂等的）。
        servicePrewarmed = false
        gameStarted = false
        prewarmContext = context.applicationContext
        startGameService(context)
        servicePrewarmed = true

        taskExecutor.executePhasesAsync(
            onStart = {
                try {
                    //当前任务流 Job 的子协程作用域，流程取消时并行的账号校验会被一并取消
                    val flowScope = CoroutineScope(currentCoroutineContext())
                    taskExecutor.addPhases(
                        listOf(
                            buildLaunchPhases(
                                context = context,
                                version = version,
                                account = account,
                                skipAccountRefresh = skipAccountRefresh,
                                exitActivity = exitActivity,
                                ensureVulkanSupported = ensureVulkanSupported,
                                onRendererUnsupported = onRendererUnsupported,
                                onError = handleError,
                                flowScope = flowScope
                            )
                        )
                    )
                } catch (e: Throwable) {
                    //阶段构建失败也算启动失败，回收提前拉起的服务
                    stopPrewarmedService()
                    throw e
                }
            },
            onComplete = onComplete,
            onError = { th ->
                cancelAccountCheck()
                stopPrewarmedService()
                when (th) {
                    is LaunchReloginRequired -> onReloginRequired(th.account)
                    is LaunchCheckFailed -> onRefreshFailed(th.account, th.cause ?: th)
                    is LaunchRendererDeclined -> onRendererDeclined()
                    else -> handleError(
                        ErrorViewModel.ThrowableMessage(
                            title = androidText(R.string.generic_error),
                            message = androidText(th.getMessageOrToString())
                        )
                    )
                }
            },
            onCancel = {
                cancelAccountCheck()
                stopPrewarmedService()
            },
        )
    }

    /**
     * 取消当前的启动流程
     */
    fun cancel() {
        cancelAccountCheck()
        taskExecutor.cancel()
        stopPrewarmedService()
    }

    private fun cancelAccountCheck() {
        accountCheckJob?.cancel()
        accountCheckJob = null
    }

    /**
     * 启动失败/被取消且尚未进入 runGame 时，停止提前拉起的 :game 服务，避免残留进程
     */
    private fun stopPrewarmedService() {
        if (!servicePrewarmed || gameStarted) return
        servicePrewarmed = false
        prewarmContext?.let { context ->
            runCatching {
                context.stopService(Intent(context, GameService::class.java))
            }
        }
    }

    private fun buildLaunchPhases(
        context: Context,
        version: Version,
        account: Account,
        skipAccountRefresh: Boolean,
        exitActivity: () -> Unit,
        ensureVulkanSupported: suspend (Version) -> Boolean,
        onRendererUnsupported: suspend (RendererInterface, Version) -> Boolean,
        onError: (ErrorViewModel.ThrowableMessage) -> Unit,
        flowScope: CoroutineScope
    ): TaskFlowExecutor.TaskPhase {
        //检查是否联网，根据这个条件决定是否校验账号
        //以及，没有联网时，让微软账号、外置账号作为离线账号登录
        val hasNetwork = isNetworkAvailable(context)
        if (!hasNetwork && !account.isLocalAccount()) {
            version.offlineAccountLogin = true
        }

        return buildPhase {
            var launchManifest: GameManifest? = null

            // FF-F1-3：TaskFlowExecutor 阶段内任务严格串行，无法在任务模型中表达并行。
            // 因此将账号校验作为任务流 Job 的子协程提前启动，与 MOD 扫描、完整性校验并行；
            // 在 runGame 前的“账号校验”任务中 join，失败仍抛出原有异常类型，
            // 由外层 onError 走与之前完全一致的 LaunchReloginRequired/LaunchCheckFailed 路径。
            val needsAccountCheck = hasNetwork && !skipAccountRefresh &&
                    AccountsManager.isLaunchCheckNeeded(account)
            val accountCheck = CompletableDeferred<Throwable?>()
            val accountTask = if (needsAccountCheck) {
                //该任务只负责等待并转发并行校验的结果，账号校验已在后台开始
                Task.runTask(
                    dispatcher = Dispatchers.IO,
                    task = {
                        val failure = accountCheck.await()
                        if (failure != null) {
                            if (failure is CancellationException) throw failure
                            if (failure.isReloginRequired()) throw LaunchReloginRequired(account)
                            throw LaunchCheckFailed(account, failure)
                        }
                    }
                ).also { task ->
                    accountCheckJob = flowScope.launch(Dispatchers.IO) {
                        try {
                            if (account.isMicrosoftAccount()) {
                                checkMicrosoftAccount(task, account)
                            } else {
                                checkOtherAccount(task, context, account)
                            }
                            AccountsManager.markSessionValidated(account)
                            AccountsManager.suspendSaveAccount(account)
                            accountCheck.complete(null)
                        } catch (e: CancellationException) {
                            accountCheck.completeExceptionally(e)
                            throw e
                        } catch (e: Throwable) {
                            //异常延迟到 join 时再抛出，保持原有的启动失败语义
                            accountCheck.complete(e)
                        }
                    }
                }
            } else null

            // 检查游戏环境
            // 扫描模组列表，开启对应功能，并检查渲染器兼容性
            addTask(
                icon = R.drawable.ic_extension_outlined,
                title = androidText(R.string.launch_check_environment),
                dispatcher = Dispatchers.IO
            ) { task ->
                val patchedManifest = checkMods(version)
                checkRendererSupport(
                    version = version,
                    ensureVulkanSupported = ensureVulkanSupported,
                    onRendererUnsupported = onRendererUnsupported
                )

                val manifest = patchedManifest ?: VersionInfoParser(version)
                    .setInheriting(skipIfNotExists = true)
                    .build()
                val manifestString = GSON.toJson(manifest)

                launchManifest = manifest
                version.launchManifest = manifestString

                // 如果打了补丁，此处需要重新检索一下依赖库并下载
                patchedManifest?.let {
                    val libDownloader = GameLibDownloader(
                        downloader = BaseMinecraftDownloader(version.getGameHome()),
                        gameJson = manifestString
                    )
                    libDownloader.schedule(task)
                    libDownloader.download(task)
                }
            }

            if (!version.skipGameIntegrityCheck()) {
                //校验并修复游戏文件
                addTask(
                    icon = R.drawable.ic_assignment_filled,
                    title = androidText(R.string.minecraft_download_stat_verify_task),
                    task = createGameDownloadTask(
                        context = context,
                        version = version,
                        onError = onError
                    )
                )
            }

            //账号校验任务仅在此处 join：正常情况下校验早已在后台完成，
            //异常（如需重新登录）也在这里才抛出，启动因此失败的方式与之前一致
            accountTask?.let { task ->
                addTask(
                    icon = R.drawable.ic_login,
                    title = androidText(R.string.account_logging_in, account.username),
                    task = task
                )
            }

            //启动游戏
            addTask(
                icon = R.drawable.ic_rocket_launch_filled,
                title = androidText(R.string.main_launch_game)
            ) { _ ->
                launchManifest?.let { manifest ->
                    version.launchManifest = GSON.toJson(
                        VersionInfoParser(version)
                            .setManifest(manifest)
                            .setInheriting()
                            .build()
                    )
                }
                //成功拉起 VMActivity 后不再回收预热的 :game 服务
                runGame(context, version, account)
                gameStarted = true
                exitActivity()
            }
        }
    }

    /**
     * 微软账号：向服务端校验缓存的凭据，被拒绝或已临近过期时静默刷新
     */
    private suspend fun checkMicrosoftAccount(task: Task, account: Account) {
        val expired = System.currentTimeMillis() > account.expiresAt - 5 * 60 * 1000
        if (expired || !validateAccessToken(account)) {
            account.refreshMicrosoft(task, currentCoroutineContext())
        }
    }

    /**
     * 外置账号：依次尝试 validate 与 refresh，均被服务端拒绝时再用账号密码重新登录
     */
    private suspend fun checkOtherAccount(task: Task, context: Context, account: Account) {
        task.updateMessage(androidText(R.string.account_logging_in, account.username))
        val helper = AuthServerHelper(
            baseUrl = account.otherBaseUrl!!,
            serverName = account.accountType!!,
            email = account.otherAccount!!,
            password = account.otherPassword!!
        )
        if (!helper.validateOrRefresh(context, account)) {
            helper.passwordLogin(context, account)
        }
    }

    private fun createGameDownloadTask(
        context: Context,
        version: Version,
        onError: (ErrorViewModel.ThrowableMessage) -> Unit
    ): Task {
        return MinecraftDownloader(
            context = context,
            version = version.getVersionInfo()?.minecraftVersion ?: version.getVersionName(),
            customName = version.getVersionName(),
            gameHome = version.getGameHome(),
            mode = DownloadMode.VERIFY_AND_REPAIR,
            onError = { message ->
                onError(
                    ErrorViewModel.ThrowableMessage(
                        title = androidText(R.string.minecraft_download_failed),
                        message = androidText(message)
                    )
                )
            }
        ).getDownloadTask()
    }

    /**
     * 扫描模组列表并开启对应功能
     * @return 经过补丁的游戏清单，为 null 则表示没什么补丁
     */
    private suspend fun checkMods(version: Version): GameManifest? {
        val modsDir = VersionFolders.MOD.getDir(version.getGameDir())
        val mods = AllModReader(modsDir).readAllLocals()
        version.fpeEmulatorModDetected = detectFpeEmulatorMods(mods)

        if (mods.any { it.id == "touchcontroller" && it.file.isEnabled() }) {
            version.enableTouchProxy = true
        }

        val patched = patchLwjgl3ifyIfNeeded(version, mods)
        return patched
    }

    /**
     * 渲染器兼容性检查
     */
    private suspend fun checkRendererSupport(
        version: Version,
        ensureVulkanSupported: suspend (Version) -> Boolean,
        onRendererUnsupported: suspend (RendererInterface, Version) -> Boolean
    ) {
        val renderer = Renderers.setCurrentRenderer(version.getRenderer())
        val mcVer = version.getVersionInfo()?.minecraftVersion ?: version.getVersionName()

        // 设备完全支持 Vulkan 时，游戏走 Vulkan 后端渲染，跳过渲染器的版本支持检查
        if (version.hasVulkanBackend() && ensureVulkanSupported(version)) return

        val loaded = loadRenderer(renderer, version)
        // FPE 支持可来自 SFPEW 兼容层，也可来自检测到的 FPE 模拟模组
        val fpeProvided = version.fpeEmulatorModDetected ||
                (loaded.sfpewLayerEnabled ?: isSfpewConfiguredFor(renderer, version))
        val unsupportedMin = loaded.getMinMCVersion()?.let { !fpeProvided && mcVer.isLowerVer(it) } ?: false
        val unsupportedMax = loaded.getMaxMCVersion()?.let { mcVer.isBiggerVer(it) } ?: false

        if ((unsupportedMin || unsupportedMax) && !onRendererUnsupported(renderer, version)) {
            throw LaunchRendererDeclined()
        }
    }
}
