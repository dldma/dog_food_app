package com.example.dogfood

import android.content.Intent
import android.content.res.ColorStateList
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
import java.time.format.DateTimeFormatter

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
        binding.txtTodayFood.text = "${today.sumOf { it.foodEstimatedGram }} g"
        binding.txtTodayWater.text = "${today.sumOf { it.waterEstimatedGram }} g"

        binding.recordContainer.removeAllViews()
        binding.emptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.btnShare.isEnabled = records.isNotEmpty()
        binding.btnClear.isEnabled = records.isNotEmpty()
        binding.btnShare.alpha = if (records.isNotEmpty()) 1f else 0.45f
        binding.btnClear.alpha = if (records.isNotEmpty()) 1f else 0.45f

        records.forEach { binding.recordContainer.addView(createRecordCard(it)) }
    }

    private fun createRecordCard(record: FeedingRecord): View {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.divider)
            setCardBackgroundColor(color(R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBox.addView(text(
            record.timestamp.format(DateTimeFormatter.ofPattern("M월 d일 HH:mm")),
            17f,
            R.color.text_primary,
            bold = true,
        ))
        titleBox.addView(text(
            record.timestamp.format(DateTimeFormatter.ofPattern("yyyy.MM.dd EEEE")),
            11f,
            R.color.text_muted,
        ).apply { setPadding(0, dp(3), 0, 0) })

        val badge = text(RecordStore.modeLabel(record.mode), 11f, R.color.primary, bold = true).apply {
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@RecordActivity, R.drawable.bg_chip_green)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30))
        }
        header.addView(titleBox)
        header.addView(badge)
        body.addView(header)

        body.addView(text("추정 섭취량", 12f, R.color.text_muted, bold = true).apply {
            setPadding(0, dp(14), 0, dp(6))
        })
        body.addView(metricRow("사료", "${record.foodEstimatedGram} g", "물", "${record.waterEstimatedGram} g"))

        body.addView(text("투약", 12f, R.color.text_muted, bold = true).apply {
            setPadding(0, dp(12), 0, dp(6))
        })
        body.addView(metricRow(
            record.pillAName,
            "${record.pillAEstimatedCount}개",
            record.pillBName,
            "${record.pillBEstimatedCount}개",
        ))

        card.addView(body)
        return card
    }

    private fun metricRow(label1: String, value1: String, label2: String, value2: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        row.addView(metricBox(label1, value1, endMargin = dp(5)))
        row.addView(metricBox(label2, value2, startMargin = dp(5)))
        return row
    }

    private fun metricBox(label: String, value: String, startMargin: Int = 0, endMargin: Int = 0): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@RecordActivity, R.drawable.bg_soft_panel)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = startMargin
                marginEnd = endMargin
            }
            addView(text(label, 11f, R.color.text_secondary))
            addView(text(value, 17f, R.color.text_primary, bold = true).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }
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
            putExtra(Intent.EXTRA_SUBJECT, "반려견 급식 기록")
            putExtra(Intent.EXTRA_TEXT, RecordStore.exportText(records))
        }
        startActivity(Intent.createChooser(send, "기록 공유"))
    }

    private fun confirmClear() {
        if (records.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("기록을 모두 삭제할까요?")
            .setMessage("저장된 급식·추정 섭취 기록이 모두 삭제됩니다. 이 작업은 되돌릴 수 없습니다.")
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
