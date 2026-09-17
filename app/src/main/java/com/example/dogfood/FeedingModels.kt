package com.example.dogfood

import java.time.LocalDateTime

data class FeedingPlan(
    val index: Int,
    var time: LocalDateTime,
    var foodGram: Int,
    var pillA: Int,
    var pillB: Int,
    var fed: Boolean = false,
    var measured: Boolean = false,
    var measureAt: LocalDateTime? = null,
)

data class DailySchedule(
    val hour: Int,
    val minute: Int,
    val foodGram: Int,
    val pillA: Int,
    val pillB: Int,
) {
    val minuteOfDay: Int get() = hour * 60 + minute
    fun timeText(): String = "%02d:%02d".format(hour, minute)
}
