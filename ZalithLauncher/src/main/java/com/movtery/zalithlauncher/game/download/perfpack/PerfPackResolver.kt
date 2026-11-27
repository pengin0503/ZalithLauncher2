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

import com.movtery.zalithlauncher.game.download.addon.platform.PlatformReleaseType
import com.movtery.zalithlauncher.game.download.addon.platform.modrinth.MODRINTH_API
import com.movtery.zalithlauncher.game.download.addon.platform.modrinth.ModrinthSearcher
import com.movtery.zalithlauncher.game.download.addon.platform.modrinth.models.getPrimary
import com.movtery.zalithlauncher.utils.string.parseInstant
import java.time.Instant

/**
 * Thin resolver over the existing Modrinth API client and models.
 *
 * Modrinth is the only source used here: the API needs no key, while the
 * CurseForge API key is not available in this fork. Filtering is done on the
 * version list returned by the existing client (the `/project/{id}/version`
 * endpoint has no loader/game-version filter), and only stable releases are
 * considered. A project with no compatible version simply resolves to null.
 */
object PerfPackResolver {
    suspend fun resolve(
        slug: String,
        loader: PerfPackLoader,
        mcVersion: String
    ): ResolvedPerfMod? {
        // Official endpoint: https://api.modrinth.com/v2/project/{slug}/version
        val versions = ModrinthSearcher(api = MODRINTH_API).getVersionsChunk(slug)

        val version = versions
            .asSequence()
            .filter { it.versionType == PlatformReleaseType.RELEASE }
            .filter { it.gameVersions.any { value -> value.equals(mcVersion, ignoreCase = true) } }
            .filter { it.loaders.any { value -> value.equals(loader.modrinthName, ignoreCase = true) } }
            .sortedByDescending { candidate ->
                runCatching { parseInstant(candidate.datePublished) }.getOrDefault(Instant.EPOCH)
            }
            .firstOrNull() ?: return null

        val file = version.files.getPrimary() ?: return null
        return ResolvedPerfMod(
            slug = slug,
            fileName = file.fileName,
            url = file.url,
            sha1 = file.hashes.sha1
        )
    }
}
