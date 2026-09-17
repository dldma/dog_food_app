package com.example.dogfood

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class FeedingRecord(
    val timestamp: LocalDateTime,
    val mode: String,
    val waterEstimatedGram: Int,
    val foodEstimatedGram: Int,
    val pillAEstimatedCount: Int,
    val pillBEstimatedCount: Int,
    val pillAName: String,
    val pillBName: String,
)

object RecordStore {
    private const val FILE_NAME = "애견급식기.txt"
    private const val VERSION = "V2"
    private val legacyTime = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun appendDemoRecord(
        context: Context,
        waterEstimatedGram: Int,
        foodEstimatedGram: Int,
        pillAEstimatedCount: Int,
        pillBEstimatedCount: Int,
        pillAName: String,
        pillBName: String,
    ) {
        val safeA = sanitize(pillAName.ifBlank { "약 A" })
        val safeB = sanitize(pillBName.ifBlank { "약 B" })
        val line = listOf(
            VERSION,
            LocalDateTime.now().toString(),
            "DEMO",
            waterEstimatedGram.coerceAtLeast(0).toString(),
            foodEstimatedGram.coerceAtLeast(0).toString(),
            pillAEstimatedCount.coerceAtLeast(0).toString(),
            pillBEstimatedCount.coerceAtLeast(0).toString(),
            safeA,
            safeB,
        ).joinToString("\t") + "\n"

        file(context).appendText(line, Charsets.UTF_8)
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

        val timeFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
        return buildString {
            appendLine("반려견 케어 스테이션 기록")
            appendLine("※ 섭취량은 센서 무게 변화에 기반한 추정값입니다.")
            appendLine()
            records.sortedByDescending { it.timestamp }.forEach { record ->
                appendLine("${record.timestamp.format(timeFmt)} · ${modeLabel(record.mode)}")
                appendLine("물 추정 섭취량 ${record.waterEstimatedGram}g / 사료 추정 섭취량 ${record.foodEstimatedGram}g")
                appendLine("${record.pillAName} ${record.pillAEstimatedCount}개 / ${record.pillBName} ${record.pillBEstimatedCount}개")
                appendLine()
            }
        }.trimEnd()
    }

    fun modeLabel(mode: String): String = when (mode.uppercase()) {
        "DEMO" -> "시연 급식"
        "LIFE" -> "생활 급식"
        else -> "급식 기록"
    }

    private fun parseLine(line: String): FeedingRecord? {
        if (line.isBlank()) return null
        return parseV2(line) ?: parseLegacy(line)
    }

    private fun parseV2(line: String): FeedingRecord? {
        val parts = line.split('\t')
        if (parts.size < 9 || parts[0] != VERSION) return null

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

    private fun sanitize(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
