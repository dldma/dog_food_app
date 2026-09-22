package com.example.dogfood

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.dogfood.databinding.ActivityRecordBinding
import com.google.android.material.card.MaterialCardView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class RecordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecordBinding
    private var records: List<FeedingRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareRecords() }
        binding.btnClear.setOnClickListener { confirmClear() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        records = RecordStore.readAll(this)
        val today = RecordStore.today(records)

        binding.txtTotalCount.text = records.size.toString()
        binding.txtTodayCount.text = today.size.toString()
        binding.txtTodayFood.text = "${today.filter { it.foodEstimateAvailable }.sumOf { it.foodEstimatedGram }} g"
        binding.txtTodayWater.text = "${today.sumOf { it.waterEstimatedGram }} g"

        binding.recordContainer.removeAllViews()
        binding.emptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.btnShare.isEnabled = records.isNotEmpty()
        binding.btnClear.isEnabled = records.isNotEmpty()
        binding.btnShare.alpha = if (records.isNotEmpty()) 1f else 0.45f
        binding.btnClear.alpha = if (records.isNotEmpty()) 1f else 0.45f

        records
            .groupBy { it.timestamp.toLocalDate() }
            .toSortedMap(compareByDescending { it })
            .forEach { (date, dayRecords) ->
                binding.recordContainer.addView(createDailyCard(date, dayRecords))
            }
    }

    /**
     * 날짜 하나를 카드 하나로 보여준다.
     * 카드 상단은 하루 총량, 하단은 시간순 섭취/급식 기록이다.
     */
    private fun createDailyCard(date: LocalDate, dayRecords: List<FeedingRecord>): View {
        val chronological = dayRecords.sortedBy { it.timestamp }
        val foodTotal = chronological.filter { it.foodEstimateAvailable }.sumOf { it.foodEstimatedGram }
        val waterTotal = chronological.sumOf { it.waterEstimatedGram }

        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.divider)
            setCardBackgroundColor(color(R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val isToday = date == LocalDate.now()
        val dateTitle = if (isToday) {
            "오늘 · ${date.format(DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN))}"
        } else {
            date.format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN))
        }
        titleBox.addView(text(dateTitle, 18f, R.color.text_primary, bold = true))
        titleBox.addView(text(
            "하루 전체 기록 · ${chronological.size}건",
            11f,
            R.color.text_muted,
        ).apply { setPadding(0, dp(3), 0, 0) })

        val dayBadge = text(if (isToday) "오늘" else "기록", 11f, R.color.primary, bold = true).apply {
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@RecordActivity, R.drawable.bg_chip_green)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30))
        }

        header.addView(titleBox)
        header.addView(dayBadge)
        body.addView(header)

        body.addView(text("하루 요약", 12f, R.color.text_muted, bold = true).apply {
            setPadding(0, dp(15), 0, dp(6))
        })
        body.addView(summaryRow(
            "급식 기록", "${chronological.size}회",
            "사료 섭취", "${foodTotal} g",
            "물 섭취", "${waterTotal} g",
        ))

        body.addView(text("시간별 섭취 기록", 13f, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(18), 0, dp(8))
        })

        chronological.forEachIndexed { index, record ->
            body.addView(createTimelineRow(record))
            if (index != chronological.lastIndex) {
                body.addView(View(this).apply {
                    setBackgroundColor(color(R.color.divider))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1),
                    ).apply {
                        marginStart = dp(62)
                        topMargin = dp(8)
                        bottomMargin = dp(8)
                    }
                })
            }
        }

        card.addView(body)
        return card
    }

    private fun createTimelineRow(record: FeedingRecord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val timeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        }
        timeBox.addView(text(
            record.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
            15f,
            R.color.primary,
            bold = true,
        ))
        timeBox.addView(text(
            RecordStore.modeLabel(record.mode),
            9f,
            R.color.text_muted,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        })

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val mainText = when {
            record.foodEstimateAvailable -> "사료 ${record.foodEstimatedGram}g 섭취"
            record.foodDispensedGram > 0 -> "사료 ${record.foodDispensedGram}g 배출 · 섭취량 미측정"
            else -> "급식 실행 · 섭취량 미측정"
        }
        detail.addView(text(mainText, 15f, R.color.text_primary, bold = true))

        val subItems = mutableListOf<String>()
        if (record.foodEstimateAvailable && record.foodDispensedGram > 0) {
            subItems += "배출 ${record.foodDispensedGram}g"
        }
        if (record.waterEstimatedGram > 0) {
            subItems += "물 ${record.waterEstimatedGram}g 섭취"
        }
        if (record.pillAEstimatedCount > 0) {
            subItems += "${record.pillAName} ${record.pillAEstimatedCount}개"
        }
        if (record.pillBEstimatedCount > 0) {
            subItems += "${record.pillBName} ${record.pillBEstimatedCount}개"
        }

        if (subItems.isNotEmpty()) {
            detail.addView(text(
                subItems.joinToString(" · "),
                11f,
                R.color.text_secondary,
            ).apply { setPadding(0, dp(4), 0, 0) })
        }

        if (!record.foodEstimateAvailable) {
            detail.addView(text(
                "장치 실행 기록만 확인되어 실제 섭취량은 계산하지 않았습니다.",
                10f,
                R.color.text_muted,
            ).apply { setPadding(0, dp(4), 0, 0) })
        }

        row.addView(timeBox)
        row.addView(detail)
        return row
    }

    private fun summaryRow(
        label1: String,
        value1: String,
        label2: String,
        value2: String,
        label3: String,
        value3: String,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        row.addView(summaryBox(label1, value1, endMargin = dp(4)))
        row.addView(summaryBox(label2, value2, startMargin = dp(4), endMargin = dp(4)))
        row.addView(summaryBox(label3, value3, startMargin = dp(4)))
        return row
    }

    private fun summaryBox(
        label: String,
        value: String,
        startMargin: Int = 0,
        endMargin: Int = 0,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = ContextCompat.getDrawable(this@RecordActivity, R.drawable.bg_soft_panel)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = startMargin
            marginEnd = endMargin
        }
        addView(text(label, 10f, R.color.text_secondary))
        addView(text(value, 16f, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun text(value: String, sizeSp: Float, colorRes: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color(colorRes))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun shareRecords() {
        if (records.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "반려견 일별 급식 기록")
            putExtra(Intent.EXTRA_TEXT, RecordStore.exportText(records))
        }
        startActivity(Intent.createChooser(send, "기록 공유"))
    }

    private fun confirmClear() {
        if (records.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("기록을 모두 삭제할까요?")
            .setMessage("저장된 일별 급식·추정 섭취 기록이 모두 삭제됩니다. 이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                RecordStore.clear(this)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
