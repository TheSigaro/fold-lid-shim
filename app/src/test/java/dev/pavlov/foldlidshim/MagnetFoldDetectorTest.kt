package dev.pavlov.foldlidshim

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MagnetFoldDetectorTest {
    private class Capture : MagnetFoldDetector.Listener {
        val closing = mutableListOf<Long>()
        val opening = mutableListOf<Long>()
        override fun closing(tNs: Long, score: Double) { closing += tNs }
        override fun opening(tNs: Long, score: Double) { opening += tNs }
    }
    private fun feed(d: MagnetFoldDetector, ms: Long, amount: Double) {
        val b = MagnetParams().hardIron + MagnetParams().direction * amount
        d.onMag(ms * 1_000_000, b.x, b.y, b.z)
    }
    @Test fun stationaryJumpAndOpeningHaveRearmAndHold() {
        val c = Capture(); val d = MagnetFoldDetector(listener = c)
        d.onHinge(0, 180f); d.onHinge(1, 90f)
        for (ms in 0L..300 step 10) feed(d, ms, if (ms < 200) 40.0 else 100.0)
        assertEquals(listOf(200_000_000L), c.closing)
        d.onHinge(310_000_000, 0f)
        for (ms in 310L..600 step 10) feed(d, ms, 40.0)
        assertTrue(c.opening.isEmpty())
        for (ms in 610L..1100 step 10) feed(d, ms, 100.0)
        for (ms in 1110L..1300 step 10) feed(d, ms, 40.0)
        assertEquals(listOf(1_110_000_000L), c.opening)
    }
    @Test fun reboundAfterOpeningCannotCloseBeforeHinge180() {
        val c = Capture(); val d = MagnetFoldDetector(listener = c)
        d.onHinge(0, 180f); d.onHinge(1, 90f); d.onHinge(2, 0f)
        for (ms in 800L..1300 step 10) feed(d, ms, 100.0)
        for (ms in 1310L..1500 step 10) feed(d, ms, 40.0)
        assertEquals(listOf(1_310_000_000L), c.opening)
        d.onHinge(1_400_000_000, 90f)
        for (ms in 1510L..1800 step 10) feed(d, ms, 100.0) // Rebound along M right after the drop.
        assertTrue(c.closing.isEmpty())
        d.onHinge(1_900_000_000, 180f); d.onHinge(2_000_000_000, 90f)
        for (ms in 2010L..2300 step 10) feed(d, ms, if (ms < 2200) 40.0 else 100.0)
        assertEquals(listOf(2_200_000_000L), c.closing)
    }
    @Test fun pureRotationIsCompensated() {
        val c = Capture(); val d = MagnetFoldDetector(listener = c)
        d.onHinge(0, 180f); d.onHinge(1, 90f)
        var maxScore = 0.0
        val h = MagnetParams().hardIron
        for (ms in 0L..3000 step 10) {
            val a = -4 * ms * 1e-3
            d.onGyro(ms * 1_000_000, 0.0, 0.0, 4.0)
            d.onMag(ms * 1_000_000, h.x + 45 * cos(a), h.y + 45 * sin(a), h.z)
            maxScore = maxOf(maxScore, abs(d.lastScore))
        }
        assertTrue("score=$maxScore", maxScore <= 5)
        assertTrue(c.closing.isEmpty()); assertTrue(c.opening.isEmpty())
    }
    @Test fun gapAndOutOfOrderSamplesCannotMakeAJump() {
        val c = Capture(); val d = MagnetFoldDetector(listener = c)
        d.onHinge(0, 180f); d.onHinge(1, 90f)
        for (ms in 0L..200 step 10) feed(d, ms, 40.0)
        feed(d, 500, 100.0)
        feed(d, 400, 40.0)
        feed(d, 510, 100.0)
        assertEquals(0.0, d.lastScore, 0.0)
        assertTrue(c.closing.isEmpty())
        feed(d, 2000, 100.0)
        feed(d, 700, 40.0) // More than one second older than newest input.
        for (ms in 2010L..2200 step 10) feed(d, ms, 100.0)
        assertTrue(c.closing.isEmpty())
    }
    @Test fun openHingeAndExpiredFlexBlockClosing() {
        for (angle in listOf(90f, 180f)) {
            val c = Capture(); val d = MagnetFoldDetector(listener = c)
            d.onHinge(0, 180f); d.onHinge(1, angle)
            for (ms in 11000L..11400 step 10) feed(d, ms, if (ms < 11200) 40.0 else 100.0)
            assertTrue(c.closing.isEmpty())
        }
    }
    @Test fun calibrationFitsSphereAndRejectsPoorCoverage() {
        val h = MagnetVector(67.0, -30.7, 11.9)
        val points = (0..30).flatMap { lat -> (0..40).map { lon ->
            val a = PI * lat / 30; val b = 2 * PI * lon / 40
            h + MagnetVector(sin(a) * cos(b), sin(a) * sin(b), cos(a)) * 42.0
        } }
        val fit = MagnetFoldDetector.fitSphere(points)!!
        assertEquals(0.0, (fit.h - h).norm(), 1e-6)
        assertEquals(42.0, fit.radius, 1e-6)
        assertTrue(fit.rms < 1e-6)
        assertNull(MagnetFoldDetector.fitSphere(points.take(40)))
        assertNull(MagnetFoldDetector.fitSphere(points.map { h + (it - h) * 3.0 }))
    }
    @Test fun scheduledCalibrationAcceptsCoveredSphereEvenAfterMovementStops() {
        val c = Capture()
        val fits = mutableListOf<MagnetCalibration>()
        val d = MagnetFoldDetector(listener = c, calibrated = { fits += it })
        val h = MagnetVector(70.0, -25.0, 12.0)
        d.onHinge(0, 180f)
        var b = h
        for (i in 0..199) {
            val a = PI * (i % 20) / 19
            val phi = 2 * PI * i / 31
            b = h + MagnetVector(sin(a) * cos(phi), sin(a) * sin(phi), cos(a)) * 42.0
            d.onMag(i * 100_000_000L, b.x, b.y, b.z)
        }
        for (i in 200..310) d.onMag(i * 100_000_000L, b.x, b.y, b.z)
        assertEquals(1, fits.size)
        assertEquals(0.0, (d.hardIron - h).norm(), 1e-6)
        assertEquals(42.0, fits.single().radius, 1e-6)
        assertTrue(c.closing.isEmpty()); assertTrue(c.opening.isEmpty())
    }
    @Test fun preliminarySamplesUseConfiguredMotionPolicyWithoutChangingHingeHistory() {
        val motion = DirectMotion()
        motion.angle(0f, 1, 10f, 15f, true, 45f)
        assertFalse(motion.previewAngle(90f, 100_000_000, 10f, 15f, true, 45f))
        assertTrue(motion.closed)
        // A custom opening threshold above 90 must retain CLOSED, just like real hinge 90.
        assertTrue(motion.previewAngle(90f, 100_000_000, 10f, 120f, true, 45f))
        assertTrue(motion.closed)
        motion.angle(180f, 200_000_000, 10f, 15f, true, 45f)
        assertTrue(motion.previewAngle(0f, 300_000_000, 10f, 15f, true, 45f))
        assertFalse(motion.closed)
        // Velocity still comes from real hinge 180, unaffected by preliminary 0.
        assertFalse(motion.angle(90f, 400_000_000, 10f, 15f, true, 45f))
    }
    @Test fun preliminaryPolicyDeduplicatesOwnedRequestAndWatchdogRestoresHinge() {
        val motion = DirectMotion()
        val p = MagnetPreview()
        motion.angle(180f, 1, 10f, 15f, true, 45f)
        motion.angle(90f, 200_000_000, 10f, 15f, true, 45f)
        p.start(true, 400_000_000)
        assertEquals(DirectPolicy.Command.CLOSED,
            DirectPolicy.decide(p.desiredClosed ?: motion.closed, 3, false))
        assertEquals(DirectPolicy.Command.NONE,
            DirectPolicy.decide(p.desiredClosed ?: motion.closed, 3, true))
        p.expire(1_900_000_000)
        assertEquals(DirectPolicy.Command.CANCEL,
            DirectPolicy.decide(p.desiredClosed ?: motion.closed, 0, true))
        // Real hinge 180 overrides a preliminary closing immediately.
        val q = MagnetPreview()
        q.start(true, 2_000_000_000)
        assertNotNull(q.contradict(2_100_000_000, 180f))
        assertNull(q.desiredClosed)
        assertEquals(1, q.falseCount)
    }
    @Test fun watchdogConfirmsOrRollsBackAndBlocksForThirtySeconds() {
        val p = MagnetPreview()
        assertTrue(p.start(true, 0))
        assertNull(p.expire(1_499_999_999))
        assertNotNull(p.expire(1_500_000_000))
        assertEquals(1, p.falseCount)
        assertNull(p.desiredClosed)
        assertFalse(p.start(false, 31_499_999_999))
        assertTrue(p.start(false, 31_500_000_000))
        assertNull(p.hinge(31_600_000_000, 0f))
        assertEquals(200L, p.hinge(31_700_000_000, 90f))
        assertNull(p.expire(40_000_000_000))
        assertTrue(p.start(true, 41_000_000_000))
        assertEquals(100L, p.hinge(41_100_000_000, 0f))
        assertNull(p.expire(43_000_000_000))
        assertTrue(p.start(false, 44_000_000_000))
        assertNotNull(p.expire(45_500_000_000))
        assertEquals(2, p.falseCount)
    }
    private fun vector(d: MagnetFoldDetector, ms: Long, along: Double, across: Double = 0.0) {
        val p = d.params
        val perpendicular = p.direction.cross(MagnetVector(0.0, 0.0, 1.0)).unit()
        val b = p.hardIron + p.direction * along + perpendicular * across
        d.onMag(ms * 1_000_000, b.x, b.y, b.z)
    }

    @Test fun directionFilterRejectsBothSignsAndKeepsAlignedLidMotion() {
        for (opening in listOf(false, true)) {
            for (ratio in listOf(0.64, 0.66)) {
                val c = Capture()
                val d = MagnetFoldDetector(listener = c)
                if (opening) d.onHinge(0, 0f) else {
                    d.onHinge(0, 180f)
                    d.onHinge(1, 90f)
                }
                for (ms in 800L..1300 step 10) vector(d, ms, if (opening) 100.0 else 40.0)
                for (ms in 1310L..1500 step 10)
                    vector(d, ms, 70.0, 30.0 * ratio)
                assertEquals("opening=$opening ratio=$ratio", if (ratio < 0.65) 1 else 0,
                    if (opening) c.opening.size else c.closing.size)
            }
        }
    }

    @Test fun foreignFieldQuarantinesItsRemovalAndRecoversAfterQuietInterval() {
        val c = Capture()
        val d = MagnetFoldDetector(listener = c)
        d.onHinge(0, 0f)
        for (ms in 1000L..1200 step 10) vector(d, ms, 100.0)
        vector(d, 1210, 140.0) // Outside even the closed sphere envelope.
        for (ms in 1220L..1400 step 10) vector(d, ms, 40.0)
        assertTrue(c.opening.isEmpty())
        for (ms in 30000L..30500 step 10) vector(d, ms, if (ms < 30300) 100.0 else 40.0)
        assertTrue(c.opening.isEmpty())
        for (ms in 31700L..32400 step 10) vector(d, ms, if (ms < 32200) 100.0 else 40.0)
        assertEquals(listOf(32_200_000_000L), c.opening)
    }

    @Test fun rollbackRearmsBothDirectionsWithoutAnotherConfirmingHinge() {
        for (opening in listOf(false, true)) {
            val c = Capture()
            val d = MagnetFoldDetector(listener = c)
            if (opening) d.onHinge(0, 0f) else {
                d.onHinge(0, 180f)
                d.onHinge(1, 90f)
            }
            fun jump(start: Long) {
                for (ms in start-300..start+400 step 10)
                    vector(d, ms, if ((ms < start+200) == opening) 100.0 else 40.0)
            }
            jump(1000)
            assertEquals(1, if (opening) c.opening.size else c.closing.size)
            d.inhibitUntil(32_000_000_000L)
            // Refresh Flex eligibility, without resetting the opening/closing latch via 0/180.
            if (!opening) d.onHinge(31_000_000_000L, 90f)
            jump(31500)
            assertEquals(1, if (opening) c.opening.size else c.closing.size)
            jump(32500)
            assertEquals(2, if (opening) c.opening.size else c.closing.size)
        }
    }

    @Test fun initialCachedHingeSeedsStateButLaterStaleHingeIsIgnored() {
        val c = Capture()
        val d = MagnetFoldDetector(listener = c)
        d.onGyro(31_000_000_000L, 0.0, 0.0, 0.0)
        d.onHinge(0, 0f)
        d.onHinge(1, 180f) // Not the first hinge any more, now stale.
        for (ms in 31700L..32400 step 10) vector(d, ms, if (ms < 32200) 100.0 else 40.0)
        assertEquals(listOf(32_200_000_000L), c.opening)
    }

    @Test fun openingWatchdogUsesEightHundredMillisecondsClosingKeepsFifteenHundred() {
        for (closed in listOf(false, true)) {
            val p = MagnetPreview()
            p.start(closed, 10_000_000_000L)
            val timeout = if (closed) 1_500_000_000L else 800_000_000L
            assertNull(p.expire(10_000_000_000L + timeout - 1))
            assertNotNull(p.expire(10_000_000_000L + timeout))
            assertEquals(10_000_000_000L + timeout + 30_000_000_000L, p.blockedUntilNs)
        }
    }

    @Test fun phantomClosingBlocksFastRemovalButAllowsOpeningAfterOneSecond() {
        for (delay in listOf(100L, 1000L)) {
            for (across in listOf(0.0, 18.0)) {
                val c = Capture()
                val d = MagnetFoldDetector(listener = c)
                d.onHinge(0, 0f)
                for (ms in 1400L..(2200 + delay) step 10) {
                    vector(d, ms, when {
                        ms < 2000 -> 40.0
                        ms < 2000 + delay -> 65.0
                        else -> 35.0
                    }, if (ms in 2000L until (2000 + delay)) across else 0.0)
                }
                // The last positive window ends at 2140 ms, so quarantine ends at 2640 ms.
                assertEquals(if (delay == 100L) emptyList<Long>() else listOf(3_000_000_000L), c.opening)
            }
        }
    }

    @Test fun closingSettlingBeforeOpeningHoldIsNotPhantom() {
        val c = Capture()
        val d = MagnetFoldDetector(listener = c)
        d.onHinge(0, 0f)
        for (ms in 0L..1000 step 10)
            vector(d, ms, when {
                ms < 300 -> 40.0
                ms < 800 -> 65.0
                else -> 35.0
            })
        assertEquals(listOf(800_000_000L), c.opening)
    }

    @Test fun openingRejectsOnlyRiseBeforeWindow() {
        for (rise in listOf(15.0, 0.0, -15.0)) {
            val c = Capture()
            val d = MagnetFoldDetector(listener = c)
            d.onHinge(0, 0f)
            for (ms in 1000L..1800 step 10) {
                val along = when {
                    ms < 1500 -> 60.0
                    ms < 1650 -> 60.0 + rise
                    else -> 35.0 + rise
                }
                vector(d, ms, along)
            }
            assertEquals("pre-window rise=$rise", if (rise > 10) emptyList<Long>()
                else listOf(1_650_000_000L), c.opening)
        }
    }

    @Test fun phantomDuringQuarantineExtendsItEvenWhenPreviewIsBlocked() {
        val c = Capture()
        val d = MagnetFoldDetector(listener = c)
        d.onHinge(0, 0f)
        d.inhibitUntil(2_100_000_000L)
        for (ms in 1400L..4000 step 10) {
            val along = when {
                ms < 2000 -> 40.0
                ms < 2100 -> 65.0
                ms < 2400 -> 35.0
                ms < 2800 -> 60.0
                ms < 2900 -> 30.0
                ms < 3400 -> 30.0 + (ms - 2900) * 0.06
                ms < 3800 -> 60.0
                else -> 30.0
            }
            vector(d, ms, along)
            // The second peak extends quarantine from 2590 to 3040 ms.
            if (ms < 3800) assertTrue("Early opening at $ms", c.opening.isEmpty())
        }
        assertEquals(listOf(3_800_000_000L), c.opening)
    }

    @Test fun quietPhoneNeedsLargerOpeningButMissingOrMovingGyroUsesNormalThreshold() {
        for ((speed, drop, expected) in listOf(
            Triple(0.1, 25.0, false), Triple(0.1, 40.0, true),
            Triple(1.0, 25.0, true), Triple(null, 25.0, true)
        )) {
            val c = Capture()
            val d = MagnetFoldDetector(listener = c)
            d.onHinge(0, 0f)
            for (ms in 1000L..1700 step 10) {
                // Rotation along M leaves this synthetic field unchanged.
                if (speed != null) {
                    val omega = d.params.direction * speed
                    d.onGyro(ms * 1_000_000, omega.x, omega.y, omega.z)
                }
                vector(d, ms, if (ms < 1500) 60.0 else 60.0 - drop)
            }
            assertEquals("speed=$speed drop=$drop", if (expected) listOf(1_500_000_000L)
                else emptyList<Long>(), c.opening)
        }
    }

    @Test fun openingRequiresFullPreWindowHistoryAfterStartAndGap() {
        for (start in listOf(1000L, 2000L)) {
            val c = Capture()
            val d = MagnetFoldDetector(listener = c)
            d.onHinge(0, 0f)
            if (start == 2000L) for (ms in 1000L..1500 step 10) vector(d, ms, 60.0)
            for (ms in start..start + 400 step 10)
                vector(d, ms, if (ms < start + 200) 60.0 else 35.0)
            assertTrue(c.opening.isEmpty())
        }
    }

}
