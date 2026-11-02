/*
 * Copyright (C) 2026 yuarmy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yuarmy.videoconvert.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun sanitize_replacesIllegalCharacters() {
        assertEquals("a_b_c_d_e_f_g", SettingsStore.sanitize("a/b:c*d?e\"f<g"))
    }

    @Test
    fun sanitize_fallsBackToDefaultWhenBlank() {
        assertEquals(SettingsStore.DEFAULT_OUTPUT_FOLDER, SettingsStore.sanitize("   "))
        assertEquals(SettingsStore.DEFAULT_OUTPUT_FOLDER, SettingsStore.sanitize("..."))
    }

    @Test
    fun sanitize_trimsDotsAndWhitespace() {
        assertEquals("videos", SettingsStore.sanitize("  .videos.  "))
    }

    @Test
    fun sanitize_truncatesToFortyCharacters() {
        val result = SettingsStore.sanitize("a".repeat(60))
        assertEquals(40, result.length)
    }
}
