package com.example.dogfood

import kotlin.math.pow

data class FeedingRecommendation(
    val coefficient: Double,
    val merKcal: Double,
    val dailyGram: Double,
    val perMealGram: Double,
    val feedingCount: Int,
)

object FeedingCalculator {
    fun coefficient(bodyCondition: String, neutered: Boolean): Double = when (bodyCondition) {
        "OVERWEIGHT" -> if (neutered) 82.78 else 81.34
        "OBESE" -> if (neutered) 70.13 else 66.90
        else -> if (neutered) 96.70 else 103.42
    }

    fun calculate(
        weightKg: Double,
        bodyCondition: String,
        neutered: Boolean,
        foodKcalPerKg: Double,
        feedingCount: Int,
    ): FeedingRecommendation {
        require(weightKg > 0.0) { "weightKg must be positive" }
        require(foodKcalPerKg > 0.0) { "foodKcalPerKg must be positive" }
        require(feedingCount > 0) { "feedingCount must be positive" }

        val k = coefficient(bodyCondition, neutered)
        val mer = k * weightKg.pow(0.75)
        val dailyGram = mer / foodKcalPerKg * 1000.0

        return FeedingRecommendation(
            coefficient = k,
            merKcal = mer,
            dailyGram = dailyGram,
            perMealGram = dailyGram / feedingCount,
            feedingCount = feedingCount,
        )
    }
}
