package com.example.dogfood

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 한 번의 급식/섭취 측정 기록.
 *
 * 새 시연 모드에서는 각 회차 측정이 끝날 때마다 1건씩 저장하므로
 * 하루 화면에서 "몇 시에 얼마나 먹었는지"를 시간순으로 확인할 수 있다.
 */
data class FeedingRecord(
    val timestamp: LocalDateTime,
    val mode: String,
    val waterEstimatedGram: Int,
    val foodEstimatedGram: Int,
    val pillAEstimatedCount: Int,
    val pillBEstimatedCount: Int,
    val pillAName: String,
    val pillBName: String,
    val foodDispensedGram: Int = 0,
    val foodEstimateAvailable: Boolean = true,
    val deviceSequence: Int = -1,
)

object RecordStore {
    private const val FILE_NAME = "애견급식기.txt"
    private const val VERSION_V2 = "V2"
    private const val VERSION_V3 = "V3"
    private val legacyTime = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    /**
     * 기존 버전 호환용: 시연 전체 결과를 한 건으로 저장할 때 사용하던 함수.
     * 새 시연 흐름에서는 appendDemoMealRecord()를 사용한다.
     */
    fun appendDemoRecord(
        context: Context,
        waterEstimatedGram: Int,
        foodEstimatedGram: Int,
        pillAEstimatedCount: Int,
        pillBEstimatedCount: Int,
        pillAName: String,
        pillBName: String,
    ) {
        appendV3(
            context = context,
            record = FeedingRecord(
                timestamp = LocalDateTime.now(),
                mode = "DEMO",
                waterEstimatedGram = waterEstimatedGram.coerceAtLeast(0),
                foodEstimatedGram = foodEstimatedGram.coerceAtLeast(0),
                pillAEstimatedCount = pillAEstimatedCount.coerceAtLeast(0),
                pillBEstimatedCount = pillBEstimatedCount.coerceAtLeast(0),
                pillAName = pillAName,
                pillBName = pillBName,
                foodDispensedGram = 0,
                foodEstimateAvailable = true,
            ),
        )
    }

    /**
     * 시연 급식 한 회차의 실제 측정 결과를 즉시 저장한다.
     * timestamp가 각 회차의 "먹은 시각"으로 기록 화면에 표시된다.
     */
    fun appendDemoMealRecord(
        context: Context,
        timestamp: LocalDateTime,
        waterEstimatedGram: Int,
        foodEstimatedGram: Int,
        foodDispensedGram: Int,
        pillAEstimatedCount: Int,
        pillBEstimatedCount: Int,
        pillAName: String,
        pillBName: String,
    ) {
        appendV3(
            context = context,
            record = FeedingRecord(
                timestamp = timestamp,
                mode = "DEMO_MEAL",
                waterEstimatedGram = waterEstimatedGram.coerceAtLeast(0),
                foodEstimatedGram = foodEstimatedGram.coerceAtLeast(0),
                pillAEstimatedCount = pillAEstimatedCount.coerceAtLeast(0),
                pillBEstimatedCount = pillBEstimatedCount.coerceAtLeast(0),
                pillAName = pillAName,
                pillBName = pillBName,
                foodDispensedGram = foodDispensedGram.coerceAtLeast(0),
                foodEstimateAvailable = true,
            ),
        )
    }

    fun appendLifeExecution(
        context: Context,
        event: DeviceFeedEvent,
        pillAName: String,
        pillBName: String,
    ): Boolean {
        val timestamp = inferEventTimestamp(event.hour, event.minute)
        val existing = readAll(context).any {
            it.mode == "LIFE" &&
                it.deviceSequence == event.sequence &&
                it.timestamp.toLocalDate() == timestamp.toLocalDate() &&
                it.timestamp.hour == timestamp.hour &&
                it.timestamp.minute == timestamp.minute
        }
        if (existing) return false

        appendV3(
            context = context,
            record = FeedingRecord(
                timestamp = timestamp,
                mode = "LIFE",
                waterEstimatedGram = 0,
                foodEstimatedGram = 0,
                pillAEstimatedCount = event.pillA,
                pillBEstimatedCount = event.pillB,
                pillAName = pillAName,
                pillBName = pillBName,
                foodDispensedGram = event.foodGram,
                foodEstimateAvailable = false,
                deviceSequence = event.sequence,
            ),
        )
        return true
    }

