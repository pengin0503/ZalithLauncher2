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

package com.movtery.zalithlauncher.game.download.perfpack

import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.path.getVersionsHome
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.file.ensureParentDirectory
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.downloadFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import java.io.File

private const val TAG = "PerfPackInstaller"

/**
 * Best-effort installer for the curated performance pack.
 *
 * Every per-mod failure is contained: the only exception that escapes is
 * coroutine cancellation. Callers must launch this OFF the install task so a
 * network problem here can never make instance creation fail.
 */
object PerfPackInstaller {
    /** The loader of a freshly created instance, known from its install request. */
    fun loaderOf(info: GameDownloadInfo): PerfPackLoader? = when {
        info.neoforge != null -> PerfPackLoader.NEOFORGE
        info.fabric != null -> PerfPackLoader.FABRIC
        info.quilt != null -> PerfPackLoader.QUILT
        info.forge != null -> PerfPackLoader.FORGE
        else -> null
    }

    suspend fun install(info: GameDownloadInfo, versionName: String): PerfPackSummary =
        withContext(Dispatchers.IO) {
            val loader = loaderOf(info) ?: return@withContext PerfPackSummary()

            // The installed version is authoritative about its game dir (isolation
            // may follow the global setting). Fall back to the version directory.
            val gameDir = runCatching { VersionsManager.loadVersion(getGameHome(), versionName) }
                .getOrNull()
                ?.getGameDir()
                ?: File(getVersionsHome(getGameHome()), versionName)

            val modsDir = VersionFolders.MOD.getDir(gameDir).also { it.mkdirs() }
            // Download to a staging file so a failed transfer never leaves a
            // half-written jar in the mods folder (which would break idempotence).
            val stagingDir = File(PathManager.DIR_CACHE, "perfpack").also { it.mkdirs() }

            val installed = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val failed = mutableListOf<String>()

            for (slug in PerfPackMods.slugs) {
                var staging: File? = null
                try {
                    val resolved = PerfPackResolver.resolve(slug, loader, info.gameVersion)
                    if (resolved == null) {
                        // No release for this loader/MC version (e.g. MC 26.x): skip silently.
                        skipped += slug
                        continue
                    }

                    val target = File(modsDir, resolved.fileName)
                    if (target.exists()) {
                        // Idempotence: same filename is already present.
                        skipped += slug
                        continue
                    }

                    val pending = File(stagingDir, resolved.fileName).ensureParentDirectory()
                    staging = pending
                    downloadFile(url = resolved.url, outputFile = pending, sha1 = resolved.sha1)

                    // Another writer may have put the same file in place meanwhile.
                    if (target.exists()) {
                        skipped += slug
                        continue
                    }
                    FileUtils.moveFile(pending, target)
                    installed += slug
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Logger.warning(TAG, "Failed to install performance mod '$slug'", t)
                    failed += slug
                } finally {
                    staging?.let { runCatching { if (it.exists()) it.delete() } }
                }
            }

            PerfPackSummary(installed, skipped, failed)
        }
}
