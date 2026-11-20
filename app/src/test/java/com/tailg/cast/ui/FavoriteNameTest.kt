package com.tailg.cast.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteNameTest {

    @Test
    fun noteWins() {
        assertEquals("家", favoriteDisplayName(" 家 ", "天府广场", 1))
    }

    @Test
    fun placeNameFallback() {
        assertEquals("天府广场", favoriteDisplayName("  ", " 天府广场 ", 2))
    }

    @Test
    fun indexFallback() {
        assertEquals("收藏点 3", favoriteDisplayName("", "", 3))
    }
}
