package com.example.dogfood

import android.content.Context

object Prefs {
    private const val NAME = "dog_food_settings"
    private const val KEY_DOG_NAME = "dog_name"

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

    fun setPlan(context: Context, index: Int, food: Int, pillA: Int, pillB: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("food_$index", food)
            .putInt("pill_a_$index", pillA)
            .putInt("pill_b_$index", pillB)
            .apply()
    }

    private fun int(context: Context, key: String, default: Int): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(key, default)
}
