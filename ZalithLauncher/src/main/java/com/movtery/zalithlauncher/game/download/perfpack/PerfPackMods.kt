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

/**
 * Curated client-side performance mods, identified by Modrinth project slug.
 *
 * Only mods that never change gameplay or world data belong here. Order is
 * intentionally stable so that user feedback is deterministic.
 */
object PerfPackMods {
    val slugs: List<String> = listOf(
        "sodium",
        "ferrite-core",
        "modernfix",
        "immediatelyfast",
        "entityculling",
        "lithium"
    )
}

/** Mod loaders supported by the performance pack, with their Modrinth facet names. */
enum class PerfPackLoader(val modrinthName: String) {
    FABRIC("fabric"),
    FORGE("forge"),
    NEOFORGE("neoforge"),
    QUILT("quilt")
}

/** A resolved Modrinth version ready to be downloaded. */
data class ResolvedPerfMod(
    val slug: String,
    val fileName: String,
    val url: String,
    val sha1: String
)

/** Outcome of one best-effort performance pack run. */
data class PerfPackSummary(
    val installed: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failed: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = installed.isEmpty() && skipped.isEmpty() && failed.isEmpty()
}