    fun readAll(context: Context): List<FeedingRecord> {
        val target = file(context)
        if (!target.exists() || target.length() == 0L) return emptyList()

        return target.readLines(Charsets.UTF_8)
            .mapNotNull(::parseLine)
            .sortedByDescending { it.timestamp }
    }

    fun clear(context: Context) {
        file(context).writeText("", Charsets.UTF_8)
    }

    fun today(records: List<FeedingRecord>): List<FeedingRecord> {
        val today = LocalDate.now()
        return records.filter { it.timestamp.toLocalDate() == today }
    }

    fun exportText(records: List<FeedingRecord>): String {
        if (records.isEmpty()) return "저장된 급식 기록이 없습니다."

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val dateFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd")
        val byDate = records.groupBy { it.timestamp.toLocalDate() }
            .toSortedMap(compareByDescending { it })

        return buildString {
            appendLine("반려견 케어 스테이션 일별 기록")
            appendLine("※ 섭취량은 센서 무게 변화에 기반한 추정값입니다.")
            appendLine("※ 생활 예약은 장치 실행 시각과 배출 설정량을 기록하며, 휴대폰이 연결되지 않았던 경우 섭취 추정값은 제공되지 않을 수 있습니다.")
            appendLine()

            byDate.forEach { (date, dayRecords) ->
                val chronological = dayRecords.sortedBy { it.timestamp }
                val foodTotal = chronological.filter { it.foodEstimateAvailable }.sumOf { it.foodEstimatedGram }
                val waterTotal = chronological.sumOf { it.waterEstimatedGram }
                appendLine("[${date.format(dateFmt)}] 급식 ${chronological.size}회 · 사료 추정 ${foodTotal}g · 물 추정 ${waterTotal}g")

                chronological.forEach { record ->
                    append("${record.timestamp.format(timeFmt)} · ${modeLabel(record.mode)}")
                    if (record.foodEstimateAvailable) {
                        append(" · 사료 ${record.foodEstimatedGram}g 섭취")
                    } else if (record.foodDispensedGram > 0) {
                        append(" · 사료 ${record.foodDispensedGram}g 배출 / 섭취량 미측정")
                    }
                    if (record.waterEstimatedGram > 0) append(" · 물 ${record.waterEstimatedGram}g")
                    if (record.foodDispensedGram > 0 && record.foodEstimateAvailable) {
                        append(" · 배출 ${record.foodDispensedGram}g")
                    }
                    if (record.pillAEstimatedCount > 0 || record.pillBEstimatedCount > 0) {
                        append(" · ${record.pillAName} ${record.pillAEstimatedCount}개 / ${record.pillBName} ${record.pillBEstimatedCount}개")
                    }
                    appendLine()
                }
                appendLine()
            }
        }.trimEnd()
    }

    fun modeLabel(mode: String): String = when (mode.uppercase()) {
        "DEMO_MEAL" -> "시연 급식"
        "DEMO" -> "시연 급식"
        "LIFE" -> "생활 급식"
        else -> "급식 기록"
    }

    private fun appendV3(context: Context, record: FeedingRecord) {
        val line = listOf(
            VERSION_V3,
            record.timestamp.toString(),
            sanitize(record.mode),
            record.waterEstimatedGram.coerceAtLeast(0).toString(),
            record.foodEstimatedGram.coerceAtLeast(0).toString(),
            record.pillAEstimatedCount.coerceAtLeast(0).toString(),
            record.pillBEstimatedCount.coerceAtLeast(0).toString(),
            sanitize(record.pillAName.ifBlank { "약 A" }),
            sanitize(record.pillBName.ifBlank { "약 B" }),
            record.foodDispensedGram.coerceAtLeast(0).toString(),
            if (record.foodEstimateAvailable) "1" else "0",
            record.deviceSequence.toString(),
        ).joinToString("\t") + "\n"
        file(context).appendText(line, Charsets.UTF_8)
    }

