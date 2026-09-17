package com.example.dogfood

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dogfood.databinding.ActivitySettingsBinding
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var selectedBirthDate: LocalDate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBirthDate.setOnClickListener { showBirthDatePicker() }
        binding.btnSave.setOnClickListener { saveSettings() }
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
        switchOverweight.isChecked = Prefs.overweight(this@SettingsActivity)

        val kcal = Prefs.foodKcalPerGram(this@SettingsActivity)
        if (kcal > 0f) edtFoodKcal.setText(trimFloat(kcal))

        edtDefaultFood.setText(Prefs.defaultFoodGram(this@SettingsActivity).toString())
        edtPillAName.setText(Prefs.pillAName(this@SettingsActivity))
        edtPillBName.setText(Prefs.pillBName(this@SettingsActivity))

        // 기존 App Inventor의 자동 급식량 계산식이 아직 확인되지 않아
        // Stage 6에서는 임의의 계산식을 넣지 않고 직접 입력만 활성화한다.
        radioManual.isChecked = true
        radioAuto.isEnabled = false
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

    private fun saveSettings() = with(binding) {
        val name = edtDogName.text?.toString()?.trim().orEmpty()
        val weight = edtWeight.text?.toString()?.trim()?.toFloatOrNull() ?: 0f
        val kcal = edtFoodKcal.text?.toString()?.trim()?.toFloatOrNull() ?: 0f
        val defaultFood = edtDefaultFood.text?.toString()?.trim()?.toIntOrNull()
        val pillAName = edtPillAName.text?.toString()?.trim().orEmpty().ifBlank { "약 A" }
        val pillBName = edtPillBName.text?.toString()?.trim().orEmpty().ifBlank { "약 B" }

        if (weight < 0f || weight > 100f) {
            toast("몸무게는 0~100kg 범위로 입력해주세요.")
            return@with
        }
        if (kcal < 0f || kcal > 20f) {
            toast("사료 칼로리는 0~20 kcal/g 범위로 입력해주세요.")
            return@with
        }
        if (defaultFood == null || defaultFood !in 1..999) {
            toast("기본 1회 급식량은 1~999g 범위로 입력해주세요.")
            return@with
        }

        Prefs.setProfile(
            context = this@SettingsActivity,
            dogName = name,
            birthDate = selectedBirthDate?.toString().orEmpty(),
            weightKg = weight,
            neutered = switchNeutered.isChecked,
            overweight = switchOverweight.isChecked,
            foodKcalPerGram = kcal,
            defaultFoodGram = defaultFood,
            pillAName = pillAName,
            pillBName = pillBName,
        )

        toast("보호자 설정을 저장했습니다.")
        finish()
    }

    private fun trimFloat(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
