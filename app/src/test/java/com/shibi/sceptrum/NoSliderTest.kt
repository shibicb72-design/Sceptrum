package com.shibi.sceptrum

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §2.3: volume is buttons only. No `Slider(` may appear anywhere in the app UI. */
class NoSliderTest {
    @Test
    fun appSourcesNeverUseASlider() {
        val root = File("src/main/java")
        assertTrue("source root not found from ${File(".").absolutePath}", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> SliderCall.containsMatchIn(file.readText()) }
            .map { it.path }
            .toList()
        assertTrue("Slider found in: $offenders", offenders.isEmpty())
    }

    private companion object {
        val SliderCall = Regex("""\b\w*Slider\s*\(""")
    }
}
