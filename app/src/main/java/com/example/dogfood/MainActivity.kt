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
    private var lastLifeScheduleSignature = ""
    private var simulationEnabled = false
    private var lastSimLifeMinuteKey = ""
    private var simEventSequence = 1

    private var developerDialog: Dialog? = null
    private var developerBinding: DialogDeveloperBinding? = null
    private val developerLog = ArrayDeque<String>()
    private val activeDeveloperStopCommands = mutableSetOf<String>()

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
                    if (!simulationEnabled) {
                        hasReceivedPacket = true
                        applyDogState(state)
                    }
                }
            },
            onDeviceEvent = { event ->
                runOnUiThread { handleDeviceFeedEvent(event) }
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
                    if (connected) {
                        if (simulationEnabled) disableSimulation(showToast = false)
                        toast("$message 연결 완료")
                        lastLifeScheduleSignature = ""
                        // 연결이 끊긴 동안 실행된 생활 급식 이벤트를 먼저 요청한 뒤 시간/예약을 동기화한다.
                        bluetooth.send(DogFoodProtocol.CMD_EVENT_REPLAY)
                        handler.postDelayed({ syncLifeSchedulesToDevice(showToast = false) }, 1100L)
                    }
                    refreshOverallStatus()
                    refreshDeveloperConnection()
                    refreshLifeScheduleCard()
                }
            },
        )

        bindButtons()
        refreshHeader()
        refreshLifeScheduleCard()
        createInitialPlans()
        renderPlans()
        applyDogState(dogState)
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        refreshHeader()
        refreshLifeScheduleCard()
        if (!sequenceRunning) {
            createInitialPlans()
            renderPlans()
        }
        if (::bluetooth.isInitialized && bluetooth.isConnected()) {
            val signature = lifeScheduleSignature()
            if (signature != lastLifeScheduleSignature) {
                handler.postDelayed({ syncLifeSchedulesToDevice(showToast = false) }, 350L)
            }
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
        btnStart.setOnClickListener {
            if (sequenceRunning) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("시연을 중지할까요?")
                    .setMessage("진행 중인 시연 예약을 취소하고 장치를 초기화합니다.")
                    .setPositiveButton("중지") { _, _ -> resetSequence(sendReset = true) }
                    .setNegativeButton("계속", null)
                    .show()
            } else {
                showDemoScheduleDialog()
            }
        }

        btnLifeSchedule.setOnClickListener {
            startActivity(Intent(this@MainActivity, LifeScheduleActivity::class.java))
        }
        btnLifeSync.setOnClickListener {
            syncLifeSchedulesToDevice(showToast = true)
        }
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
        binding.txtMode.text = if (Prefs.lifeEnabled(this)) "생활 모드 ON" else "통합 모드"

        binding.txtPillALabel.text = "${Prefs.pillAName(this)} 잔여"
        binding.txtPillBLabel.text = "${Prefs.pillBName(this)} 잔여"
        binding.txtPillAConsumedLabel.text = "${Prefs.pillAName(this)} 개"
        binding.txtPillBConsumedLabel.text = "${Prefs.pillBName(this)} 개"
    }

    private fun sendManual(command: String) {
        if (isDeviceReady()) sendCommand(command)
        else toast("먼저 장치를 블루투스로 연결하거나 개발자 시뮬레이션을 켜주세요.")
    }

    private fun isDeviceReady(): Boolean = bluetooth.isConnected() || simulationEnabled

    private fun sendCommand(command: String) {
        if (simulationEnabled) simulateCommand(command) else bluetooth.send(command)
    }

    private fun showDemoScheduleDialog() {
        val dialogBinding = com.example.dogfood.databinding.DialogDemoScheduleBinding.inflate(layoutInflater)
        val fieldsOffset = listOf(dialogBinding.edtOffset1, dialogBinding.edtOffset2, dialogBinding.edtOffset3)
        val fieldsFood = listOf(dialogBinding.edtDemoFood1, dialogBinding.edtDemoFood2, dialogBinding.edtDemoFood3)
        val fieldsA = listOf(dialogBinding.edtDemoPillA1, dialogBinding.edtDemoPillA2, dialogBinding.edtDemoPillA3)
        val fieldsB = listOf(dialogBinding.edtDemoPillB1, dialogBinding.edtDemoPillB2, dialogBinding.edtDemoPillB3)
        val pillAName = Prefs.pillAName(this)
        val pillBName = Prefs.pillBName(this)
        fieldsA.forEach { it.hint = pillAName }
        fieldsB.forEach { it.hint = pillBName }

        repeat(3) { i ->
            val index = i + 1
            fieldsOffset[i].setText(Prefs.demoOffset(this, index).toString())
            fieldsFood[i].setText(Prefs.food(this, index).toString())
            fieldsA[i].setText(Prefs.pillA(this, index).toString())
            fieldsB[i].setText(Prefs.pillB(this, index).toString())
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnDemoCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnDemoStart.setOnClickListener {
            if (!isDeviceReady()) {
                toast("예약을 시작하려면 장치를 연결하거나 개발자 시뮬레이션을 켜주세요.")
                return@setOnClickListener
            }

            val offsets = fieldsOffset.map { it.text.toString().trim().toIntOrNull() }
            val foods = fieldsFood.map { it.text.toString().trim().toIntOrNull() }
            val pillsA = fieldsA.map { it.text.toString().trim().toIntOrNull() }
            val pillsB = fieldsB.map { it.text.toString().trim().toIntOrNull() }

            if (offsets.any { it == null } || foods.any { it == null } || pillsA.any { it == null } || pillsB.any { it == null }) {
                toast("모든 시연 예약 값을 숫자로 입력해주세요.")
                return@setOnClickListener
            }

            val safeOffsets = offsets.filterNotNull()
            val safeFoods = foods.filterNotNull()
            val safeA = pillsA.filterNotNull()
            val safeB = pillsB.filterNotNull()

            if (safeOffsets.any { it !in 1..180 }) {
                toast("시연 시간은 1~180분 후로 설정해주세요.")
                return@setOnClickListener
            }
            if (safeOffsets[1] - safeOffsets[0] < 2 || safeOffsets[2] - safeOffsets[1] < 2) {
                toast("각 급식 사이를 최소 2분 이상 띄워주세요.")
                return@setOnClickListener
            }
            if (safeFoods.any { it !in 1..999 }) {
                toast("사료량은 1~999g 범위로 입력해주세요.")
                return@setOnClickListener
            }
            if (safeA.any { it !in 0..7 } || safeB.any { it !in 0..7 }) {
                toast("약 개수는 각 회차당 0~7개로 입력해주세요.")
                return@setOnClickListener
            }

            val availableA = if (hasReceivedPacket) dogState.pillACount else 7
            val availableB = if (hasReceivedPacket) dogState.pillBCount else 7
            if (safeA.sum() > availableA) {
                toast("${Prefs.pillAName(this)} 예약 총 ${safeA.sum()}개 · 현재 사용 가능 ${availableA}개입니다.")
                return@setOnClickListener
            }
            if (safeB.sum() > availableB) {
                toast("${Prefs.pillBName(this)} 예약 총 ${safeB.sum()}개 · 현재 사용 가능 ${availableB}개입니다.")
                return@setOnClickListener
            }

            repeat(3) { i ->
                val index = i + 1
                Prefs.setDemoOffset(this, index, safeOffsets[i])
                Prefs.setPlan(this, index, safeFoods[i], safeA[i], safeB[i])
            }

            dialog.dismiss()
            startDemoSequence(safeOffsets, safeFoods, safeA, safeB)
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun startDemoSequence(
        offsets: List<Int>,
        foods: List<Int>,
        pillsA: List<Int>,
        pillsB: List<Int>,
    ) {
        if (!isDeviceReady()) {
            toast("시연을 시작하려면 장치를 연결하거나 개발자 시뮬레이션을 켜주세요.")
            return
        }

        val now = LocalDateTime.now().withSecond(0).withNano(0)
        plans = (1..3).map { index ->
            FeedingPlan(
                index = index,
                time = now.plusMinutes(offsets[index - 1].toLong()),
                foodGram = foods[index - 1],
                pillA = pillsA[index - 1],
                pillB = pillsB[index - 1],
            )
        }.toMutableList()

        sequenceRunning = true
        foodConsumedTotal = 0
        binding.txtFoodConsumed.text = "0"
        styleStartButton(active = true)
        renderPlans()

        // 시연 중 생활 예약이 같은 시각에 겹치지 않도록 예약 실행만 잠시 중지합니다.
        sendCommand(DogFoodProtocol.CMD_DAILY_PAUSE)
        // 'k'는 토글 명령이므로 이미 자동 모드라면 다시 보내지 않는다.
        if (dogState.startMode == 0) {
            handler.postDelayed({
                if (isDeviceReady()) sendCommand(DogFoodProtocol.CMD_START)
            }, 250L)
        }
        toast("시연 예약 3건을 시작했습니다.")
    }

    private fun createInitialPlans() {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        plans = (1..3).map { index ->
            FeedingPlan(
                index = index,
                time = now.plusMinutes(Prefs.demoOffset(this, index).toLong()),
                foodGram = Prefs.food(this, index),
                pillA = Prefs.pillA(this, index),
                pillB = Prefs.pillB(this, index),
            )
        }.toMutableList()
    }

    private fun updateClockAndSchedule() {
        val now = LocalDateTime.now()
        binding.txtNow.text = now.format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일  HH:mm:ss"))

        if (simulationEnabled && Prefs.lifeEnabled(this)) {
            simulateLifeScheduleIfNeeded(now)
        }

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

        if (isDeviceReady()) {
            sendCommand(
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
        if (sendReset && isDeviceReady()) {
            sendCommand(DogFoodProtocol.CMD_RESET)
            // j 명령은 생활 예약도 중지하므로, 사용자가 생활 모드를 켜둔 경우 다시 동기화합니다.
            if (Prefs.lifeEnabled(this)) {
                handler.postDelayed({ syncLifeSchedulesToDevice(showToast = false) }, 800L)
            }
        }
        renderPlans()
    }

    private fun appendRecord() {
        RecordStore.appendDemoRecord(
            context = this,
            waterEstimatedGram = dogState.waterConsumed,
            foodEstimatedGram = foodConsumedTotal,
            pillAEstimatedCount = dogState.pillAConsumed,
            pillBEstimatedCount = dogState.pillBConsumed,
            pillAName = Prefs.pillAName(this),
            pillBName = Prefs.pillBName(this),
        )
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
        styleStartButton(active = sequenceRunning)
        styleCoverButtons(state.coverMode)
        refreshOverallStatus()
    }

    private fun refreshOverallStatus() {
        if (!bluetooth.isConnected() && !simulationEnabled) {
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
        binding.btnStart.text = if (active) "시연 진행 중 · 눌러서 중지" else "시연 급식 설정 및 시작"
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
                append("\n사료 ${plan.foodGram}g  ·  ${Prefs.pillAName(this@MainActivity)} ${plan.pillA}개  ·  ${Prefs.pillBName(this@MainActivity)} ${plan.pillB}개")
            }
        }

        updateNextFeedingCard()
    }

    private fun updateNextFeedingCard() {
        if (!sequenceRunning) {
            val daily = Prefs.lifeSchedules(this)
            if (Prefs.lifeEnabled(this) && daily.isNotEmpty()) {
                val now = java.time.LocalTime.now()
                val currentMinute = now.hour * 60 + now.minute
                val nextToday = daily.firstOrNull { it.minuteOfDay > currentMinute }
                val next = nextToday ?: daily.first()
                val dayText = if (nextToday != null) "오늘" else "내일"
                binding.txtNextFeed.text = "생활 예약 · $dayText ${next.timeText()}"
                binding.txtNextFeedDetail.text = "사료 ${next.foodGram}g  ·  ${Prefs.pillAName(this)} ${next.pillA}개  ·  ${Prefs.pillBName(this)} ${next.pillB}개"
            } else {
                binding.txtNextFeed.text = "시연 급식 대기 중"
                binding.txtNextFeedDetail.text = "시연 급식 시작을 눌러 시간 간격·사료량·투약 개수를 설정하세요."
            }
            return
        }

        val next = plans.firstOrNull { !it.fed }
        if (next == null) {
            binding.txtNextFeed.text = "모든 급식이 실행되었습니다"
            binding.txtNextFeedDetail.text = "센서 측정 및 기록 저장을 진행하고 있습니다."
        } else {
            binding.txtNextFeed.text = "${next.time.format(DateTimeFormatter.ofPattern("HH:mm"))} · ${next.index}회차"
            binding.txtNextFeedDetail.text = "사료 ${next.foodGram}g  ·  ${Prefs.pillAName(this)} ${next.pillA}개  ·  ${Prefs.pillBName(this)} ${next.pillB}개"
        }
    }

    private fun refreshLifeScheduleCard() {
        val schedules = Prefs.lifeSchedules(this)
        val enabled = Prefs.lifeEnabled(this)
        val lastSync = Prefs.lastDeviceSync(this)

        binding.txtLifeStatus.text = if (enabled) "생활 모드 ON" else "생활 모드 OFF"
        binding.txtLifeBadge.text = if (enabled) "ON" else "OFF"
        binding.txtLifeBadge.setBackgroundResource(
            if (enabled) R.drawable.bg_chip_green else R.drawable.bg_chip_neutral
        )
        binding.txtLifeBadge.setTextColor(
            color(if (enabled) R.color.accent_green else R.color.text_secondary)
        )

        binding.txtLifeSummary.text = when {
            schedules.isEmpty() -> "등록된 예약이 없습니다. 예약 관리에서 매일 반복할 시간을 추가해주세요."
            enabled -> "매일 ${schedules.size}회 자동 급식 · ${schedules.joinToString(" · ") { it.timeText() }}"
            else -> "예약 ${schedules.size}개 저장됨 · 현재는 실행 중지 상태"
        }

        binding.txtLifeSync.text = when {
            simulationEnabled -> if (lastSync.isBlank()) "시뮬레이션 모드 · 예약 동작을 장치 없이 확인할 수 있습니다." else "마지막 적용 · $lastSync"
            !bluetooth.isConnected() -> "장치 연결 시 휴대폰 현재 시간과 저장된 예약을 자동 동기화합니다."
            lastSync.isBlank() -> "장치 연결됨 · 예약 동기화 준비 중"
            else -> "마지막 장치 전송 · $lastSync"
        }

        updateNextFeedingCard()
    }

    private fun lifeScheduleSignature(): String {
        val schedules = Prefs.lifeSchedules(this)
        return buildString {
            append(Prefs.lifeEnabled(this@MainActivity))
            schedules.forEach {
                append('|').append(it.hour).append(':').append(it.minute)
                    .append(',').append(it.foodGram)
                    .append(',').append(it.pillA)
                    .append(',').append(it.pillB)
            }
        }
    }

    private fun syncLifeSchedulesToDevice(showToast: Boolean) {
        val schedules = Prefs.lifeSchedules(this).take(DogFoodProtocol.MAX_DAILY_SCHEDULES)

        if (simulationEnabled) {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")) + " · 시뮬레이션"
            Prefs.setLastDeviceSync(this, stamp)
            lastLifeScheduleSignature = lifeScheduleSignature()
            refreshLifeScheduleCard()
            if (showToast) toast("시뮬레이션 장치에 현재 시간과 예약 ${schedules.size}개를 적용했습니다.")
            return
        }

        if (!bluetooth.isConnected()) {
            if (showToast) toast("먼저 장치를 블루투스로 연결해주세요.")
            return
        }

        val enabled = Prefs.lifeEnabled(this)
        if (enabled && schedules.isEmpty()) {
            if (showToast) toast("생활 모드 예약을 1개 이상 추가해주세요.")
            return
        }

        val commands = buildList {
            add(DogFoodProtocol.clockCommand())
            add(DogFoodProtocol.CMD_DAILY_CLEAR)
            schedules.forEach { add(DogFoodProtocol.dailyScheduleCommand(it)) }
            add(if (enabled) DogFoodProtocol.CMD_DAILY_ENABLE else DogFoodProtocol.CMD_DAILY_PAUSE)
        }

        commands.forEachIndexed { index, command ->
            handler.postDelayed({
                if (!bluetooth.isConnected()) return@postDelayed
                bluetooth.send(command)

                if (index == commands.lastIndex) {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss"))
                    Prefs.setLastDeviceSync(this, stamp)
                    lastLifeScheduleSignature = lifeScheduleSignature()
                    refreshLifeScheduleCard()
                    if (showToast) {
                        toast(
                            if (enabled) "현재 시간과 생활 예약 ${schedules.size}개를 장치로 전송했습니다."
                            else "현재 시간을 맞추고 생활 예약 실행을 중지했습니다."
                        )
                    }
                }
            }, index * 250L)
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
            stopActiveDeveloperMotors("개발자 도구 닫힘")
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
        dialogBinding.btnDevSimulation.setOnClickListener {
            if (bluetooth.isConnected()) {
                toast("실제 장치 연결 중에는 시뮬레이션을 켤 수 없습니다.")
            } else if (simulationEnabled) {
                disableSimulation(showToast = true)
            } else {
                enableSimulation()
            }
        }

        dialogBinding.btnDevQuickTest.setOnClickListener {
            runQuickSimulationTest()
        }

        dialogBinding.btnDevEmergencyStop.setOnClickListener {
            emergencyStopDeveloper("사용자 긴급 정지")
        }

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
            stopDeveloperMotor(DogFoodProtocol.CMD_WATER_MOTOR_OFF, "M1 물 펌프 정지")
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
            stopDeveloperMotor(DogFoodProtocol.CMD_FOOD_MOTOR_OFF, "M2 사료 모터 정지")
        }

        dialogBinding.btnDevCoverOpen.setOnClickListener {
            runTimedMotor(
                onCommand = DogFoodProtocol.CMD_COVER_OPEN,
                offCommand = DogFoodProtocol.CMD_COVER_STOP,
                durationMs = 5000L,
                label = "M3 덮개 열기",
            )
        }
        dialogBinding.btnDevCoverClose.setOnClickListener {
            runTimedMotor(
                onCommand = DogFoodProtocol.CMD_COVER_CLOSE,
                offCommand = DogFoodProtocol.CMD_COVER_STOP,
                durationMs = 5000L,
                label = "M3 덮개 닫기",
            )
        }
        dialogBinding.btnDevCoverStop.setOnClickListener {
            stopDeveloperMotor(DogFoodProtocol.CMD_COVER_STOP, "M3 덮개 정지")
        }

        dialogBinding.btnDevPillA.setOnClickListener {
            runStepperFromInput(DogFoodProtocol.CMD_PILL_A_STEP, "M4 약통 A (${Prefs.pillAName(this)})")
        }
        dialogBinding.btnDevPillB.setOnClickListener {
            runStepperFromInput(DogFoodProtocol.CMD_PILL_B_STEP, "M5 약통 B (${Prefs.pillBName(this)})")
        }

        dialogBinding.btnDevStopAll.setOnClickListener {
            emergencyStopDeveloper("전체 DC 모터 정지")
        }

        refreshDeveloperConnection()
        refreshDeveloperPanel(dogState)
        appendDeveloperLog("개발자 도구 열림")
    }

    private fun refreshDeveloperConnection() {
        val dev = developerBinding ?: return
        val connected = bluetooth.isConnected()
        dev.txtDevConnection.text = when {
            simulationEnabled -> "● 시뮬레이션"
            connected -> "● 연결됨"
            else -> "● 연결 안됨"
        }
        dev.txtDevConnection.setTextColor(
            color(if (connected || simulationEnabled) R.color.accent_green else R.color.text_secondary)
        )
        dev.btnDevBluetooth.text = if (connected) "장치 변경" else "장치 연결"
        dev.btnDevSimulation.text = if (simulationEnabled) "시뮬레이션 종료" else "장치 없이 시뮬레이션"
    }

    private fun refreshDeveloperPanel(state: DogState) {
        val dev = developerBinding ?: return

        if (!hasReceivedPacket) {
            dev.txtDevWaterWeight.text = "물 로드셀  ·  수신 대기"
            dev.txtDevFoodWeight.text = "사료 로드셀  ·  수신 대기"
            dev.txtDevWaterSensor.text = "물통 감지 센서  ·  수신 대기"
            dev.txtDevFoodSensor.text = "사료 감지 센서  ·  수신 대기"
            dev.txtDevCover.text = "덮개 상태  ·  수신 대기"
            dev.txtDevPills.text = "${Prefs.pillAName(this)} --개  ·  ${Prefs.pillBName(this)} --개"
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
        dev.txtDevPills.text = "${Prefs.pillAName(this)} ${state.pillACount}개  ·  ${Prefs.pillBName(this)} ${state.pillBCount}개"
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
        if (isDeviceReady()) return true
        toast("장치를 연결하거나 시뮬레이션을 켜주세요.")
        appendDeveloperLog("전송 실패 · 장치 연결 또는 시뮬레이션 필요")
        return false
    }

    private fun sendDeveloper(command: String, label: String) {
        if (!requireDeveloperConnection()) return
        sendCommand(command)
        appendDeveloperLog("$label  →  '$command'${if (simulationEnabled) " · SIM" else ""}")
    }

    private fun runTimedMotor(
        onCommand: String,
        offCommand: String,
        durationMs: Long,
        label: String,
    ) {
        if (!requireDeveloperConnection()) return
        activeDeveloperStopCommands += offCommand
        sendCommand(onCommand)
        appendDeveloperLog("$label ON  →  '$onCommand'${if (simulationEnabled) " · SIM" else ""}")
        handler.postDelayed({
            if (activeDeveloperStopCommands.remove(offCommand) && isDeviceReady()) {
                sendCommand(offCommand)
                appendDeveloperLog("$label 자동 정지  →  '$offCommand'${if (simulationEnabled) " · SIM" else ""}")
            }
        }, durationMs)
    }

    private fun stopDeveloperMotor(offCommand: String, label: String) {
        if (!requireDeveloperConnection()) return
        activeDeveloperStopCommands.remove(offCommand)
        sendCommand(offCommand)
        appendDeveloperLog("$label  →  '$offCommand'${if (simulationEnabled) " · SIM" else ""}")
    }

    private fun emergencyStopDeveloper(reason: String) {
        if (!isDeviceReady()) {
            appendDeveloperLog("$reason · 장치/시뮬레이션 미연결")
            return
        }
        activeDeveloperStopCommands.clear()
        sendCommand(DogFoodProtocol.CMD_FOOD_MOTOR_OFF)
        sendCommand(DogFoodProtocol.CMD_WATER_MOTOR_OFF)
        sendCommand(DogFoodProtocol.CMD_COVER_STOP)
        appendDeveloperLog("$reason · b / d / g")
        toast("DC 모터 정지 명령을 전송했습니다.")
    }

    private fun stopActiveDeveloperMotors(reason: String) {
        if (activeDeveloperStopCommands.isEmpty() || !isDeviceReady()) return
        val commands = activeDeveloperStopCommands.toList()
        activeDeveloperStopCommands.clear()
        commands.forEach(::sendCommand)
        appendDeveloperLog("$reason · 실행 중 모터 자동 정지")
    }

    private fun runQuickSimulationTest() {
        if (bluetooth.isConnected()) {
            toast("빠른 통합 테스트는 실제 장치 연결을 끊고 실행해주세요.")
            return
        }
        if (!simulationEnabled) enableSimulation()

        simulateCommand(DogFoodProtocol.CMD_WATER_MOTOR_ON)
        simulateCommand(DogFoodProtocol.CMD_FOOD_MOTOR_ON)
        simulateCommand(DogFoodProtocol.CMD_COVER_CLOSE)
        simulateCommand(DogFoodProtocol.CMD_COVER_OPEN)
        syncLifeSchedulesToDevice(showToast = false)

        val now = LocalDateTime.now()
        val schedule = Prefs.lifeSchedules(this).firstOrNull()
        val foodGram = schedule?.foodGram ?: Prefs.defaultFoodGram(this)
        val pillA = schedule?.pillA ?: 1
        val pillB = schedule?.pillB ?: 1
        val event = DeviceFeedEvent(
            sequence = simEventSequence++,
            hour = now.hour,
            minute = now.minute,
            foodGram = foodGram,
            pillA = pillA,
            pillB = pillB,
        )
        handleDeviceFeedEvent(event)

        dogState = dogState.copy(
            foodWeight = (foodGram * 20 / 100).coerceAtLeast(0),
            pillACount = (dogState.pillACount - pillA).coerceAtLeast(0),
            pillBCount = (dogState.pillBCount - pillB).coerceAtLeast(0),
        )
        applyDogState(dogState)
        appendDeveloperLog("빠른 통합 테스트 완료 · 센서/UI/예약동기화/기록")
        AlertDialog.Builder(this)
            .setTitle("빠른 통합 테스트 완료")
            .setMessage(
                "장치 없이 다음 경로를 확인했습니다.\n\n" +
                    "• 가상 센서값 갱신\n" +
                    "• 물/사료/덮개 명령 처리\n" +
                    "• 생활 예약 동기화 로직\n" +
                    "• 생활 급식 실행 기록 저장\n\n" +
                    "기록 보기에서 방금 생성된 생활 급식 기록을 확인할 수 있습니다."
            )
            .setPositiveButton("확인", null)
            .show()
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
                if (isDeviceReady()) {
                    sendCommand(command)
                    appendDeveloperLog("$label ${index + 1}/$repeats  →  '$command'${if (simulationEnabled) " · SIM" else ""}")
                }
            }, index * 1300L)
        }
    }

    private fun enableSimulation() {
        simulationEnabled = true
        hasReceivedPacket = true
        dogState = DogState(
            startMode = 0,
            waterSet = 150,
            waterWeight = 125,
            foodWeight = 42,
            pillACount = 7,
            pillBCount = 7,
            waterLow = 1,
            coverMode = 0,
            foodLow = 1,
            waterEat = 0,
        )
        applyDogState(dogState)
        binding.txtConnection.text = "● 시뮬레이션"
        binding.txtConnection.setTextColor(color(R.color.accent_green))
        binding.txtConnection.setBackgroundResource(R.drawable.bg_chip_green)
        appendDeveloperLog("장치 없는 시뮬레이션 시작")
        refreshDeveloperConnection()
        refreshOverallStatus()
        toast("시뮬레이션을 시작했습니다. 실제 모터는 동작하지 않습니다.")
    }

    private fun disableSimulation(showToast: Boolean) {
        simulationEnabled = false
        hasReceivedPacket = false
        lastSimLifeMinuteKey = ""
        if (!bluetooth.isConnected()) {
            dogState = DogState()
            applyDogState(dogState)
            binding.txtConnection.text = "● 연결 안됨"
            binding.txtConnection.setTextColor(color(R.color.text_secondary))
            binding.txtConnection.setBackgroundResource(R.drawable.bg_chip_neutral)
        }
        refreshDeveloperConnection()
        refreshOverallStatus()
        if (showToast) toast("시뮬레이션을 종료했습니다.")
    }

    private fun simulateCommand(command: String) {
        if (!simulationEnabled) return
        var state = dogState
        when {
            command == DogFoodProtocol.CMD_WATER_UP -> state = state.copy(waterSet = (state.waterSet + 10).coerceAtMost(999))
            command == DogFoodProtocol.CMD_WATER_DOWN -> state = state.copy(waterSet = (state.waterSet - 10).coerceAtLeast(0))
            command == DogFoodProtocol.CMD_COVER_OPEN -> state = state.copy(coverMode = 0)
            command == DogFoodProtocol.CMD_COVER_CLOSE -> state = state.copy(coverMode = 1)
            command == DogFoodProtocol.CMD_REFILL_DONE -> state = state.copy(pillACount = 7, pillBCount = 7, waterLow = 1, foodLow = 1)
            command == DogFoodProtocol.CMD_RESET -> state = DogState(waterSet = 100, waterWeight = 100, foodWeight = 50, pillACount = 7, pillBCount = 7, waterLow = 1, foodLow = 1)
            command == DogFoodProtocol.CMD_START -> state = state.copy(startMode = if (state.startMode == 0) 1 else 0)
            command == DogFoodProtocol.CMD_WATER_MOTOR_ON -> state = state.copy(waterWeight = (state.waterWeight + 15).coerceAtMost(999))
            command == DogFoodProtocol.CMD_FOOD_MOTOR_ON -> state = state.copy(foodWeight = (state.foodWeight + 20).coerceAtMost(999))
            command == DogFoodProtocol.CMD_PILL_A_STEP -> state = state.copy(pillACount = (state.pillACount - 1).coerceAtLeast(0))
            command == DogFoodProtocol.CMD_PILL_B_STEP -> state = state.copy(pillBCount = (state.pillBCount - 1).coerceAtLeast(0))
            command.startsWith("wx") -> {
                val food = command.substringAfter('x').substringBefore('y').toIntOrNull() ?: 0
                val a = command.substringAfter('y').substringBefore('z').toIntOrNull() ?: 0
                val b = command.substringAfter('z').trim().toIntOrNull() ?: 0
                state = state.copy(
                    foodWeight = (food * 20 / 100).coerceAtLeast(0),
                    pillACount = (state.pillACount - a).coerceAtLeast(0),
                    pillBCount = (state.pillBCount - b).coerceAtLeast(0),
                )
            }
        }
        dogState = state
        hasReceivedPacket = true
        applyDogState(state)
    }

    private fun simulateLifeScheduleIfNeeded(now: LocalDateTime) {
        val minuteKey = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
        if (minuteKey == lastSimLifeMinuteKey) return
        val schedule = Prefs.lifeSchedules(this).firstOrNull { it.hour == now.hour && it.minute == now.minute } ?: return
        lastSimLifeMinuteKey = minuteKey
        val event = DeviceFeedEvent(
            sequence = simEventSequence++,
            hour = now.hour,
            minute = now.minute,
            foodGram = schedule.foodGram,
            pillA = schedule.pillA,
            pillB = schedule.pillB,
        )
        handleDeviceFeedEvent(event)
        dogState = dogState.copy(
            foodWeight = (schedule.foodGram * 20 / 100).coerceAtLeast(0),
            pillACount = (dogState.pillACount - schedule.pillA).coerceAtLeast(0),
            pillBCount = (dogState.pillBCount - schedule.pillB).coerceAtLeast(0),
        )
        applyDogState(dogState)
        appendDeveloperLog("생활 예약 시뮬레이션 실행 · ${schedule.timeText()}")
    }

    private fun handleDeviceFeedEvent(event: DeviceFeedEvent) {
        val added = RecordStore.appendLifeExecution(
            context = this,
            event = event,
            pillAName = Prefs.pillAName(this),
            pillBName = Prefs.pillBName(this),
        )
        if (added) {
            appendDeveloperLog("생활 급식 실행 확인 · %02d:%02d · 사료 %dg".format(event.hour, event.minute, event.foodGram))
            toast("생활 급식 실행 기록을 저장했습니다.")
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
        stopActiveDeveloperMotors("앱 종료")
        handler.removeCallbacks(tick)
        developerDialog?.dismiss()
        bluetooth.shutdown()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
