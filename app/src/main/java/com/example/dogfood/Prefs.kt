package com.example.dogfood

import android.content.Context

object Prefs {
    const val BCS_NORMAL = "NORMAL"
    const val BCS_OVERWEIGHT = "OVERWEIGHT"
    const val BCS_OBESE = "OBESE"

    private const val NAME = "dog_food_settings"
    private const val KEY_DOG_NAME = "dog_name"
    private const val KEY_DOG_BIRTH_DATE = "dog_birth_date"
    private const val KEY_DOG_WEIGHT = "dog_weight"
    private const val KEY_NEUTERED = "dog_neutered"
    private const val KEY_OVERWEIGHT = "dog_overweight" // 이전 버전 호환용
    private const val KEY_BODY_CONDITION = "dog_body_condition"
    private const val KEY_FOOD_KCAL_PER_GRAM = "food_kcal_per_gram" // 이전 버전 호환용
    private const val KEY_FOOD_KCAL_PER_KG = "food_kcal_per_kg"
    private const val KEY_FEEDING_COUNT = "feeding_count"
    private const val KEY_AUTO_FOOD_MODE = "auto_food_mode"
    private const val KEY_DEFAULT_FOOD_GRAM = "default_food_gram"
    private const val KEY_PILL_A_NAME = "pill_a_name"
    private const val KEY_PILL_B_NAME = "pill_b_name"

    private const val KEY_LIFE_SCHEDULES = "life_schedules"
    private const val KEY_LIFE_ENABLED = "life_enabled"
    private const val KEY_LAST_DEVICE_SYNC = "last_device_sync"

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun dogName(context: Context): String =
        prefs(context).getString(KEY_DOG_NAME, "").orEmpty()

