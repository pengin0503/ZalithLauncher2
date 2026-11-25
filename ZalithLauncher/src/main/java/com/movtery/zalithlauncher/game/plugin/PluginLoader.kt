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

package com.movtery.zalithlauncher.game.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.movtery.zalithlauncher.game.plugin.driver.DriverPluginManager
import com.movtery.zalithlauncher.game.plugin.ffmpeg.FFmpegPluginManager
import com.movtery.zalithlauncher.game.plugin.natives.NativePluginManager
import com.movtery.zalithlauncher.game.plugin.renderer.RendererPluginCache
import com.movtery.zalithlauncher.game.plugin.renderer.RendererPluginManager
import com.movtery.zalithlauncher.game.plugin.renderer_v2.RendererV2PluginManager
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.utils.logging.Logger

/**
 * 统一插件的加载，保证仅获取一次应用列表
 */
object PluginLoader {
    private var isInitialized: Boolean = false
    private const val PACKAGE_FLAGS =
        PackageManager.GET_META_DATA or PackageManager.GET_SHARED_LIBRARY_FILES

    /**
     * 所有已加载的应用插件
     */
    var allPlugins: List<ApkPlugin> = emptyList()
        private set

    @JvmStatic
    @SuppressLint("QueryPermissionsNeeded")
    fun loadAllPlugins(context: Context, force: Boolean = false) {
        if (isInitialized && !force) return
        isInitialized = true

        val apkPluginList: MutableList<ApkPlugin> = mutableListOf()
        resetManagers(context)

        val queryIntentActivities =
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN),
                PACKAGE_FLAGS
            )
        queryIntentActivities.forEach { resolve ->
            val applicationInfo = resolve.activityInfo.applicationInfo
            runCatching {
                parseApplication(context, applicationInfo, apkPluginList)
            }.onFailure { e ->
                Logger.error("PluginLoader", "An exception was encountered while importing the software plugin ${applicationInfo.packageName}", e)
            }
        }
        FFmpegPluginManager.loadPlugin(context) { apkPluginList.add(it) }

        registerRenderers()
        finishPlugins(apkPluginList)
        // 缓存识别到的插件包名，供 :game 快速路径逐个定向解析（避免下次启动全量扫描）
        PluginPackageCache.save(allPlugins.map { it.packageName })
    }

    /**
     * FF-F2-3：:game 进程的快速路径。
     *
     * 主进程解析出当前渲染器后，会把插件包名和 versionCode 写入 [RendererPluginCache]；
     * 同时 [PluginPackageCache] 保存了主进程全量扫描识别到的全部插件包名。
     * 这里只对这些已知包定向解析，跳过 [PackageManager.queryIntentActivities] 的全量扫描；
     * 这样原生库插件、驱动器插件等也不会因为快速路径而丢失。
     * FFmpeg 插件包名固定，顺手直接加载，保持 :game 的 PATH/录制能力不变。
     *
     * @return true 表示已加载缓存中的渲染器插件；false 表示缓存缺失/过期，
     *         调用方应回退到 [loadAllPlugins]（force = true）。
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun loadSelectedRendererPlugin(context: Context): Boolean {
        if (isInitialized) return true
        val selection = RendererPluginCache.read() ?: return false
        val cachedPackages = PluginPackageCache.read()
        if (cachedPackages.isEmpty()) return false

        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(selection.packageName, PACKAGE_FLAGS)
        }.getOrNull() ?: return false

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        if (versionCode != selection.versionCode) return false

        val applicationInfo = packageInfo.applicationInfo ?: return false
        val apkPluginList: MutableList<ApkPlugin> = mutableListOf()
        resetManagers(context)

        val packageNames = mutableListOf<String>()
        packageNames.add(selection.packageName)
        cachedPackages.forEach { packageName ->
            if (packageName != selection.packageName) packageNames.add(packageName)
        }
        packageNames.forEach { packageName ->
            val info = (if (packageName == selection.packageName) {
                applicationInfo
            } else {
                runCatching {
                    context.packageManager.getPackageInfo(packageName, PACKAGE_FLAGS).applicationInfo
                }.getOrNull()
            }) ?: return@forEach
            runCatching {
                parseApplication(context, info, apkPluginList)
            }.onFailure { e ->
                Logger.error("PluginLoader", "Failed to load cached plugin $packageName", e)
            }
        }
        FFmpegPluginManager.loadPlugin(context) { apkPluginList.add(it) }

        registerRenderers()
        finishPlugins(apkPluginList)

        val selectedPluginLoaded =
            RendererPluginManager.getRendererList().any { it.packageName == selection.packageName } ||
                RendererV2PluginManager.getRendererList().any { it.packageName == selection.packageName }
        if (!selectedPluginLoaded) {
            // 包仍在、版本也匹配，但已不再声明渲染器：清掉半成品，让调用方回退全量扫描
            RendererPluginManager.clearPlugin()
            RendererV2PluginManager.clearPlugin()
            NativePluginManager.clearPlugin()
            allPlugins = emptyList()
            return false
        }

        isInitialized = true
        Logger.info(
            "PluginLoader",
            "Fast path: loaded cached renderer plugin ${selection.packageName} (versionCode=${selection.versionCode})"
        )
        return true
    }

    private fun resetManagers(context: Context) {
        DriverPluginManager.initDriver(context)
        RendererPluginManager.clearPlugin()
        RendererV2PluginManager.clearPlugin()
        NativePluginManager.clearPlugin()
    }

    private fun parseApplication(
        context: Context,
        applicationInfo: ApplicationInfo,
        apkPluginList: MutableList<ApkPlugin>
    ) {
        DriverPluginManager.parseApkPlugin(context, applicationInfo) { apkPluginList.add(it) }
        RendererV2PluginManager.parseApkPlugin(context, applicationInfo) { apkPluginList.add(it) }
        RendererPluginManager.parseApkPlugin(context, applicationInfo) { apkPluginList.add(it) }
        NativePluginManager.parseApkPlugin(context, applicationInfo) { apkPluginList.add(it) }
    }

    private fun registerRenderers() {
        // 加载旧架构渲染器插件
        RendererPluginManager.getRendererList().filter { plugin ->
            !Renderers.addRenderer(plugin)
        }.takeIf {
            it.isNotEmpty()
        }?.let { failedToLoadList ->
            RendererPluginManager.removeRenderer(failedToLoadList)
        }
        // 加载新架构渲染器插件
        RendererV2PluginManager.getRendererList().filter { plugin ->
            !Renderers.addRenderer(plugin)
        }.takeIf {
            it.isNotEmpty()
        }?.let { failedToLoadList ->
            RendererV2PluginManager.removeRenderer(failedToLoadList)
        }
    }

    private fun finishPlugins(apkPluginList: MutableList<ApkPlugin>) {
        // 去重已加载的插件
        val seenPackages = mutableSetOf<String>()
        apkPluginList.removeAll { !seenPackages.add(it.packageName) }

        //全部已加载的插件
        allPlugins = apkPluginList.sortedBy { it.appName }
    }
}
