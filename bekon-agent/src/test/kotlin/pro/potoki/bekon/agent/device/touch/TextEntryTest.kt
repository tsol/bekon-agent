package pro.potoki.bekon.agent.device.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEntryTest {
    @Test fun hintReportedAsTextIsIgnored() {
        assertEquals("", TextEntry.effectiveCurrent("Search", "Search", showingHint = false))
        assertEquals("", TextEntry.effectiveCurrent("Search", "Search", showingHint = true))
        assertEquals("", TextEntry.effectiveCurrent("anything", null, showingHint = true))
        assertEquals("abc", TextEntry.effectiveCurrent("abc", "Search", showingHint = false))
        assertEquals("", TextEntry.effectiveCurrent(null, "Search", showingHint = false))
    }

    @Test fun emptyFieldWithPlaceholderGetsOnlyNewText() {
        val cur = TextEntry.effectiveCurrent("Message", "Message", false)
        assertEquals("hello", TextEntry.desired(cur, "hello", replace = false))
    }

    @Test fun appendAndInsertAtSelection() {
        assertEquals("abcX", TextEntry.desired("abc", "X", false))
        assertEquals("aXc", TextEntry.desired("abc", "X", false, 1, 2))
        assertEquals("aXbc", TextEntry.desired("abc", "X", false, 1, 1))
        assertEquals("abcX", TextEntry.desired("abc", "X", false, 5, 9))
        assertEquals(2, TextEntry.cursorAfter("abc", "X", false, 1, 2))
    }

    @Test fun replaceClearsEverything() {
        assertEquals("new", TextEntry.desired("old text", "new", replace = true))
    }

    @Test fun detectsGluedPlaceholder() {
        assertTrue(TextEntry.gluedToHint("Searchhello", "Search", "hello"))
        assertFalse(TextEntry.gluedToHint("hello", "Search", "hello"))
    }
}
