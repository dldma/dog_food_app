package com.example.dogfood

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dogfood.databinding.ActivitySettingsBinding
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var selectedBirthDate: LocalDate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        bindActions()
        updateFoodModeUi()
        refreshRecommendationPreview(showError = false, applyToDefault = false)
    }

    private fun bindActions() = with(binding) {
        btnBack.setOnClickListener { finish() }
        btnBirthDate.setOnClickListener { showBirthDatePicker() }
        btnCalculateFood.setOnClickListener {
            radioAuto.isChecked = true
            val recommendation = refreshRecommendationPreview(showError = true, applyToDefault = true)
            if (recommendation != null) {
                toast("계산한 1회 권장량을 기본 급식량에 적용했습니다.")
            }
        }
        radioManual.setOnCheckedChangeListener { _, _ -> updateFoodModeUi() }
        radioAuto.setOnCheckedChangeListener { _, isChecked ->
            updateFoodModeUi()
            if (isChecked) refreshRecommendationPreview(showError = false, applyToDefault = true)
        }
        btnSave.setOnClickListener { saveSettings() }
    }

    private fun loadSettings() = with(binding) {
        edtDogName.setText(Prefs.dogName(this@SettingsActivity))

        selectedBirthDate = Prefs.dogBirthDate(this@SettingsActivity)
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        refreshBirthDate()

        val weight = Prefs.dogWeight(this@SettingsActivity)
        if (weight > 0f) edtWeight.setText(trimFloat(weight))

        switchNeutered.isChecked = Prefs.neutered(this@SettingsActivity)
        when (Prefs.bodyCondition(this@SettingsActivity)) {
            Prefs.BCS_OVERWEIGHT -> radioBcsOverweight.isChecked = true
            Prefs.BCS_OBESE -> radioBcsObese.isChecked = true
            else -> radioBcsNormal.isChecked = true
        }

        val kcalPerKg = Prefs.foodKcalPerKg(this@SettingsActivity)
        if (kcalPerKg > 0f) edtFoodKcal.setText(trimFloat(kcalPerKg))

        edtFeedingCount.setText(Prefs.feedingCount(this@SettingsActivity).toString())
        edtDefaultFood.setText(Prefs.defaultFoodGram(this@SettingsActivity).toString())
        edtPillAName.setText(Prefs.pillAName(this@SettingsActivity))
        edtPillBName.setText(Prefs.pillBName(this@SettingsActivity))

        if (Prefs.autoFoodMode(this@SettingsActivity)) {
            radioAuto.isChecked = true
        } else {
            radioManual.isChecked = true
        }
    }

    private fun showBirthDatePicker() {
        val base = selectedBirthDate ?: LocalDate.now().minusYears(1)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedBirthDate = LocalDate.of(year, month + 1, day)
                refreshBirthDate()
            },
            base.year,
            base.monthValue - 1,
            base.dayOfMonth,
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun refreshBirthDate() = with(binding) {
        val date = selectedBirthDate
        if (date == null) {
            btnBirthDate.text = "생년월일 선택"
            txtAge.text = "생년월일을 입력하면 현재 나이를 표시합니다."
            return@with
        }

        btnBirthDate.text = date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일"))
        val age = Period.between(date, LocalDate.now())
        txtAge.text = when {
            age.years > 0 -> "만 ${age.years}세 ${age.months}개월"
            age.months > 0 -> "${age.months}개월"
            else -> "${age.days}일"
        }
    }

    private fun selectedBodyCondition(): String = when (binding.bcsGroup.checkedRadioButtonId) {
        binding.radioBcsOverweight.id -> Prefs.BCS_OVERWEIGHT
        binding.radioBcsObese.id -> Prefs.BCS_OBESE
        else -> Prefs.BCS_NORMAL
    }

    private fun calculateRecommendation(showError: Boolean): FeedingRecommendation? = with(binding) {
        val weight = edtWeight.text?.toString()?.trim()?.toDoubleOrNull()
        val kcalPerKg = edtFoodKcal.text?.toString()?.trim()?.toDoubleOrNull()
        val feedingCount = edtFeedingCount.text?.toString()?.trim()?.toIntOrNull()

        fun invalid(message: String): FeedingRecommendation? {
            if (showError) toast(message)
            return null
        }

        if (weight == null || weight <= 0.0 || weight > 100.0) {
            return@with invalid("자동 계산을 위해 몸무게를 0보다 크고 100kg 이하로 입력해주세요.")
        }
        if (kcalPerKg == null || kcalPerKg < 500.0 || kcalPerKg > 10000.0) {
            return@with invalid("사료 열량을 kcal/kg 단위로 입력해주세요. 예: 3500")
        }
        if (feedingCount == null || feedingCount !in 1..10) {
            return@with invalid("하루 급여 횟수는 1~10회 범위로 입력해주세요.")
        }

        FeedingCalculator.calculate(
            weightKg = weight,
            bodyCondition = selectedBodyCondition(),
            neutered = switchNeutered.isChecked,
            foodKcalPerKg = kcalPerKg,
            feedingCount = feedingCount,
        )
    }

    private fun refreshRecommendationPreview(
        showError: Boolean,
        applyToDefault: Boolean,
    ): FeedingRecommendation? {
        val recommendation = calculateRecommendation(showError) ?: run {
            binding.txtRecommendation.text =
                "체중, 체형(BCS), 중성화 여부, 사료 열량과 하루 급여 횟수를 입력하면 권장량을 계산합니다."
            return null
        }

        binding.txtRecommendation.text = buildString {
            append("K = ${format(recommendation.coefficient, 2)}\n")
            append("MER = ${format(recommendation.merKcal, 1)} kcal/day\n")
            append("하루 권장 급여량 = ${format(recommendation.dailyGram, 1)} g\n")
            append("1회 권장 급여량 = ${format(recommendation.perMealGram, 1)} g")
            append("  (${recommendation.feedingCount}회/일)")
        }

        if (applyToDefault) {
            val rounded = recommendation.perMealGram.roundToInt().coerceIn(1, 999)
            binding.edtDefaultFood.setText(rounded.toString())
        }
        return recommendation
    }

    private fun updateFoodModeUi() = with(binding) {
        val auto = radioAuto.isChecked
        edtDefaultFood.isEnabled = !auto
        txtDefaultFoodHelp.text = if (auto) {
            "자동 계산된 1회 권장 급여량을 반올림해 기본 급식량으로 사용합니다. 각 생활 예약에서는 별도로 수정할 수 있습니다."
        } else {
            "새 생활 예약을 추가할 때 이 값이 기본 사료량으로 입력됩니다. 각 예약에서는 별도로 수정할 수 있습니다."
        }
    }

    private fun saveSettings() = with(binding) {
        val name = edtDogName.text?.toString()?.trim().orEmpty()
        val weight = edtWeight.text?.toString()?.trim()?.toFloatOrNull() ?: 0f
        val kcalPerKg = edtFoodKcal.text?.toString()?.trim()?.toFloatOrNull() ?: 0f
        val feedingCount = edtFeedingCount.text?.toString()?.trim()?.toIntOrNull()
        val pillAName = edtPillAName.text?.toString()?.trim().orEmpty().ifBlank { "약 A" }
        val pillBName = edtPillBName.text?.toString()?.trim().orEmpty().ifBlank { "약 B" }
        val autoMode = radioAuto.isChecked

        if (weight < 0f || weight > 100f) {
            toast("몸무게는 0~100kg 범위로 입력해주세요.")
            return@with
        }
        if (kcalPerKg < 0f || kcalPerKg > 10000f) {
            toast("사료 열량은 0~10000 kcal/kg 범위로 입력해주세요.")
            return@with
        }
        if (feedingCount == null || feedingCount !in 1..10) {
            toast("하루 급여 횟수는 1~10회 범위로 입력해주세요.")
            return@with
        }

        val defaultFood = if (autoMode) {
            val recommendation = refreshRecommendationPreview(showError = true, applyToDefault = true)
                ?: return@with
            recommendation.perMealGram.roundToInt().coerceIn(1, 999)
        } else {
            val manual = edtDefaultFood.text?.toString()?.trim()?.toIntOrNull()
            if (manual == null || manual !in 1..999) {
                toast("기본 1회 급식량은 1~999g 범위로 입력해주세요.")
                return@with
            }
            manual
        }

        Prefs.setProfile(
            context = this@SettingsActivity,
            dogName = name,
            birthDate = selectedBirthDate?.toString().orEmpty(),
            weightKg = weight,
            neutered = switchNeutered.isChecked,
            bodyCondition = selectedBodyCondition(),
            foodKcalPerKg = kcalPerKg,
            feedingCount = feedingCount,
            autoFoodMode = autoMode,
            defaultFoodGram = defaultFood,
            pillAName = pillAName,
            pillBName = pillBName,
        )

        toast(
            if (autoMode) "자동 계산 급여량을 저장했습니다."
            else "보호자 설정을 저장했습니다."
        )
        finish()
    }

    private fun trimFloat(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.KOREA, "%.${decimals}f", value)

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
