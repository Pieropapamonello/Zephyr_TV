package dev.piume

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelTest {
    @Test fun sortsByChannelNumber() {
        val numbers = listOf("101", null, "5-1", "20", "5", "1", "abc")
        assertEquals(listOf("1", "5", "5-1", "20", "101", null, "abc"), numbers.sortedBy { channelKey(it) })
    }
}
