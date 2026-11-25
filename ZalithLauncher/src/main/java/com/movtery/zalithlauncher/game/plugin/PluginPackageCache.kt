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

import com.tencent.mmkv.MMKV

/**
 * FF-F2-3：主进程全量扫描识别到的插件包名缓存。
 *
 * :game 进程用这份列表逐个 `getPackageInfo` 定向解析，跳过
 * `queryIntentActivities` 全量扫描；同时保证渲染器插件之外的原生库插件、
 * 驱动器插件等仍能被加载。
 */
object PluginPackageCache {
    private const val MMKV_ID = "PluginPackageList"
    private const val KEY_PACKAGES = "packages"

    private val mmkv get() = MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)

    fun save(packageNames: Collection<String>) {
        val value = packageNames.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString("\n")
        mmkv.putString(KEY_PACKAGES, value).apply()
    }

    fun read(): List<String> {
        val raw = mmkv.getString(KEY_PACKAGES, null) ?: return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.toList()
    }
}
