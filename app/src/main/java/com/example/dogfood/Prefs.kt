package com.example.dogfood

import android.content.Context

object Prefs {
    private const val NAME = "dog_food_settings"
    private const val KEY_DOG_NAME = "dog_name"
    private const val KEY_LIFE_SCHEDULES = "life_schedules"
    private const val KEY_LIFE_ENABLED = "life_enabled"
    private const val KEY_LAST_DEVICE_SYNC = "last_device_sync"

    fun dogName(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_DOG_NAME, "")
            .orEmpty()

    fun setDogName(context: Context, value: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DOG_NAME, value)
            .apply()
    }

    fun food(context: Context, index: Int): Int = int(context, "food_$index", 65)
    fun pillA(context: Context, index: Int): Int =
        int(context, "pill_a_$index", listOf(2, 3, 2)[index - 1])
    fun pillB(context: Context, index: Int): Int =
        int(context, "pill_b_$index", listOf(1, 1, 2)[index - 1])

    fun demoOffset(context: Context, index: Int): Int =
        int(context, "demo_offset_$index", listOf(1, 4, 7)[index - 1])

    fun setDemoOffset(context: Context, index: Int, minutesAfterNow: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("demo_offset_$index", minutesAfterNow)
            .apply()
    }

    fun setPlan(context: Context, index: Int, food: Int, pillA: Int, pillB: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("food_$index", food)
            .putInt("pill_a_$index", pillA)
            .putInt("pill_b_$index", pillB)
            .apply()
    }

    fun lifeSchedules(context: Context): List<DailySchedule> {
        val raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_LIFE_SCHEDULES, "")
            .orEmpty()

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

        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIFE_SCHEDULES, encoded)
            .apply()
    }

    fun lifeEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIFE_ENABLED, false)

    fun setLifeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIFE_ENABLED, enabled)
            .apply()
    }

    fun lastDeviceSync(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_SYNC, "")
            .orEmpty()

    fun setLastDeviceSync(context: Context, value: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_SYNC, value)
            .apply()
    }

    private fun int(context: Context, key: String, default: Int): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(key, default)
}
