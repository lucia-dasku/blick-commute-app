package se.blick.app.ui.screens.routinedetails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.blick.app.R
import se.blick.app.domain.model.JourneyLeg
import se.blick.app.domain.model.JourneyPlan
import se.blick.app.domain.model.JourneyRole
import se.blick.app.domain.model.TransportMode
import java.time.Instant

class JourneyTimelinePresentationTest {
    private val start = Instant.parse("2026-08-23T12:12:00Z")

    private fun leg(
        mode: TransportMode,
        line: String?,
        origin: String,
        destination: String,
        departureMinutes: Long,
        arrivalMinutes: Long,
        direction: String? = destination,
    ) = JourneyLeg(
        transportMode = mode,
        lineDesignation = line,
        direction = direction,
        originName = origin,
        destinationName = destination,
        departureTime = start.plusSeconds(departureMinutes * 60),
        arrivalTime = start.plusSeconds(arrivalMinutes * 60),
        isRealtime = true,
        disruptions = emptyList(),
    )

    private fun journey(
        legs: List<JourneyLeg>,
        transferCount: Int,
        arrivalMinutes: Long = 16,
        role: JourneyRole = JourneyRole.PRIMARY,
    ) = JourneyPlan(
        journeyId = role.name,
        originName = legs.first().originName,
        destinationName = legs.last().destinationName,
        departureTime = legs.first().departureTime!!,
        arrivalTime = start.plusSeconds(arrivalMinutes * 60),
        transferCount = transferCount,
        firstLeg = legs.first { it.transportMode != TransportMode.UNKNOWN },
        legs = legs,
        disruptions = emptyList(),
        role = role,
    )

    @Test
    fun `direct journey produces one transit leg and no transfer`() {
        val presentation = journey(
            listOf(leg(TransportMode.METRO, "13", "Mälarhöjden, Stockholm", "T-Centralen, Stockholm", 0, 15)),
            transferCount = 0,
            arrivalMinutes = 15,
        ).toTimelinePresentation()

        assertEquals(1, presentation.items.size)
        assertTrue(presentation.items.single() is JourneyTimelineItem.TransitLeg)
        assertEquals(0, presentation.transferCount)
        assertEquals(15, presentation.totalDurationMinutes)
    }

    @Test
    fun `one transfer preserves both transit legs with a transfer between them`() {
        val first = leg(TransportMode.METRO, "13", "Mälarhöjden, Stockholm", "Liljeholmen, Stockholm", 0, 3, "Ropsten")
        val second = leg(TransportMode.METRO, "14", "Liljeholmen, Stockholm", "Fruängen, Stockholm", 5, 16, "Fruängen")

        val presentation = journey(listOf(first, second), transferCount = 1).toTimelinePresentation()

        assertEquals(
            listOf(
                JourneyTimelineItem.TransitLeg::class,
                JourneyTimelineItem.Transfer::class,
                JourneyTimelineItem.TransitLeg::class,
            ),
            presentation.items.map { it::class },
        )
        val transfer = presentation.items[1] as JourneyTimelineItem.Transfer
        assertEquals("Liljeholmen", transfer.stationDisplayName)
        assertEquals(2L, transfer.durationMinutes)
    }

    @Test
    fun `multiple transfers preserve transit order and mixed modes`() {
        val legs = listOf(
            leg(TransportMode.METRO, "11", "Akalla", "T-Centralen", 0, 10),
            leg(TransportMode.TRAM, "7", "T-Centralen", "Djurgårdsbron", 12, 24),
            leg(TransportMode.BUS, "67", "Djurgårdsbron", "Blockhusudden", 27, 42),
        )

        val transit = journey(legs, transferCount = 2, arrivalMinutes = 42)
            .toTimelinePresentation().items.filterIsInstance<JourneyTimelineItem.TransitLeg>()

        assertEquals(listOf(TransportMode.METRO, TransportMode.TRAM, TransportMode.BUS), transit.map { it.transportMode })
        assertEquals(listOf("11", "7", "67"), transit.map { it.lineDesignation })
    }

    @Test
    fun `meaningful walking connector is represented instead of a fabricated transfer`() {
        val first = leg(TransportMode.BUS, "4", "Radiohuset", "T-Centralen", 0, 10)
        val walk = leg(TransportMode.UNKNOWN, null, "T-Centralen", "Central station", 10, 14, direction = null)
        val train = leg(TransportMode.TRAIN, "40", "Central station", "Uppsala C", 15, 41)

        val items = journey(listOf(first, walk, train), transferCount = 1, arrivalMinutes = 41)
            .toTimelinePresentation().items

        assertEquals(
            listOf(
                JourneyTimelineItem.TransitLeg::class,
                JourneyTimelineItem.Walk::class,
                JourneyTimelineItem.TransitLeg::class,
            ),
            items.map { it::class },
        )
        assertEquals(4L, (items[1] as JourneyTimelineItem.Walk).durationMinutes)
    }

    @Test
    fun `direction is retained when present and omitted cleanly when absent`() {
        val withDirection = leg(TransportMode.FERRY, "82", "Slussen", "Allmänna gränd", 0, 16, "Djurgården")
        val withoutDirection = leg(TransportMode.BUS, "4", "A", "B", 18, 30, direction = " - ")

        val transit = journey(listOf(withDirection, withoutDirection), transferCount = 1, arrivalMinutes = 30)
            .toTimelinePresentation().items.filterIsInstance<JourneyTimelineItem.TransitLeg>()

        assertEquals("Djurgården", transit[0].direction)
        assertNull(transit[1].direction)
    }

    @Test
    fun `only exact redundant Stockholm suffix is removed`() {
        assertEquals("Mälarhöjden", compactStationDisplayName("Mälarhöjden, Stockholm"))
        assertEquals("Centralstation, Göteborg", compactStationDisplayName("Centralstation, Göteborg"))
        assertEquals("Place, Stockholm, Sweden", compactStationDisplayName("Place, Stockholm, Sweden"))
        assertEquals("Stockholm", compactStationDisplayName("Stockholm"))
    }

    @Test
    fun `transport modes expose localized text resources for every supported mode`() {
        assertEquals(R.string.journey_mode_metro, TransportMode.METRO.journeyLabelResId())
        assertEquals(R.string.journey_mode_bus, TransportMode.BUS.journeyLabelResId())
        assertEquals(R.string.journey_mode_commuter_rail, TransportMode.TRAIN.journeyLabelResId())
        assertEquals(R.string.journey_mode_tram, TransportMode.TRAM.journeyLabelResId())
        assertEquals(R.string.journey_mode_ferry, TransportMode.FERRY.journeyLabelResId())
        assertEquals(R.string.journey_mode_ferry, TransportMode.SHIP.journeyLabelResId())
    }

    @Test
    fun `final arrival and total duration stay authoritative for NEXT`() {
        val transit = leg(TransportMode.METRO, "13", "A", "B", 0, 12)
        val next = journey(listOf(transit), transferCount = 0, arrivalMinutes = 16, role = JourneyRole.NEXT)

        val presentation = next.toTimelinePresentation()

        assertEquals(next.arrivalTime, presentation.finalArrivalTime)
        assertEquals(16, presentation.totalDurationMinutes)
    }
}
