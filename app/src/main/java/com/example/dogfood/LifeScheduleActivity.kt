package com.example.dogfood

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.dogfood.databinding.ActivityLifeScheduleBinding
import com.example.dogfood.databinding.DialogLifeScheduleEditBinding
import com.example.dogfood.databinding.ItemDailyScheduleBinding

class LifeScheduleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLifeScheduleBinding
    private val schedules = mutableListOf<DailySchedule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLifeScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        schedules += Prefs.lifeSchedules(this)
        binding.switchLifeEnabled.isChecked = Prefs.lifeEnabled(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddSchedule.setOnClickListener {
            if (schedules.size >= DogFoodProtocol.MAX_DAILY_SCHEDULES) {
                toast("생활 예약은 최대 ${DogFoodProtocol.MAX_DAILY_SCHEDULES}개까지 추가할 수 있습니다.")
            } else {
                showScheduleEditor(null)
            }
        }
        binding.btnSaveLifeSchedule.setOnClickListener { saveAndFinish() }

        renderSchedules()
    }

    private fun renderSchedules() {
        binding.scheduleContainer.removeAllViews()
        binding.txtScheduleCount.text = "${schedules.size} / ${DogFoodProtocol.MAX_DAILY_SCHEDULES}개"

        if (schedules.isEmpty()) {
            binding.txtEmptySchedule.visibility = android.view.View.VISIBLE
        } else {
            binding.txtEmptySchedule.visibility = android.view.View.GONE
        }

        schedules.sortedBy { it.minuteOfDay }.forEach { schedule ->
            val row = ItemDailyScheduleBinding.inflate(LayoutInflater.from(this), binding.scheduleContainer, false)
            row.txtScheduleTime.text = schedule.timeText()
            val pillAName = Prefs.pillAName(this)
            val pillBName = Prefs.pillBName(this)
            row.txtScheduleDetail.text = "사료 ${schedule.foodGram}g  ·  $pillAName ${schedule.pillA}개  ·  $pillBName ${schedule.pillB}개"
            row.btnScheduleEdit.setOnClickListener { showScheduleEditor(schedule) }
            row.btnScheduleDelete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("예약을 삭제할까요?")
                    .setMessage("${schedule.timeText()} 예약을 삭제합니다.")
                    .setPositiveButton("삭제") { _, _ ->
                        schedules.remove(schedule)
                        renderSchedules()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            binding.scheduleContainer.addView(row.root)
        }
    }

    private fun showScheduleEditor(existing: DailySchedule?) {
        val dialogBinding = DialogLifeScheduleEditBinding.inflate(layoutInflater)
        var selectedHour = existing?.hour ?: 8
        var selectedMinute = existing?.minute ?: 0

        fun refreshTime() {
            dialogBinding.btnPickTime.text = "%02d:%02d".format(selectedHour, selectedMinute)
        }

        refreshTime()
        dialogBinding.edtLifeFood.setText((existing?.foodGram ?: Prefs.defaultFoodGram(this)).toString())
        dialogBinding.edtLifePillA.setText((existing?.pillA ?: 0).toString())
        dialogBinding.edtLifePillB.setText((existing?.pillB ?: 0).toString())
        dialogBinding.layoutLifePillA.hint = "${Prefs.pillAName(this)} 개수"
        dialogBinding.layoutLifePillB.hint = "${Prefs.pillBName(this)} 개수"

        dialogBinding.btnPickTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    selectedHour = hour
                    selectedMinute = minute
                    refreshTime()
                },
                selectedHour,
                selectedMinute,
                true,
            ).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "생활 예약 추가" else "생활 예약 수정")
            .setView(dialogBinding.root)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val food = dialogBinding.edtLifeFood.text.toString().trim().toIntOrNull()
                val pillA = dialogBinding.edtLifePillA.text.toString().trim().toIntOrNull()
                val pillB = dialogBinding.edtLifePillB.text.toString().trim().toIntOrNull()

                if (food == null || food !in 1..999) {
                    toast("사료량은 1~999g 범위로 입력해주세요.")
                    return@setOnClickListener
                }
                if (pillA == null || pillA !in 0..7 || pillB == null || pillB !in 0..7) {
                    toast("${Prefs.pillAName(this)}/${Prefs.pillBName(this)}는 각각 0~7개로 입력해주세요.")
                    return@setOnClickListener
                }

                val duplicate = schedules.any {
                    it !== existing && it.hour == selectedHour && it.minute == selectedMinute
                }
                if (duplicate) {
                    toast("같은 시간에 이미 등록된 예약이 있습니다.")
                    return@setOnClickListener
                }

                val updated = DailySchedule(selectedHour, selectedMinute, food, pillA, pillB)
                if (existing == null) {
                    schedules += updated
                } else {
                    val index = schedules.indexOf(existing)
                    if (index >= 0) schedules[index] = updated
                }
                schedules.sortBy { it.minuteOfDay }
                renderSchedules()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveAndFinish() {
        val enabled = binding.switchLifeEnabled.isChecked
        if (enabled && schedules.isEmpty()) {
            toast("생활 모드를 켜려면 예약을 1개 이상 추가해주세요.")
            return
        }

        val totalA = schedules.sumOf { it.pillA }
        val totalB = schedules.sumOf { it.pillB }
        if (enabled && (totalA > 7 || totalB > 7)) {
            AlertDialog.Builder(this)
                .setTitle("약통 용량을 확인해주세요")
                .setMessage(
                    "하루 예약 합계가 약통의 현재 기준 용량 7개를 넘습니다.\n\n" +
                        "${Prefs.pillAName(this)}: ${totalA}개 / ${Prefs.pillBName(this)}: ${totalB}개\n\n" +
                        "중간에 보충하지 않으면 일부 투약이 불가능할 수 있습니다. 그래도 저장할까요?"
                )
                .setPositiveButton("저장") { _, _ -> persist(enabled) }
                .setNegativeButton("취소", null)
                .show()
            return
        }

        persist(enabled)
    }

    private fun persist(enabled: Boolean) {
        Prefs.setLifeSchedules(this, schedules)
        Prefs.setLifeEnabled(this, enabled)
        toast("생활 예약을 저장했습니다. 장치 연결 시 자동 동기화됩니다.")
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
