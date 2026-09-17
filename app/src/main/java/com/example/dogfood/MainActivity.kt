package com.example.dogfood

import android.Manifest
import android.app.Dialog
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.dogfood.databinding.ActivityMainBinding
import com.example.dogfood.databinding.DialogDeveloperBinding
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetooth: BluetoothController
    private lateinit var tts: TextToSpeech

    private val handler = Handler(Looper.getMainLooper())
    private var dogState = DogState()
    private var plans = mutableListOf<FeedingPlan>()
    private var sequenceRunning = false
    private var foodConsumedTotal = 0
    private var hasReceivedPacket = false

    private var developerDialog: Dialog? = null
    private var developerBinding: DialogDeveloperBinding? = null
    private val developerLog = ArrayDeque<String>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) showBluetoothDevices()
            else toast("블루투스 권한이 필요합니다.")
        }

    private val tick = object : Runnable {
        override fun run() {
            updateClockAndSchedule()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        bluetooth = BluetoothController(
            context = this,
            onPacket = { state ->
                runOnUiThread {
                    hasReceivedPacket = true
                    applyDogState(state)
                }
            },
            onConnectionChanged = { connected, message ->
                runOnUiThread {
                    binding.btnBluetooth.text = if (connected) "장치 변경" else "장치 연결"
                    binding.txtConnection.text = if (connected) "● 연결됨" else "● 연결 안됨"
                    binding.txtConnection.setTextColor(
                        color(if (connected) R.color.accent_green else R.color.text_secondary)
                    )
                    binding.txtConnection.setBackgroundResource(
                        if (connected) R.drawable.bg_chip_green else R.drawable.bg_chip_neutral
                    )
                    if (connected) toast("$message 연결 완료")
                    refreshOverallStatus()
                    refreshDeveloperConnection()
                }
            },
        )

        bindButtons()
        refreshHeader()
        createInitialPlans()
        renderPlans()
        applyDogState(dogState)
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        refreshHeader()
        if (!sequenceRunning) {
            createInitialPlans()
            renderPlans()
        }
    }

    private fun bindButtons() = with(binding) {
        btnBluetooth.setOnClickListener { ensureBluetoothPermissionAndShowDevices() }

        btnWaterUp.setOnClickListener { sendManual(DogFoodProtocol.CMD_WATER_UP) }
        btnWaterDown.setOnClickListener { sendManual(DogFoodProtocol.CMD_WATER_DOWN) }
        btnCoverClose.setOnClickListener { sendManual(DogFoodProtocol.CMD_COVER_CLOSE) }
        btnCoverOpen.setOnClickListener { sendManual(DogFoodProtocol.CMD_COVER_OPEN) }
        btnRefill.setOnClickListener { sendManual(DogFoodProtocol.CMD_REFILL_DONE) }
        btnReset.setOnClickListener { resetSequence(sendReset = true) }
        btnStart.setOnClickListener { startDemoSequence() }

        btnSettings.setOnClickListener {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        btnRecord.setOnClickListener {
            startActivity(Intent(this@MainActivity, RecordActivity::class.java))
        }
        btnDeveloper.setOnClickListener { showDeveloperPanel() }
    }

    private fun refreshHeader() {
        val name = Prefs.dogName(this).trim()
        binding.txtDogName.text = if (name.isBlank()) "반려견 케어 스테이션" else "$name 케어 스테이션"
        binding.txtMode.text = "시연 모드"
    }

    private fun sendManual(command: String) {
        if (bluetooth.isConnected()) bluetooth.send(command)
        else toast("먼저 장치를 블루투스로 연결해주세요.")
    }

    private fun startDemoSequence() {
        if (!bluetooth.isConnected()) {
            toast("시연을 시작하려면 먼저 장치를 연결해주세요.")
            return
        }

        val now = LocalDateTime.now().withSecond(0).withNano(0)

        // 기존 App Inventor 시연 흐름을 유지: 현재 시각 기준 +1분, +4분, +7분
        val offsets = listOf(1L, 4L, 7L)
        plans = (1..3).map { index ->
            FeedingPlan(
                index = index,
                time = now.plusMinutes(offsets[index - 1]),
                foodGram = Prefs.food(this, index),
                pillA = Prefs.pillA(this, index),
                pillB = Prefs.pillB(this, index),
            )
        }.toMutableList()

        sequenceRunning = true
        foodConsumedTotal = 0
        binding.txtFoodConsumed.text = "0"
        styleStartButton(active = true)
        renderPlans()

        bluetooth.send(DogFoodProtocol.CMD_START)
        toast("시연 급식 예약을 시작했습니다.")
    }

    private fun createInitialPlans() {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        plans = (1..3).map { index ->
            FeedingPlan(
                index = index,
                time = now,
                foodGram = Prefs.food(this, index),
                pillA = Prefs.pillA(this, index),
                pillB = Prefs.pillB(this, index),
            )
        }.toMutableList()
    }

    private fun updateClockAndSchedule() {
        val now = LocalDateTime.now()
        binding.txtNow.text = now.format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일  HH:mm:ss"))

        if (!sequenceRunning) return

        plans.forEach { plan ->
            if (!plan.fed && sameMinute(now, plan.time)) {
                executeFeeding(plan)
            }

            val measureAt = plan.measureAt
            if (plan.fed && !plan.measured && measureAt != null && sameMinute(now, measureAt)) {
                measureFood(plan)
            }
        }
    }

    private fun executeFeeding(plan: FeedingPlan) {
        plan.fed = true
        val name = Prefs.dogName(this)
        speak(if (name.isBlank()) "밥먹자." else "${name}야. 밥먹자.")

        if (bluetooth.isConnected()) {
            bluetooth.send(
                DogFoodProtocol.feedingCommand(
                    foodGram = plan.foodGram,
                    pillA = plan.pillA,
                    pillB = plan.pillB,
                )
            )
        }

        // 기존 블록의 측정 지연 유지: 1회차 +2분, 이후 +1분
        val delay = if (plan.index == 1) 2L else 1L
        plan.measureAt = plan.time.plusMinutes(delay)
        renderPlans()
    }

    private fun measureFood(plan: FeedingPlan) {
        plan.measured = true
        speak("사료 추정 섭취량을 업데이트 합니다.")

        val eaten = max(0, plan.foodGram - dogState.foodWeight)
        foodConsumedTotal += eaten
        binding.txtFoodConsumed.text = foodConsumedTotal.toString()
        renderPlans()

        if (plan.index == 3) finishSequence()
    }

    private fun finishSequence() {
        appendRecord()
        resetSequence(sendReset = true)
        toast("시연 기록을 저장했습니다.")
    }

    private fun resetSequence(sendReset: Boolean) {
        sequenceRunning = false
        plans.forEach {
            it.fed = false
            it.measured = false
            it.measureAt = null
        }
        styleStartButton(active = false)
        if (sendReset && bluetooth.isConnected()) bluetooth.send(DogFoodProtocol.CMD_RESET)
        renderPlans()
    }

    private fun appendRecord() {
        val now = LocalDateTime.now()
        val line = buildString {
            append(now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")))
            append(" | 물추정섭취량=").append(dogState.waterConsumed).append("g")
            append(" | 사료추정섭취량=").append(foodConsumedTotal).append("g")
            append(" | 알약A배출추정=").append(dogState.pillAConsumed).append("ea")
            append(" | 알약B배출추정=").append(dogState.pillBConsumed).append("ea")
            append('\n')
        }
        File(filesDir, "애견급식기.txt").appendText(line, Charsets.UTF_8)
    }

    private fun sameMinute(a: LocalDateTime, b: LocalDateTime): Boolean =
        a.year == b.year &&
            a.monthValue == b.monthValue &&
            a.dayOfMonth == b.dayOfMonth &&
            a.hour == b.hour &&
            a.minute == b.minute

    private fun applyDogState(state: DogState) = with(binding) {
        dogState = state

        txtWaterSet.text = "${state.waterSet} g"
        txtWaterWeight.text = state.waterWeight.toString()
        txtFoodWeight.text = state.foodWeight.toString()
        txtPillA.text = state.pillACount.toString()
        txtPillB.text = state.pillBCount.toString()

        if (!hasReceivedPacket) {
            txtFoodStatus.text = "연결 후 확인"
            txtWaterStatus.text = "연결 후 확인"
            txtPillStatus.text = "연결 후 확인"
            txtFoodStatus.setTextColor(color(R.color.text_secondary))
            txtWaterStatus.setTextColor(color(R.color.text_secondary))
            txtPillStatus.setTextColor(color(R.color.text_secondary))
        } else {
            txtFoodStatus.text = if (state.foodLow == 0) "● 사료 부족" else "● 사료 충분"
            txtWaterStatus.text = if (state.waterLow == 0) "● 물 부족" else "● 물 충분"
            txtPillStatus.text = if (state.pillACount == 0 || state.pillBCount == 0) "● 보충 필요" else "● 투약 준비됨"

            txtFoodStatus.setTextColor(color(if (state.foodLow == 0) R.color.danger else R.color.accent_green))
            txtWaterStatus.setTextColor(color(if (state.waterLow == 0) R.color.danger else R.color.accent_green))
            txtPillStatus.setTextColor(color(if (state.pillACount == 0 || state.pillBCount == 0) R.color.danger else R.color.accent_green))
        }

        txtWaterConsumed.text = state.waterConsumed.toString()
        txtPillAConsumed.text = state.pillAConsumed.toString()
        txtPillBConsumed.text = state.pillBConsumed.toString()

        refreshDeveloperPanel(state)
        styleStartButton(active = state.startMode != 0 || sequenceRunning)
        styleCoverButtons(state.coverMode)
        refreshOverallStatus()
    }

    private fun refreshOverallStatus() {
        if (!bluetooth.isConnected()) {
            binding.txtOverallStatus.text = "장치를 연결해주세요"
            return
        }
        if (!hasReceivedPacket) {
            binding.txtOverallStatus.text = "센서 데이터 수신 대기 중"
            return
        }

        val warnings = buildList {
            if (dogState.waterLow == 0) add("물")
            if (dogState.foodLow == 0) add("사료")
            if (dogState.pillACount == 0 || dogState.pillBCount == 0) add("약")
        }

        binding.txtOverallStatus.text = if (warnings.isEmpty()) {
            "모든 장치 정상"
        } else {
            "${warnings.joinToString(" · ")} 보충이 필요합니다"
        }
    }

    private fun styleStartButton(active: Boolean) {
        binding.btnStart.backgroundTintList = ColorStateList.valueOf(
            color(if (active) R.color.danger else R.color.primary)
        )
        binding.btnStart.text = if (active) "시연 진행 중 · 초기화로 중지" else "시연 급식 시작"
    }

    private fun styleCoverButtons(coverMode: Int) {
        val selected = color(R.color.primary_soft)
        val normal = color(R.color.surface)
        binding.btnCoverOpen.backgroundTintList = ColorStateList.valueOf(if (coverMode == 0) selected else normal)
        binding.btnCoverClose.backgroundTintList = ColorStateList.valueOf(if (coverMode == 1) selected else normal)
    }

    private fun renderPlans() {
        val dateFmt = DateTimeFormatter.ofPattern("MM/dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val rows = listOf(binding.txtPlan1, binding.txtPlan2, binding.txtPlan3)

        plans.forEachIndexed { i, plan ->
            val mark = when {
                plan.measured -> "측정 완료"
                plan.fed -> "급식 완료"
                sequenceRunning -> "대기"
                else -> "설정값"
            }
            rows[i].text = buildString {
                append("${i + 1}회 · ${plan.time.format(dateFmt)} ${plan.time.format(timeFmt)}")
                append("  ·  $mark")
                append("\n사료 ${plan.foodGram}g  ·  약 A ${plan.pillA}개  ·  약 B ${plan.pillB}개")
            }
        }

        updateNextFeedingCard()
    }

    private fun updateNextFeedingCard() {
        if (!sequenceRunning) {
            binding.txtNextFeed.text = "시연 급식 대기 중"
            binding.txtNextFeedDetail.text = "시작하면 현재 시각 기준 +1분, +4분, +7분으로 3회 예약됩니다."
            return
        }

        val next = plans.firstOrNull { !it.fed }
        if (next == null) {
            binding.txtNextFeed.text = "모든 급식이 실행되었습니다"
            binding.txtNextFeedDetail.text = "센서 측정 및 기록 저장을 진행하고 있습니다."
        } else {
            binding.txtNextFeed.text = "${next.time.format(DateTimeFormatter.ofPattern("HH:mm"))} · ${next.index}회차"
            binding.txtNextFeedDetail.text = "사료 ${next.foodGram}g  ·  약 A ${next.pillA}개  ·  약 B ${next.pillB}개"
        }
    }

    private fun showDeveloperPanel() {
        if (developerDialog?.isShowing == true) return

        val dialogBinding = DialogDeveloperBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            developerBinding = null
            developerDialog = null
        }
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )

        developerDialog = dialog
        developerBinding = dialogBinding

        dialogBinding.btnDevClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnDevBluetooth.setOnClickListener { ensureBluetoothPermissionAndShowDevices() }

        dialogBinding.btnDevManualMode.setOnClickListener {
            if (!requireDeveloperConnection()) return@setOnClickListener
            if (dogState.startMode == 1) {
                sendDeveloper(DogFoodProtocol.CMD_START, "자동 모드 OFF 요청")
            } else {
                toast("이미 수동 모드입니다.")
                appendDeveloperLog("수동 모드 확인")
            }
        }

        dialogBinding.btnDevWaterRun.setOnClickListener {
            runTimedMotor(
                onCommand = DogFoodProtocol.CMD_WATER_MOTOR_ON,
                offCommand = DogFoodProtocol.CMD_WATER_MOTOR_OFF,
                durationMs = 1500L,
                label = "M1 물 펌프",
            )
        }
        dialogBinding.btnDevWaterStop.setOnClickListener {
            sendDeveloper(DogFoodProtocol.CMD_WATER_MOTOR_OFF, "M1 물 펌프 정지")
        }

        dialogBinding.btnDevFoodRun.setOnClickListener {
            runTimedMotor(
                onCommand = DogFoodProtocol.CMD_FOOD_MOTOR_ON,
                offCommand = DogFoodProtocol.CMD_FOOD_MOTOR_OFF,
                durationMs = 2000L,
                label = "M2 사료 모터",
            )
        }
        dialogBinding.btnDevFoodStop.setOnClickListener {
            sendDeveloper(DogFoodProtocol.CMD_FOOD_MOTOR_OFF, "M2 사료 모터 정지")
        }

        dialogBinding.btnDevCoverOpen.setOnClickListener {
            sendDeveloper(DogFoodProtocol.CMD_COVER_OPEN, "M3 덮개 열기")
        }
        dialogBinding.btnDevCoverClose.setOnClickListener {
            sendDeveloper(DogFoodProtocol.CMD_COVER_CLOSE, "M3 덮개 닫기")
        }
        dialogBinding.btnDevCoverStop.setOnClickListener {
            sendDeveloper(DogFoodProtocol.CMD_COVER_STOP, "M3 덮개 정지")
        }

        dialogBinding.btnDevPillA.setOnClickListener {
            runStepperFromInput(DogFoodProtocol.CMD_PILL_A_STEP, "M4 약통 A")
        }
        dialogBinding.btnDevPillB.setOnClickListener {
            runStepperFromInput(DogFoodProtocol.CMD_PILL_B_STEP, "M5 약통 B")
        }

        dialogBinding.btnDevStopAll.setOnClickListener {
            if (!requireDeveloperConnection()) return@setOnClickListener
            bluetooth.send(DogFoodProtocol.CMD_FOOD_MOTOR_OFF)
            bluetooth.send(DogFoodProtocol.CMD_WATER_MOTOR_OFF)
            bluetooth.send(DogFoodProtocol.CMD_COVER_STOP)
            appendDeveloperLog("전체 DC 모터 정지 · b / d / g")
        }

        refreshDeveloperConnection()
        refreshDeveloperPanel(dogState)
        appendDeveloperLog("개발자 도구 열림")
    }

    private fun refreshDeveloperConnection() {
        val dev = developerBinding ?: return
        val connected = bluetooth.isConnected()
        dev.txtDevConnection.text = if (connected) "● 연결됨" else "● 연결 안됨"
        dev.txtDevConnection.setTextColor(
            color(if (connected) R.color.accent_green else R.color.text_secondary)
        )
        dev.btnDevBluetooth.text = if (connected) "장치 변경" else "장치 연결"
    }

    private fun refreshDeveloperPanel(state: DogState) {
        val dev = developerBinding ?: return

        if (!hasReceivedPacket) {
            dev.txtDevWaterWeight.text = "물 로드셀  ·  수신 대기"
            dev.txtDevFoodWeight.text = "사료 로드셀  ·  수신 대기"
            dev.txtDevWaterSensor.text = "물통 감지 센서  ·  수신 대기"
            dev.txtDevFoodSensor.text = "사료 감지 센서  ·  수신 대기"
            dev.txtDevCover.text = "덮개 상태  ·  수신 대기"
            dev.txtDevPills.text = "약통 A --개  ·  약통 B --개"
            dev.txtDevAutoMode.text = "제어 모드  ·  수신 대기"
            return
        }

        dev.txtDevWaterWeight.text = "물 로드셀  ·  ${state.waterWeight} g"
        dev.txtDevFoodWeight.text = "사료 로드셀  ·  ${state.foodWeight} g"
        dev.txtDevWaterSensor.text =
            if (state.waterLow == 0) "물통 감지 센서  ·  물 부족" else "물통 감지 센서  ·  정상"
        dev.txtDevFoodSensor.text =
            if (state.foodLow == 0) "사료 감지 센서  ·  사료 부족" else "사료 감지 센서  ·  정상"
        dev.txtDevCover.text =
            if (state.coverMode == 0) "덮개 상태  ·  열림" else "덮개 상태  ·  닫힘"
        dev.txtDevPills.text = "약통 A ${state.pillACount}개  ·  약통 B ${state.pillBCount}개"
        dev.txtDevAutoMode.text =
            if (state.startMode == 1) "제어 모드  ·  자동" else "제어 모드  ·  수동"

        dev.txtDevModeWarning.text = if (state.startMode == 1) {
            "자동 모드 ON · 수동 테스트 전 아래 버튼으로 자동 모드를 꺼주세요."
        } else {
            "수동 모드 · 모터 단독 테스트 가능"
        }
        dev.txtDevModeWarning.setTextColor(
            color(if (state.startMode == 1) R.color.warning else R.color.accent_green)
        )
    }

    private fun requireDeveloperConnection(): Boolean {
        if (bluetooth.isConnected()) return true
        toast("먼저 장치를 블루투스로 연결해주세요.")
        appendDeveloperLog("전송 실패 · 블루투스 연결 필요")
        return false
    }

    private fun sendDeveloper(command: String, label: String) {
        if (!requireDeveloperConnection()) return
        bluetooth.send(command)
        appendDeveloperLog("$label  →  '$command'")
    }

    private fun runTimedMotor(
        onCommand: String,
        offCommand: String,
        durationMs: Long,
        label: String,
    ) {
        if (!requireDeveloperConnection()) return
        bluetooth.send(onCommand)
        appendDeveloperLog("$label ON  →  '$onCommand'")
        handler.postDelayed({
            if (bluetooth.isConnected()) {
                bluetooth.send(offCommand)
                appendDeveloperLog("$label 자동 정지  →  '$offCommand'")
            }
        }, durationMs)
    }

    private fun runStepperFromInput(command: String, label: String) {
        if (!requireDeveloperConnection()) return
        val angle = developerBinding?.edtStepAngle?.text?.toString()?.trim()?.toIntOrNull()
        if (angle == null || angle !in 45..360 || angle % 45 != 0) {
            toast("각도는 45~360 사이의 45도 배수로 입력해주세요.")
            return
        }

        val repeats = angle / 45
        appendDeveloperLog("$label ${angle}° 시작 · 45° × $repeats")

        // ATmega의 45° 함수가 약 1초 동안 블로킹되므로 명령이 덮어쓰이지 않게 간격을 둡니다.
        repeat(repeats) { index ->
            handler.postDelayed({
                if (bluetooth.isConnected()) {
                    bluetooth.send(command)
                    appendDeveloperLog("$label ${index + 1}/$repeats  →  '$command'")
                }
            }, index * 1300L)
        }
    }

    private fun appendDeveloperLog(message: String) {
        val stamp = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        developerLog.addFirst("[$stamp] $message")
        while (developerLog.size > 8) developerLog.removeLast()
        developerBinding?.txtDevLog?.text = developerLog.joinToString("\n")
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dog-food")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.KOREAN
    }

    private fun requiredBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun ensureBluetoothPermissionAndShowDevices() {
        val missing = requiredBluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) showBluetoothDevices()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDevices() {
        if (!bluetooth.isBluetoothAvailable()) {
            toast("이 기기는 블루투스를 지원하지 않습니다.")
            return
        }
        if (!bluetooth.isBluetoothEnabled()) {
            toast("휴대폰 설정에서 블루투스를 켜주세요.")
            return
        }

        val devices = bluetooth.bondedDevices()
        if (devices.isEmpty()) {
            toast("먼저 휴대폰 설정에서 HC-05/HC-06 등을 페어링해주세요.")
            return
        }

        val names = devices.map { "${it.name ?: "이름 없음"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("연결할 장치 선택")
            .setItems(names) { _, which -> bluetooth.connect(devices[which]) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        developerDialog?.dismiss()
        bluetooth.shutdown()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
