package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MagnetFoldDetectorReplayTest {
    data class Event(val closed: Boolean, val t: Long, val score: Double)
    @Test fun realRecordingLeadsHingeWithoutFalseEvents() {
        val events = mutableListOf<Event>()
        val fits = mutableListOf<MagnetCalibration>()
        val d = MagnetFoldDetector(listener = object : MagnetFoldDetector.Listener {
            override fun closing(tNs: Long, score: Double) { events += Event(true, tNs, score) }
            override fun opening(tNs: Long, score: Double) { events += Event(false, tNs, score) }
        }, calibrated = { fits += it })
        val transitions = mutableListOf<Pair<Boolean, Long>>()
        var previous: Float? = null
        val scores = mutableListOf<Pair<Long, Double>>()
        val calibrationSamples = mutableListOf<MagnetVector>()
        var lastCalibrationSample: Long? = null
        var calmMax = 0.0
        val rows = javaClass.getResourceAsStream("/sensors-1-trim.csv")!!.bufferedReader().use { it.readLines() }
        val hingeRows = rows.map { it.split(';') }.filter { it[2] == "36" }
        val origin = hingeRows.first()[1].toLong()
        for (line in rows) {
            val r = line.split(';')
            val t = r[1].toLong()
            when (r[2]) {
                "4" -> d.onGyro(t, r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
                "14" -> {
                    d.onMag(t, r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
                    scores += t to d.lastScore
                    if (previous == 180f) {
                        val b = MagnetVector(r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
                        if (lastCalibrationSample?.let { t - it >= 100_000_000L } != false &&
                            calibrationSamples.lastOrNull()?.let { (b - it).norm() >= 3 } != false) {
                            calibrationSamples += b
                            lastCalibrationSample = t
                        }
                    }
                    val seconds = (t - origin) * 1e-9
                    // Specified open-phone rotation intervals.
                    if (seconds in 28.2..36.6 || seconds in 43.6..47.0)
                        calmMax = maxOf(calmMax, abs(d.lastScore))
                }
                "36" -> {
                    val angle = r[4].toFloat()
                    if (previous != null && angle == 0f) transitions += true to t
                    if (previous == 0f && angle >= 90f) transitions += false to t
                    previous = angle
                    d.onHinge(t, angle)
                }
            }
        }
        assertTrue("Rotation residual exceeds threshold: ${calmMax}", calmMax < 22)
        assertEquals("Exactly seven signals in four target cycles", 7, events.size)
        assertTrue("Insufficient coverage at first scheduled fit must be rejected", fits.isEmpty())
        val finalFit = MagnetFoldDetector.fitSphere(calibrationSamples)!!
        assertEquals(2.89, finalFit.rms, 0.05)
        assertEquals(42.26, finalFit.radius, 0.05)
        val outsideMax = scores.filter { (t, _) -> transitions.none { abs(it.second - t) <= 1_500_000_000L } }
            .maxOf { abs(it.second) }
        println("MAGNET outsideTransitionMax=$outsideMax finalFit=$finalFit")
        // First opening at +4.2 is deliberately outside the four target cycles.
        val targets = transitions.filter { (it.second - origin) * 1e-9 > 9.0 }
        assertEquals(8, targets.size)
        val leads = targets.map { (closed, t) ->
            val event = events.filter { it.closed == closed && abs(it.t - t) <= 1_500_000_000L }.minByOrNull { it.t }
            val lead = event?.let { (t - it.t) / 1_000_000.0 }
            println("MAGNET transition=${(t - origin) * 1e-9} closed=$closed leadMs=$lead score=${event?.score}")
            Triple(closed, t, lead)
        }
        println("MAGNET calmMax=${calmMax} fits=$fits events=$events")
        val baselineLeads = listOf<Double?>(null, 280.070469, 200.051042, 180.179687,
            70.192187, 240.193854, 110.173802, 380.183698)
        leads.zip(baselineLeads).forEach { (actual, baseline) ->
            if (baseline != null) assertTrue("Lead degraded: $actual baseline=$baseline",
                actual.third != null && actual.third!! >= baseline - 30.0)
        }
        for (event in events) assertTrue("False event $event", transitions.any {
            it.first == event.closed && abs(it.second - event.t) <= 1_500_000_000L
        })
        val closes = leads.filter { it.first }
        assertTrue("Slow first close", closes.first().third == null || closes.first().third!! >= 0)
        closes.drop(1).forEach { assertTrue("Missing early close $it", it.third != null && it.third!! >= 20) }
        assertTrue("At least three early openings", leads.count { !it.first && it.third != null && it.third!! >= 100 } >= 3)
        leads.filter { !it.first }.forEach { assertTrue("Missing early opening $it", it.third != null && it.third!! >= 0) }
    }
    @Test fun closedRecordingHasNoEventsEvenAfterForeignFieldAndPartialOpenings() {
        for (threshold in listOf(18.0, 20.0, 22.0)) {
            val events = mutableListOf<Event>()
            val d = MagnetFoldDetector(MagnetParams(closeThreshold = threshold, openThreshold = threshold),
                object : MagnetFoldDetector.Listener {
                    override fun closing(tNs: Long, score: Double) { events += Event(true, tNs, score) }
                    override fun opening(tNs: Long, score: Double) { events += Event(false, tNs, score) }
                })
            val rows = javaClass.getResourceAsStream("/sensors-2-trim.csv")!!.bufferedReader().use { it.readLines() }
            for (line in rows) {
                val r = line.split(';')
                val t = r[1].toLong()
                when (r[2]) {
                    "36" -> d.onHinge(t, r[4].toFloat())
                    "4" -> d.onGyro(t, r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
                    "14" -> {
                        d.onMag(t, r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
                    }
                }
            }
            assertTrue("No watchdog or 30s preview block should hide later false events: $events", events.isEmpty())
        }
    }

    @Test fun laptopMovementDoesNotOpenClosedPhone() {
        for (threshold in listOf(18.0, 20.0, 22.0)) {
            val events = mutableListOf<Event>()
            val d = MagnetFoldDetector(MagnetParams(closeThreshold = threshold, openThreshold = threshold),
                object : MagnetFoldDetector.Listener {
                    override fun closing(tNs: Long, score: Double) { events += Event(true, tNs, score) }
                    override fun opening(tNs: Long, score: Double) { events += Event(false, tNs, score) }
                })
            val rows = javaClass.getResourceAsStream("/sensors-3-trim.csv")!!.bufferedReader().use { it.readLines() }
                .map { it.split(';') }
            val origin = rows.first { it[2] == "header" }[0].toLong()
            val hinges = mutableListOf<Pair<Float, Long>>()
            for (r in rows) {
                val t = r[1].toLong()
                when (r[2]) {
                    "36" -> { d.onHinge(t, r[4].toFloat()); hinges += r[4].toFloat() to t }
                    "4" -> d.onGyro(t, r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
                    "14" -> d.onMag(t, r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
                }
            }
            assertTrue("Laptop false event at T=$threshold: $events", events.none {
                (it.t - origin) * 1e-9 in 20.0..35.0
            })
            assertEquals("Exactly one closing and one opening at T=$threshold", 2, events.size)
            assertEquals(listOf(true, false), events.map { it.closed })
            assertEquals(6.16, (events[0].t - origin) * 1e-9, 0.08)
            assertEquals(86.52, (events[1].t - origin) * 1e-9, 0.08)
            val baseline = when (threshold) {
                18.0 -> listOf(195.985, 425.987)
                20.0 -> listOf(175.985, 395.987)
                else -> listOf(155.985, 395.987)
            }
            for ((i, event) in events.withIndex()) {
                val hinge = hinges.first { (a, t) -> t > event.t && a == if (event.closed) 0f else 90f }.second
                assertTrue("Lead degraded at T=$threshold: $event",
                    (hinge - event.t) / 1e6 >= baseline[i] - 30.0)
            }
        }
    }

    @Test fun scenarioRecordingPreservesV13CoverageWithoutLaptopEvents() {
        // Baseline: unmodified v13 (930becb), Python replay on sensors-4-trim.csv,
        // before report/test changes. Entries follow all 18 hinge transitions.
        val baselineLeads = mapOf(
            18.0 to listOf<Double?>(166.607500, 256.617760, 276.613073, 236.606562,
                186.614375, 286.614583, null, 236.617031, null, null, null,
                366.626823, 396.816510, 316.643021, 186.608906, null, null, null),
            20.0 to listOf<Double?>(156.607500, 246.617760, 276.613073, 216.606562,
                176.614375, 226.614583, null, null, null, null, null,
                356.626823, 366.816510, 306.643021, 176.608906, null, null, null),
            22.0 to listOf<Double?>(156.607500, 246.617760, 276.613073, 196.606562,
                176.614375, 216.614583, null, null, null, null, null,
                316.626823, 356.816510, 306.643021, 176.608906, null, null, null)
        )
        val baselineCounts = mapOf(18.0 to 11, 20.0 to 10, 22.0 to 10)
        val rows = javaClass.getResourceAsStream("/sensors-4-trim.csv")!!.bufferedReader()
            .use { it.readLines() }.map { it.split(';') }
        val origin = rows.first { it[2] == "header" }[0].toLong()
        for ((threshold, baseline) in baselineLeads) {
            val events = mutableListOf<Event>()
            val transitions = mutableListOf<Pair<Boolean, Long>>()
            var previous: Float? = null
            val detector = MagnetFoldDetector(
                MagnetParams(closeThreshold = threshold, openThreshold = threshold),
                object : MagnetFoldDetector.Listener {
                    override fun closing(tNs: Long, score: Double) { events += Event(true, tNs, score) }
                    override fun opening(tNs: Long, score: Double) { events += Event(false, tNs, score) }
                })
            for (r in rows) {
                val t = r[1].toLong()
                when (r[2]) {
                    "36" -> {
                        val angle = r[4].toFloat()
                        if (previous != null && angle == 0f) transitions += true to t
                        if (previous == 0f && angle >= 90f) transitions += false to t
                        previous = angle
                        detector.onHinge(t, angle)
                    }
                    "4" -> detector.onGyro(t, r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
                    "14" -> detector.onMag(t, r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
                }
            }
            assertEquals("All scenario transitions", 18, transitions.size)
            assertTrue("Coverage degraded at T=$threshold: $events",
                events.size >= baselineCounts.getValue(threshold))
            for (event in events) assertTrue("False event at T=$threshold: $event", transitions.any {
                it.first == event.closed && abs(it.second - event.t) <= 1_500_000_000L
            })
            assertTrue("Laptop event at T=$threshold: $events", events.none {
                (it.t - origin) * 1e-9 in 59.0..79.0
            })
            for ((index, transition) in transitions.withIndex()) {
                val lead = baseline[index] ?: continue
                val event = events.filter {
                    it.closed == transition.first && abs(it.t - transition.second) <= 1_500_000_000L
                }.minByOrNull { it.t }
                assertNotNull("Missing transition $index at T=$threshold", event)
                assertTrue("Lead degraded at transition $index T=$threshold: $event",
                    (transition.second - event!!.t) / 1e6 >= lead - 30.0)
            }
        }
    }

}
