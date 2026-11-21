package com.qcbru.remind.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val central = ZoneId.of("America/Chicago")
private val centralTimestamp = DateTimeFormatter.ofPattern("MMM d, uuuu · h:mm a z", Locale.US).withZone(central)
private val centralClock = DateTimeFormatter.ofPattern("h:mm a z", Locale.US).withZone(central)

internal fun dateLabel(value: Long?): String =
    value?.let { centralTimestamp.format(Instant.ofEpochMilli(it)) } ?: "—"

internal fun clockLabel(value: Long): String = centralClock.format(Instant.ofEpochMilli(value))
