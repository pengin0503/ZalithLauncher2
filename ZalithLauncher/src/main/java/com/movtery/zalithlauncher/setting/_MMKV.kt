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

package com.movtery.zalithlauncher.setting

import com.movtery.zalithlauncher.BuildKeys
import com.tencent.mmkv.MMKV

/**
 * 启动器全局 MMKV，管理所有设置项
 *
 * ST2: `mmkvWithID()` is a JNI lookup. Settings are read ~117 times on every
 * resume, so keep one process-level handle instead of looking it up per value.
 * MULTI_PROCESS_MODE keeps reads backed by the shared mmap, so changes made by
 * another process are still visible without re-opening the instance.
 */
private val launcherMmkv: MMKV by lazy {
    MMKV.mmkvWithID(BuildKeys.LAUNCHER_IDENTIFIER, MMKV.MULTI_PROCESS_MODE)
}

fun launcherMMKV(): MMKV = launcherMmkv
