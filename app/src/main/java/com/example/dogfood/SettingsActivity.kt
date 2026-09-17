package com.example.dogfood

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dogfood.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.edtDogName.setText(Prefs.dogName(this))
        loadPlan(1)
        loadPlan(2)
        loadPlan(3)

        binding.btnSave.setOnClickListener {
            Prefs.setDogName(this, binding.edtDogName.text.toString().trim())
            savePlan(1)
            savePlan(2)
            savePlan(3)
            Toast.makeText(this, "설정 저장 완료", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadPlan(index: Int) {
        foodField(index).setText(Prefs.food(this, index).toString())
        pillAField(index).setText(Prefs.pillA(this, index).toString())
        pillBField(index).setText(Prefs.pillB(this, index).toString())
    }

    private fun savePlan(index: Int) {
        Prefs.setPlan(
            this,
            index,
            foodField(index).text.toString().toIntOrNull() ?: 0,
            pillAField(index).text.toString().toIntOrNull() ?: 0,
            pillBField(index).text.toString().toIntOrNull() ?: 0,
        )
    }

    private fun foodField(index: Int) = when (index) {
        1 -> binding.edtFood1
        2 -> binding.edtFood2
        else -> binding.edtFood3
    }

    private fun pillAField(index: Int) = when (index) {
        1 -> binding.edtPillA1
        2 -> binding.edtPillA2
        else -> binding.edtPillA3
    }

    private fun pillBField(index: Int) = when (index) {
        1 -> binding.edtPillB1
        2 -> binding.edtPillB2
        else -> binding.edtPillB3
    }
}
