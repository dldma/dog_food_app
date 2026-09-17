package com.example.dogfood

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

object DogFoodProtocol {
    const val PACKET_LENGTH = 18

    // App Inventor 블록에서 확인된 수동 명령
    const val CMD_WATER_UP = "o"
    const val CMD_WATER_DOWN = "p"
    const val CMD_COVER_CLOSE = "e"
    const val CMD_COVER_OPEN = "f"
    const val CMD_REFILL_DONE = "m"
    const val CMD_RESET = "j"

    // 실제 ATmega128 코드의 모드 토글 명령
    const val CMD_START = "k"

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

    fun feedingCommand(foodGram: Int, pillA: Int, pillB: Int): String =
        "wx${foodGram}y${pillA}z${pillB}\n"
}
