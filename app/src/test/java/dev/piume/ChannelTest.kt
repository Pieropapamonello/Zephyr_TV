package dev.piume

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelTest {
    @Test fun sortsByChannelNumber() {
        val numbers = listOf("101", null, "5-1", "20", "5", "1", "abc")
        assertEquals(listOf("1", "5", "5-1", "20", "101", null, "abc"), numbers.sortedBy { channelKey(it) })
    }
}

class NightTest {
    @Test fun windowsMayCrossMidnight() {
        org.junit.Assert.assertTrue(inWindow(23 * 60 + 30, 23 * 60, 7 * 60))
        org.junit.Assert.assertTrue(inWindow(3 * 60, 23 * 60, 7 * 60))
        org.junit.Assert.assertFalse(inWindow(7 * 60, 23 * 60, 7 * 60))
        org.junit.Assert.assertTrue(inWindow(14 * 60, 13 * 60, 15 * 60))
        org.junit.Assert.assertFalse(inWindow(12 * 60, 13 * 60, 13 * 60))
    }
}
