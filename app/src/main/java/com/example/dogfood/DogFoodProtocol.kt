package com.example.dogfood

import java.time.LocalTime

data class DogState(
    val startMode: Int = 0,
    val waterSet: Int = 0,
    val waterWeight: Int = 0,
    val foodWeight: Int = 0,
    val pillACount: Int = 0,
    val pillBCount: Int = 0,
    val waterLow: Int = 0,
    val coverMode: Int = 0,
    val foodLow: Int = 0,
    val waterEat: Int = 0,
) {
    val waterConsumed: Int get() = waterEat * 30
    val pillAConsumed: Int get() = (7 - pillACount).coerceAtLeast(0)
    val pillBConsumed: Int get() = (7 - pillBCount).coerceAtLeast(0)
}

data class DeviceFeedEvent(
    val sequence: Int,
    val hour: Int,
    val minute: Int,
    val foodGram: Int,
    val pillA: Int,
    val pillB: Int,
)

object DogFoodProtocol {
    const val PACKET_LENGTH = 18
    const val MAX_DAILY_SCHEDULES = 10

    // App Inventor 블록에서 확인된 수동 명령
    const val CMD_WATER_UP = "o"
    const val CMD_WATER_DOWN = "p"
    const val CMD_COVER_CLOSE = "e"
    const val CMD_COVER_OPEN = "f"
    const val CMD_REFILL_DONE = "m"
    const val CMD_RESET = "j"

    // 실제 ATmega128 코드에 이미 구현되어 있는 개발자용 직접 제어 명령
    const val CMD_FOOD_MOTOR_ON = "a"
    const val CMD_FOOD_MOTOR_OFF = "b"
    const val CMD_WATER_MOTOR_ON = "c"
    const val CMD_WATER_MOTOR_OFF = "d"
    const val CMD_COVER_STOP = "g"
    const val CMD_PILL_A_STEP = "h"
    const val CMD_PILL_B_STEP = "i"

    // 실제 ATmega128 코드의 모드 토글 명령
    const val CMD_START = "k"

    // 생활모드 명령
    const val CMD_DAILY_CLEAR = "R\n"
    const val CMD_DAILY_ENABLE = "E\n"
    const val CMD_DAILY_PAUSE = "X\n"
    const val CMD_EVENT_REPLAY = "Q\n"

    fun parse(packet: String): DogState? {
        if (packet.length != PACKET_LENGTH || packet[0] != 'D') return null

        fun part(start: Int, end: Int): Int =
            packet.substring(start, end).trim().toIntOrNull() ?: 0

        return DogState(
            startMode = part(1, 2),
            waterSet = part(2, 5),
            waterWeight = part(5, 8),
            foodWeight = part(8, 11),
            pillACount = part(11, 12),
            pillBCount = part(12, 13),
            waterLow = part(13, 14),
            coverMode = part(14, 15),
            foodLow = part(15, 16),
            waterEat = part(16, 18),
        )
    }

    // Stage 10: !L,seq,HHMM,food,A,B
    fun parseDeviceEvent(line: String): DeviceFeedEvent? {
        val parts = line.trim().split(',')
        if (parts.size != 6 || parts[0] != "!L") return null

        val sequence = parts[1].toIntOrNull() ?: return null
        val hhmm = parts[2]
        if (hhmm.length != 4) return null
        val hour = hhmm.substring(0, 2).toIntOrNull() ?: return null
        val minute = hhmm.substring(2, 4).toIntOrNull() ?: return null
        val food = parts[3].toIntOrNull() ?: return null
        val pillA = parts[4].toIntOrNull() ?: return null
        val pillB = parts[5].toIntOrNull() ?: return null

        if (sequence !in 0..65535 || hour !in 0..23 || minute !in 0..59) return null
        if (food !in 0..999 || pillA !in 0..7 || pillB !in 0..7) return null

        return DeviceFeedEvent(sequence, hour, minute, food, pillA, pillB)
    }

    fun feedingCommand(foodGram: Int, pillA: Int, pillB: Int): String =
        "wx${foodGram}y${pillA}z${pillB}\n"

    fun clockCommand(time: LocalTime = LocalTime.now()): String =
        "T%02d%02d%02d\n".format(time.hour, time.minute, time.second)

    fun dailyScheduleCommand(schedule: DailySchedule): String =
        "S%02d%02dx%dy%dz%d\n".format(
            schedule.hour,
            schedule.minute,
            schedule.foodGram,
            schedule.pillA,
            schedule.pillB,
        )
}
