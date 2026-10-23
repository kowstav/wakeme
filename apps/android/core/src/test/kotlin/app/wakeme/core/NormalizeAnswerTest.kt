package app.wakeme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class NormalizeAnswerTest {
    @Test
    fun unicodeSpacesCollapseLikeTheServer() {
        assertEquals("chaos curry", normalizeAnswer("  Chaos 　Curry! "))
        assertEquals("cafe", normalizeAnswer("Café"))
    }

    /** Android's ICU regex engine rejects (?U); the JVM accepts it, so only a phone would crash. */
    @Test
    fun noRegexFlagsAndroidRejects() {
        val source = File("src/main/kotlin/app/wakeme/core").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("(?U) in core regexes", source.contains("(?U)"))
    }
}
