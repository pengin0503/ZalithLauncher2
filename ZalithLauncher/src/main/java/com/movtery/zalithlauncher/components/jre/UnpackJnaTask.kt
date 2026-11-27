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

package com.movtery.zalithlauncher.components.jre

import android.content.Context
import com.movtery.zalithlauncher.components.AssetManifest
import com.movtery.zalithlauncher.components.ComponentFile
import com.movtery.zalithlauncher.components.UnpackSingleTask
import com.movtery.zalithlauncher.components.toComponentFile
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.device.Architecture

class UnpackJnaTask(context: Context) : UnpackSingleTask(
    context = context,
    rootDir = PathManager.DIR_JNA,
    assetsDirName = "runtimes/jna",
    fileDirName = "jna"
) {
    override fun selectFiles(manifest: AssetManifest): List<ComponentFile> {
        val abi = Architecture.archAsStringAndroid(Architecture.getDeviceArchitecture())
        val declaredVersions = manifest.files.mapTo(HashSet()) { it.path.substringBefore('/') }
        val files = manifest.files.flatMap { file ->
            val parts = file.path.split('/')
            when {
                // 5.17.0 / 5.18.0 / 5.19.1 have byte-identical natives, so they
                // share one asset that is installed into every version directory.
                parts.size == 4 && parts[0] == SHARED_ASSET_DIR && parts[1] == "natives" &&
                    parts[2] == abi && parts[3] == "libjnidispatch.so" -> {
                    SHARED_VERSIONS.map { version -> file.toComponentFile("$version/libjnidispatch.so") }
                }
                // Older JNA versions still ship one native per version directory.
                parts.size == 4 && parts[1] == "natives" && parts[3] == "libjnidispatch.so" -> {
                    if (parts[2] == abi) listOf(file.toComponentFile("${parts[0]}/libjnidispatch.so"))
                    else emptyList()
                }
                else -> throw IllegalArgumentException("Unsupported JNA asset path: ${file.path}")
            }
        }
        val expectedVersions = declaredVersions - SHARED_ASSET_DIR + SHARED_VERSIONS
        require(files.mapTo(HashSet()) { it.outputPath.substringBefore('/') } == expectedVersions) {
            "JNA assets are incomplete for ABI: $abi"
        }
        return files
    }

    private companion object {
        const val SHARED_ASSET_DIR = "shared"
        val SHARED_VERSIONS = listOf("5.17.0", "5.18.0", "5.19.1")
    }
}