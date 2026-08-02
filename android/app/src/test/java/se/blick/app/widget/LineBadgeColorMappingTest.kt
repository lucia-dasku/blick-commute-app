package se.blick.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import se.blick.app.domain.model.TransportMode

/**
 * Pure JVM tests for [LineBadgeColorMapping.colorFor] — no Android dependency, no widget
 * instance, matching this project's other pure-mapper test files.
 */
class LineBadgeColorMappingTest {

    // ---- Every colour group ----

    @Test
    fun `every Pendeltag line is pink`() {
        for (line in listOf("40", "41", "42X", "43", "43X", "44", "48")) {
            assertEquals("line $line", LineBadgeColor.Pink, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, line))
        }
    }

    @Test
    fun `metro blue-line lines 10 and 11 are blue`() {
        for (line in listOf("10", "11")) {
            assertEquals("line $line", LineBadgeColor.Blue, LineBadgeColorMapping.colorFor(TransportMode.METRO, line))
        }
    }

    @Test
    fun `metro red-line lines 13 and 14 are red`() {
        for (line in listOf("13", "14")) {
            assertEquals("line $line", LineBadgeColor.Red, LineBadgeColorMapping.colorFor(TransportMode.METRO, line))
        }
    }

    @Test
    fun `metro green-line lines 17, 18 and 19 are green`() {
        for (line in listOf("17", "18", "19")) {
            assertEquals("line $line", LineBadgeColor.Green, LineBadgeColorMapping.colorFor(TransportMode.METRO, line))
        }
    }

    // ---- X (express) lines ----

    @Test
    fun `Pendeltag express lines 42X and 43X are pink, exactly like their non-express counterparts`() {
        assertEquals(LineBadgeColor.Pink, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "42X"))
        assertEquals(LineBadgeColor.Pink, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "43X"))
    }

    @Test
    fun `an X line not in the Pendeltag list is unknown, not pink by virtue of the suffix alone`() {
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "41X"))
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "50X"))
    }

    // ---- Normalization ----

    @Test
    fun `a lowercase X suffix normalizes to the same color as uppercase`() {
        assertEquals(LineBadgeColor.Pink, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "42x"))
        assertEquals(LineBadgeColor.Pink, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "43x"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed before lookup`() {
        assertEquals(LineBadgeColor.Pink, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, " 40 "))
        assertEquals(LineBadgeColor.Blue, LineBadgeColorMapping.colorFor(TransportMode.METRO, "\t10\n"))
    }

    @Test
    fun `mixed-case and whitespace together still normalize correctly`() {
        assertEquals(LineBadgeColor.Pink, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "  42x  "))
    }

    // ---- Overlapping mode/number combinations -- color depends on mode, not number alone ----

    @Test
    fun `a bus sharing a metro red-line number is unknown, not red`() {
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.BUS, "14"))
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.BUS, "13"))
    }

    @Test
    fun `a train sharing a metro line number is unknown, not that metro line's color`() {
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "14"))
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "10"))
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, "17"))
    }

    @Test
    fun `a metro line sharing a Pendeltag number is unknown, not pink`() {
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.METRO, "40"))
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.METRO, "48"))
    }

    @Test
    fun `a tram sharing any colored line number is unknown`() {
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.TRAM, "13"))
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.TRAM, "40"))
    }

    // ---- Unknown values ----

    @Test
    fun `a metro line outside every explicit range is unknown`() {
        for (line in listOf("1", "12", "15", "16", "20", "9999")) {
            assertEquals("line $line", LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.METRO, line))
        }
    }

    @Test
    fun `a Pendeltag-mode line outside the explicit list is unknown`() {
        for (line in listOf("35", "36", "26", "1")) {
            assertEquals("line $line", LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.TRAIN, line))
        }
    }

    @Test
    fun `every non-metro non-train mode is unknown regardless of line number`() {
        for (mode in listOf(TransportMode.BUS, TransportMode.SHIP, TransportMode.FERRY, TransportMode.TAXI, TransportMode.UNKNOWN)) {
            assertEquals("mode $mode", LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(mode, "14"))
        }
    }

    @Test
    fun `an empty or blank line designation is unknown`() {
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.METRO, ""))
        assertEquals(LineBadgeColor.Unknown, LineBadgeColorMapping.colorFor(TransportMode.METRO, "   "))
    }
}
