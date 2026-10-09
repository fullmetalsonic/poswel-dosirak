package com.fullmetalsonic.dosirak.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

data class ServerTimeSample(val serverEpochMillis: Long, val requestStartedElapsedMillis: Long,
    val responseFinishedElapsedMillis: Long, val deviceWallMillis: Long)

data class ServerTimeBounds(val earliestEpochMillis: Long, val latestEpochMillis: Long, val estimatedEpochMillis: Long)

class ServerOrderClock(
    private val wallClock: Clock = Clock.system(SEOUL),
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) : Clock() {
    private var sample: ServerTimeSample? = null
    private var lastElapsed: Long? = null

    @Synchronized fun accept(value: ServerTimeSample): Boolean {
        val previous = bounds()
        sample = null
        val now = elapsedMillis()
        val rtt = value.responseFinishedElapsedMillis - value.requestStartedElapsedMillis
        if (value.serverEpochMillis !in 1..MAX_EPOCH_MILLIS || value.responseFinishedElapsedMillis < value.requestStartedElapsedMillis ||
            rtt !in 0..2_000 || rtt + 1_000 > 2_000 || now < value.responseFinishedElapsedMillis ||
            lastElapsed?.let { now < it } == true) return false
        val age = now - value.responseFinishedElapsedMillis
        if (age > TTL_MILLIS || abs(wallClock.millis() - value.deviceWallMillis - age) > 2_000) return false
        // A far initial literal can indicate an incorrect page or disabled automatic device time.
        if (previous == null && abs(value.serverEpochMillis - value.deviceWallMillis) > MAX_INITIAL_OFFSET_MILLIS) return false
        val earliest = value.serverEpochMillis + age
        val latest = earliest + rtt + 1_000
        if (previous != null && (latest < previous.earliestEpochMillis || earliest > previous.latestEpochMillis)) return false
        sample = value
        lastElapsed = now
        return true
    }

    @Synchronized fun bounds(): ServerTimeBounds? {
        val value = sample ?: return null
        val now = elapsedMillis()
        val age = now - value.responseFinishedElapsedMillis
        if (age !in 0..TTL_MILLIS || lastElapsed?.let { now < it } == true ||
            abs(wallClock.millis() - value.deviceWallMillis - age) > 2_000) {
            sample = null
            return null
        }
        lastElapsed = now
        val earliest = value.serverEpochMillis + age
        val latest = earliest + value.responseFinishedElapsedMillis - value.requestStartedElapsedMillis + 1_000
        return ServerTimeBounds(earliest, latest, earliest + (latest - earliest) / 2)
    }

    fun elapsedNow(): Long = elapsedMillis()

    fun purchaseProblem(date: LocalDate, start: LocalTime): String? {
        val current = bounds() ?: return "사이트 시각을 확인하지 못했거나 확인값이 만료되어 새 신청을 중단했습니다."
        val earliest = Instant.ofEpochMilli(current.earliestEpochMillis).atZone(SEOUL)
        val latest = Instant.ofEpochMilli(current.latestEpochMillis).atZone(SEOUL)
        return when {
            earliest.toLocalDate() != date || latest.toLocalDate() != date -> "사이트 기준 당일 날짜가 일치하지 않아 새 신청을 중단했습니다."
            earliest.toLocalTime() < maxOf(start, LocalTime.of(6, 0)) -> "사이트 기준 신청 시각에 아직 도달하지 않았습니다."
            latest.toLocalTime() >= LocalTime.of(8, 0) -> "사이트 시각의 오차 범위가 08:00 마감에 닿아 새 신청을 중단했습니다."
            else -> null
        }
    }

    fun delayUntil(date: LocalDate, time: LocalTime): Long? {
        val current = bounds() ?: return null
        val target = date.atTime(time).atZone(SEOUL).toInstant().toEpochMilli()
        return maxOf(0, target - current.earliestEpochMillis)
    }

    override fun instant(): Instant = Instant.ofEpochMilli(bounds()?.estimatedEpochMillis ?: wallClock.millis())
    override fun getZone(): ZoneId = SEOUL
    override fun withZone(zone: ZoneId): Clock {
        if (zone == SEOUL) return this
        val requestedZone = zone
        return object : Clock() {
            override fun instant(): Instant = this@ServerOrderClock.instant()
            override fun getZone(): ZoneId = requestedZone
            override fun withZone(zone: ZoneId): Clock = this@ServerOrderClock.withZone(zone)
        }
    }

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
        const val TTL_MILLIS = 180_000L
        const val MAX_INITIAL_OFFSET_MILLIS = 300_000L
        private const val MAX_EPOCH_MILLIS = 253_402_300_799_999L
    }
}
