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
