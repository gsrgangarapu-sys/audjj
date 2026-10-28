package com.autodj

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun youtubeLinks() {
        assertEquals("playlist", youtubeKind("https://www.youtube.com/playlist?list=PL123"))
        assertEquals("video", youtubeKind("https://www.youtube.com/watch?v=abc123"))
        assertEquals("video", youtubeKind("https://youtu.be/abc123"))
        assertNull(youtubeKind("https://example.com/watch?v=abc"))
        assertNull(youtubeKind("not a link"))
    }
    @Test fun nextTriggerIsInFuture() {
        val now = System.currentTimeMillis()
        val t = Scheduler.nextTrigger(Routine(hour = 6, minute = 0), now)
        assertTrue(t > now && t - now <= 24 * 3600_000L + 3600_000L)
    }
    @Test fun dayIndex() {
        assertEquals(0, Store.dayIndex(java.util.Calendar.MONDAY))
        assertEquals(6, Store.dayIndex(java.util.Calendar.SUNDAY))
    }
}