    fun setDogName(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DOG_NAME, value).apply()
    }

    fun dogBirthDate(context: Context): String =
        prefs(context).getString(KEY_DOG_BIRTH_DATE, "").orEmpty()

    fun dogWeight(context: Context): Float =
        prefs(context).getFloat(KEY_DOG_WEIGHT, 0f)

    fun neutered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NEUTERED, false)

    fun bodyCondition(context: Context): String {
        val p = prefs(context)
        val saved = p.getString(KEY_BODY_CONDITION, null)
        if (saved in setOf(BCS_NORMAL, BCS_OVERWEIGHT, BCS_OBESE)) return saved!!

        // 이전 버전의 비만 관리 스위치 값이 있으면 과체중으로 안전하게 이전한다.
        return if (p.getBoolean(KEY_OVERWEIGHT, false)) BCS_OVERWEIGHT else BCS_NORMAL
    }

    fun foodKcalPerKg(context: Context): Float {
        val p = prefs(context)
        if (p.contains(KEY_FOOD_KCAL_PER_KG)) {
            return p.getFloat(KEY_FOOD_KCAL_PER_KG, 0f)
        }

        // 이전 버전은 kcal/g 단위였으므로 kcal/kg로 변환하여 표시한다.
        val oldPerGram = p.getFloat(KEY_FOOD_KCAL_PER_GRAM, 0f)
        return if (oldPerGram > 0f) oldPerGram * 1000f else 0f
    }

    fun feedingCount(context: Context): Int =
        prefs(context).getInt(KEY_FEEDING_COUNT, 2).coerceIn(1, 10)

    fun autoFoodMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_FOOD_MODE, false)

    fun defaultFoodGram(context: Context): Int =
        prefs(context).getInt(KEY_DEFAULT_FOOD_GRAM, 65)

    fun pillAName(context: Context): String =
        prefs(context).getString(KEY_PILL_A_NAME, "약 A")
            .orEmpty()
            .ifBlank { "약 A" }

    fun pillBName(context: Context): String =
        prefs(context).getString(KEY_PILL_B_NAME, "약 B")
            .orEmpty()
            .ifBlank { "약 B" }

    fun setProfile(
        context: Context,
        dogName: String,
        birthDate: String,
        weightKg: Float,
        neutered: Boolean,
        bodyCondition: String,
        foodKcalPerKg: Float,
        feedingCount: Int,
        autoFoodMode: Boolean,
        defaultFoodGram: Int,
        pillAName: String,
        pillBName: String,
    ) {
        prefs(context).edit()
            .putString(KEY_DOG_NAME, dogName)
            .putString(KEY_DOG_BIRTH_DATE, birthDate)
            .putFloat(KEY_DOG_WEIGHT, weightKg)
            .putBoolean(KEY_NEUTERED, neutered)
            .putString(KEY_BODY_CONDITION, bodyCondition)
            .putBoolean(KEY_OVERWEIGHT, bodyCondition != BCS_NORMAL)
            .putFloat(KEY_FOOD_KCAL_PER_KG, foodKcalPerKg)
            .putFloat(KEY_FOOD_KCAL_PER_GRAM, foodKcalPerKg / 1000f)
            .putInt(KEY_FEEDING_COUNT, feedingCount.coerceIn(1, 10))
            .putBoolean(KEY_AUTO_FOOD_MODE, autoFoodMode)
            .putInt(KEY_DEFAULT_FOOD_GRAM, defaultFoodGram)
            .putString(KEY_PILL_A_NAME, pillAName.ifBlank { "약 A" })
            .putString(KEY_PILL_B_NAME, pillBName.ifBlank { "약 B" })
            .apply()
    }

    fun food(context: Context, index: Int): Int =
        int(context, "food_$index", defaultFoodGram(context))

    fun pillA(context: Context, index: Int): Int =
        int(context, "pill_a_$index", listOf(2, 3, 2)[index - 1])

    fun pillB(context: Context, index: Int): Int =
        int(context, "pill_b_$index", listOf(1, 1, 2)[index - 1])

    fun demoOffset(context: Context, index: Int): Int =
        int(context, "demo_offset_$index", listOf(1, 4, 7)[index - 1])

    fun setDemoOffset(context: Context, index: Int, minutesAfterNow: Int) {
        prefs(context).edit()
            .putInt("demo_offset_$index", minutesAfterNow)
            .apply()
    }

    fun setPlan(context: Context, index: Int, food: Int, pillA: Int, pillB: Int) {
        prefs(context).edit()
            .putInt("food_$index", food)
            .putInt("pill_a_$index", pillA)
            .putInt("pill_b_$index", pillB)
            .apply()
    }

    fun lifeSchedules(context: Context): List<DailySchedule> {
        val raw = prefs(context).getString(KEY_LIFE_SCHEDULES, "").orEmpty()

        if (raw.isBlank()) return emptyList()

        return raw.split(';')
            .mapNotNull { entry ->
                val parts = entry.split(',')
                if (parts.size != 5) return@mapNotNull null
                val hour = parts[0].toIntOrNull() ?: return@mapNotNull null
                val minute = parts[1].toIntOrNull() ?: return@mapNotNull null
                val food = parts[2].toIntOrNull() ?: return@mapNotNull null
                val pillA = parts[3].toIntOrNull() ?: return@mapNotNull null
                val pillB = parts[4].toIntOrNull() ?: return@mapNotNull null
                if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
                DailySchedule(hour, minute, food, pillA, pillB)
            }
            .sortedBy { it.minuteOfDay }
            .take(10)
    }

    fun setLifeSchedules(context: Context, schedules: List<DailySchedule>) {
        val encoded = schedules
            .sortedBy { it.minuteOfDay }
            .take(10)
            .joinToString(";") { "${it.hour},${it.minute},${it.foodGram},${it.pillA},${it.pillB}" }

        prefs(context).edit()
            .putString(KEY_LIFE_SCHEDULES, encoded)
            .apply()
    }

    fun lifeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LIFE_ENABLED, false)

    fun setLifeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_LIFE_ENABLED, enabled)
            .apply()
    }

    fun lastDeviceSync(context: Context): String =
        prefs(context).getString(KEY_LAST_DEVICE_SYNC, "").orEmpty()

    fun setLastDeviceSync(context: Context, value: String) {
        prefs(context).edit()
            .putString(KEY_LAST_DEVICE_SYNC, value)
            .apply()
    }

    private fun int(context: Context, key: String, default: Int): Int =
        prefs(context).getInt(key, default)
}
