package com.example.dogfood

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.dogfood.databinding.ActivityRecordBinding
import java.io.File

class RecordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        refresh()

        binding.btnClear.setOnClickListener {
            File(filesDir, "애견급식기.txt").writeText("")
            refresh()
        }
    }

    private fun refresh() {
        val file = File(filesDir, "애견급식기.txt")
        binding.txtRecord.text = if (file.exists() && file.length() > 0) {
            file.readText(Charsets.UTF_8)
        } else {
            "저장된 기록이 없습니다."
        }
    }
}