    private fun parseLine(line: String): FeedingRecord? {
        if (line.isBlank()) return null
        return parseV3(line) ?: parseV2(line) ?: parseLegacy(line)
    }

    private fun parseV3(line: String): FeedingRecord? {
        val parts = line.split('\t')
        if (parts.size < 12 || parts[0] != VERSION_V3) return null
        val timestamp = runCatching { LocalDateTime.parse(parts[1]) }.getOrNull() ?: return null
        return FeedingRecord(
            timestamp = timestamp,
            mode = parts[2],
            waterEstimatedGram = parts[3].toIntOrNull() ?: 0,
            foodEstimatedGram = parts[4].toIntOrNull() ?: 0,
            pillAEstimatedCount = parts[5].toIntOrNull() ?: 0,
            pillBEstimatedCount = parts[6].toIntOrNull() ?: 0,
            pillAName = parts[7].ifBlank { "약 A" },
            pillBName = parts[8].ifBlank { "약 B" },
            foodDispensedGram = parts[9].toIntOrNull() ?: 0,
            foodEstimateAvailable = parts[10] == "1",
            deviceSequence = parts[11].toIntOrNull() ?: -1,
        )
    }

    private fun parseV2(line: String): FeedingRecord? {
        val parts = line.split('\t')
        if (parts.size < 9 || parts[0] != VERSION_V2) return null
        val timestamp = runCatching { LocalDateTime.parse(parts[1]) }.getOrNull() ?: return null
        return FeedingRecord(
            timestamp = timestamp,
            mode = parts[2],
            waterEstimatedGram = parts[3].toIntOrNull() ?: 0,
            foodEstimatedGram = parts[4].toIntOrNull() ?: 0,
            pillAEstimatedCount = parts[5].toIntOrNull() ?: 0,
            pillBEstimatedCount = parts[6].toIntOrNull() ?: 0,
            pillAName = parts[7].ifBlank { "약 A" },
            pillBName = parts[8].ifBlank { "약 B" },
        )
    }

    private fun parseLegacy(line: String): FeedingRecord? {
        val timestampText = line.substringBefore(" | ").trim()
        val timestamp = runCatching { LocalDateTime.parse(timestampText, legacyTime) }.getOrNull() ?: return null

        val segments = line.split(" | ").drop(1)
        val water = segments.firstOrNull { it.startsWith("물추정섭취량=") }
            ?.substringAfter('=')?.removeSuffix("g")?.toIntOrNull() ?: 0
        val food = segments.firstOrNull { it.startsWith("사료추정섭취량=") }
            ?.substringAfter('=')?.removeSuffix("g")?.toIntOrNull() ?: 0
        val pillSegments = segments.filter { it.contains("배출추정=") }
        val first = pillSegments.getOrNull(0)
        val second = pillSegments.getOrNull(1)

        fun pillName(segment: String?, fallback: String): String =
            segment?.substringBefore("배출추정=")?.trim().orEmpty().ifBlank { fallback }
        fun pillCount(segment: String?): Int =
            segment?.substringAfter("배출추정=")?.removeSuffix("ea")?.trim()?.toIntOrNull() ?: 0

        return FeedingRecord(
            timestamp = timestamp,
            mode = "DEMO",
            waterEstimatedGram = water,
            foodEstimatedGram = food,
            pillAEstimatedCount = pillCount(first),
            pillBEstimatedCount = pillCount(second),
            pillAName = pillName(first, "약 A"),
            pillBName = pillName(second, "약 B"),
        )
    }

    private fun inferEventTimestamp(hour: Int, minute: Int): LocalDateTime {
        val now = LocalDateTime.now()
        val eventToday = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
        // 자정 직후 재연결해 전날 늦은 이벤트를 받는 경우만 전날로 보정한다.
        return if (eventToday.isAfter(now.plusHours(2))) eventToday.minusDays(1) else eventToday
    }

    private fun sanitize(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
