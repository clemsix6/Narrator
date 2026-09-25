package io.github.clemsix6.narrator2.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/** A clock that only moves when the test says so. Copies made by [withZone] share its time. */
class TestClock private constructor(private val time: AtomicReference<Instant>, private val zone: ZoneId) : Clock() {
    constructor(start: Instant = Instant.parse("2026-09-25T19:30:00Z"), zone: ZoneId = ZoneOffset.UTC) : this(AtomicReference(start), zone)

    var now: Instant
        get() = time.get()
        set(value) = time.set(value)

    fun advance(duration: Duration) {
        time.updateAndGet { it + duration }
    }

    override fun instant(): Instant = time.get()

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = TestClock(time, zone)
}
