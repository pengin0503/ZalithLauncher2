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

package com.movtery.zalithlauncher.game.plugin.renderer

import android.content.Context
import android.os.Build
import com.movtery.zalithlauncher.game.plugin.Plugin
import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.tencent.mmkv.MMKV

/**
 * FF-F2-3：主进程解析出的“当前选中渲染器插件”身份缓存，供 :game 进程定向加载。
 *
 * 只缓存包名和 versionCode：:game 启动时若包已卸载或 versionCode 不一致，就回退到全量扫描。
 * 选择内置渲染器时清空缓存（:game 仍需全量扫描以加载原生库插件等其他插件）。
 */
object RendererPluginCache {
    private const val MMKV_ID = "RendererPluginSelection"
    private const val KEY_PACKAGE_NAME = "packageName"
    private const val KEY_VERSION_CODE = "versionCode"

    data class Selection(
        val packageName: String,
        val versionCode: Long
    )

    private val mmkv get() = MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)

    /**
     * 读取缓存；缓存缺失或字段不完整时返回 null。
     */
    fun read(): Selection? {
        val packageName = mmkv.getString(KEY_PACKAGE_NAME, null) ?: return null
        if (!mmkv.containsKey(KEY_VERSION_CODE)) return null
        return Selection(
            packageName = packageName,
            versionCode = mmkv.getLong(KEY_VERSION_CODE, -1L)
        )
    }

    /**
     * 在主进程解析出当前渲染器后调用。
     * 内置渲染器没有插件包，清空缓存；插件包已不存在时同样清空。
     */
    fun save(context: Context, renderer: RendererInterface) {
        val plugin = renderer as? Plugin
        if (plugin == null) {
            clear()
            return
        }
        val packageName = plugin.getIdentifier()
        val versionCode = getVersionCode(context, packageName)
        if (versionCode == null) {
            clear()
            return
        }
        mmkv.putString(KEY_PACKAGE_NAME, packageName).apply()
        mmkv.putLong(KEY_VERSION_CODE, versionCode).apply()
    }

    fun clear() {
        mmkv.remove(KEY_PACKAGE_NAME).apply()
        mmkv.remove(KEY_VERSION_CODE).apply()
    }

    private fun getVersionCode(context: Context, packageName: String): Long? {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.getOrNull() ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
}
