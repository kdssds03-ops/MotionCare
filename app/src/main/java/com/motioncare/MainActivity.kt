package com.motioncare

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val store by lazy { ProfileStore(filesDir) }
    private var profiles: List<Profile> = emptyList()
    /** 지금 탄 사람. null = 게스트 (기록을 남기지 않음) */
    var rider: Profile? = null
        private set
    private var screen: Screen = Screen.RiderSelect
    private val draft = Draft()
    private var uxNoKeyboard: Boolean? = null   // 차량 UX 규제(키보드 금지), 모르면 null
    /** 주행 중 키보드 입력 금지: 차량 규제가 금지하거나 P단이 아니면 막음 */
    val keyboardAllowed get() = uxNoKeyboard != true && parked != false

    private lateinit var vehicle: Vehicle
    private lateinit var location: FusedLocation
    private lateinit var tts: TextToSpeech
    private var car: Car? = null
    private var ttsReady = false

    private var comfort = Comfort()
    private var risk = Risk.LOW
    private var liveSpeed = 0.0
    private var liveHeading: Double? = null
    private var liveSteer: Double? = null
    private var demo: Iterator<DemoTick>? = null
    private var tripOn = false
    private var tripEnded = false
    private var lastSignal = 0
    private var lastCoachMs = 0L

    // 공조는 기능마다 지원 구역이 달라서 따로 저장
    private var powerAreas: List<SeatArea> = emptyList()
    private var recircAreas: List<SeatArea> = emptyList()
    private var fanMax: Map<SeatArea, Int> = emptyMap()
    private var tempRange: Map<SeatArea, ClosedFloatingPointRange<Float>> = emptyMap()
    private var ambient: Map<SeatArea, IntRange> = emptyMap()

    // 화면에 보여줄 케어 상태 (주행 시간 기준, 초)
    private var airAt: Double? = null
    private var airFan: Int? = null
    private var breathUntilMs = 0L
    private var turnAt: Double? = null
    private var turnDir = 0
    private var coachCount = 0
    private var lastSpoken = ""
    private var problem: String? = null
    private var feedback: Int? = null
    private var parked: Boolean? = null   // 기어 P 여부, 모르면 null
    private var reclined = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.p = Palette.of(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        profiles = store.load()
        show(Screen.RiderSelect)

        vehicle = Vehicle(this).apply { initialize() }
        val hvac = vehicle.getHVAC()
        hvac.checkHvacPowerCapabilities({ _, list -> powerAreas = list }, ::fail)
        hvac.checkHvacRecirculationCapabilities({ _, list -> recircAreas = list }, ::fail)
        hvac.checkHvacFanSpeedCapabilities({ _, map -> fanMax = map.mapValues { it.value.maxValue } }, ::fail)
        hvac.checkHvacTemperatureCapabilities({ _, map: Map<SeatArea, AreaBounds<Float>> ->
            tempRange = map.mapValues { it.value.minValue..it.value.maxValue }
        }, ::fail)
        vehicle.getLight().checkAmbientLightBrightnessCapabilities({ _, map ->
            ambient = map.mapValues { it.value.minValue..it.value.maxValue }
        }, ::fail)
        vehicle.getTurnSignal().registerTurnSignalState(signalListener)
        vehicle.getSteeringWheel().registerSteeringWheelAngle(steerListener)
        vehicle.getGear().getCurrentGearSelectionState({ gearListener.onSelected(it) }, ::fail)
        vehicle.getGear().registerGearSelection(gearListener)

        tts = TextToSpeech(applicationContext, Mode.ON_DEVICE)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() { tts.setLocale(Locale.KOREA); ttsReady = true }
            override fun onFailure(error: ErrorType) = fail(Exception("음성 안내를 켤 수 없어요 ($error)"))
        })

        location = FusedLocation(this)
        val perms = arrayOf(Car.PERMISSION_SPEED, "pleos.car.permission.FUSED_LOCATION")
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startSensors()
        else requestPermissions(perms, 1)

        ui.post(tick)
    }

    // --- 화면 전환 ---

    fun show(s: Screen) {
        screen = s
        setContentView(when (s) {
            Screen.RiderSelect -> riderSelect(
                profiles.sortedByDescending { p -> p.trips.firstOrNull()?.startMs ?: 0L }, columns(),
                onPick = ::pickRider, onDetail = { show(Screen.ProfileDetail(it.id)) },
                onGuest = { pickRider(null) }, onNew = { show(Screen.CreateProfile) })
            Screen.CreateProfile -> createProfile(draft, keyboardAllowed,
                onChange = { show(Screen.CreateProfile) }, onSave = ::saveDraft, onCancel = { show(Screen.RiderSelect) })
            // ponytail: 리포트·상세 화면은 Task 8에서 채움. 지금은 주행 화면 아래 카드가 리포트 역할.
            Screen.Drive, Screen.Report -> buildUi()
            is Screen.ProfileDetail -> page(profiles.firstOrNull { it.id == s.id }?.name ?: "프로필", "기록과 설정은 곧 볼 수 있어요",
                { show(Screen.RiderSelect) }, View(this))
        })
        render()
    }

    private fun pickRider(p: Profile?) {
        rider = p
        comfort = Comfort(p?.sensitivity ?: 1.0)
        risk = Risk.LOW; tripEnded = false; feedback = null
        trace.clear()
        show(Screen.Drive)
    }

    private fun saveDraft() {
        if (!draft.valid || !keyboardAllowed) { show(Screen.CreateProfile); return }
        val p = Profile(java.util.UUID.randomUUID().toString(), draft.name.take(10), draft.seat!!, surveySensitivity(draft.answers.toList()))
        profiles = profiles + p
        store.save(profiles)
        draft.name = ""; draft.seat = null; draft.answers.fill(-1)
        pickRider(p)
    }

    /** 게스트는 저장하지 않음. 프로필이면 목록에 반영하고 파일에 저장. */
    private fun updateRider(f: (Profile) -> Profile) {
        val p = rider ?: return
        val n = f(p)
        rider = n
        profiles = profiles.map { if (it.id == n.id) n else it }
        store.save(profiles)
    }

    @Deprecated("Activity 기본 뒤로가기")
    override fun onBackPressed() {
        when (screen) {
            Screen.RiderSelect -> super.onBackPressed()
            // 주행 중에는 화면만 내리고 주행은 계속 (닫으면 기록이 사라짐)
            Screen.Drive, Screen.Report -> if (tripOn) moveTaskToBack(true) else show(Screen.RiderSelect)
            else -> show(Screen.RiderSelect)
        }
    }

    // 회전·주간/야간 전환 때 액티비티를 새로 만들지 않고 같은 상태로 다시 그림
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        T.p = Palette.of(c.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        show(screen)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<String>, r: IntArray) = startSensors()

    // 권한이 거부돼도 앱은 꺼지지 않고 데모 주행은 가능하도록 실패만 기록
    private fun startSensors() {
        runCatching {
            car = Car.createCar(this)
            (car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.registerCallback(
                object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(v: CarPropertyValue<*>) { liveSpeed = abs((v.value as Float).toDouble()) }
                    override fun onErrorEvent(propId: Int, zone: Int) {}
                },
                VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL
            )
        }.onFailure { fail(Exception("속도 센서를 읽을 수 없어요")) }
        runCatching {
            val ux = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            val apply = { r: CarUxRestrictions? ->
                val was = keyboardAllowed
                uxNoKeyboard = r?.let { it.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD != 0 }
                if (was != keyboardAllowed) ui.post { if (screen == Screen.CreateProfile) show(screen) }
            }
            apply(ux?.currentCarUxRestrictions)
            ux?.registerListener { apply(it) }
        }
        runCatching {
            if (location.isSupported()) {
                location.initialize()
                location.registerFusedLocationCallback(locationListener)
            }
        }.onFailure { fail(Exception("위치 정보를 읽을 수 없어요")) }
    }

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) { data?.let { liveHeading = it.heading.toDouble() } }
        override fun onError(e: Exception) = fail(e)
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            val was = keyboardAllowed
            parked = gear?.let { it == VehicleGear.GEAR_PARK }
            if (parked == true && problem?.startsWith("주행 중") == true) problem = null
            ui.post { if (screen == Screen.CreateProfile && was != keyboardAllowed) show(screen) else render() }
        }
        override fun onFailed(e: Exception) = fail(e)
    }

    private val steerListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { liveSteer = angle?.toDouble() }
        override fun onFailed(e: Exception) = fail(e)
    }

    private val signalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            onSignal(when (state) { TurnSignalValue.LEFT -> -1; TurnSignalValue.RIGHT -> 1; else -> 0 })
        }
        override fun onFailed(e: Exception) = fail(e)
    }

    // 0.5초마다 위험 지수 갱신. 데모 중에는 4배속(0.125초마다)으로 가상 주행을 재생.
    private val tick = object : Runnable {
        override fun run() {
            if (!tripOn) { render(); ui.postDelayed(this, 500); return }
            val d = demo
            val t = d?.takeIf { it.hasNext() }?.next()
            if (d != null && t == null) { endTrip(); ui.postDelayed(this, 500); return }
            if (t != null) onSignal(t.signal)
            // 실제 주행에서는 핸들 각도 우선, 데모는 미리 만든 진행 방향 사용
            val r = comfort.update(t?.speed ?: liveSpeed, t?.heading ?: liveHeading, DT, if (t == null) liveSteer else null)
            trace.push(comfort.windowMsdv * comfort.sensitivity)
            if (r >= Risk.CAUTION && risk < Risk.CAUTION) airCare()
            if (r == Risk.HIGH && risk < Risk.HIGH) breathing()
            risk = r
            if (comfort.harshJustNow) coach()
            render()
            ui.postDelayed(this, if (demo != null) 125 else 500)
        }
    }

    // --- 케어 동작 ---

    /** 바람 케어: 외기 유입 + 풍량 올리기 + 온도 살짝 낮추기 (D'Amour 2017) */
    private fun airCare() {
        say("멀미 위험이 올라가고 있어요. 바깥 공기를 넣고 바람을 조금 세게 할게요. 앞쪽 창밖을 바라봐 주세요.")
        airAt = comfort.durationS
        trace.mark("공기 케어")
        val hvac = vehicle.getHVAC()
        powerAreas.forEach { hvac.setHvacPower(it, true, onFailed = ::fail) }
        // ponytail: 전원 켜짐 콜백을 기다리지 않고 0.5초 뒤 설정. 실차에서 실패가 보이면 onComplete로 연결.
        ui.postDelayed({
            recircAreas.forEach { hvac.setHvacRecirculation(it, false, onFailed = ::fail) }
            // 자동 공조 중에는 풍량을 직접 못 바꾸므로 자동을 끈 뒤 설정
            fanMax.forEach { (a, max) ->
                val level = (max * 2 + 2) / 3
                hvac.setHvacAuto(a, false,
                    onComplete = { _, _ -> hvac.setHvacFanSpeed(a, level, onComplete = { _, _ -> airFan = level }, onFailed = ::fail) },
                    onFailed = ::fail)
            }
            // 좌석별 온도를 0.3초 간격으로: Pleos 에뮬레이터는 거의 동시에 온 두 요청 중
            // 마지막 좌석 변경만 알려서, 앞 좌석(운전석)이 화면과 차량 서비스에 옛 값으로 남았음
            tempRange.entries.forEachIndexed { i, (a, r) ->
                ui.postDelayed({
                    hvac.setHvacTemperature(a, CARE_TEMP_C.coerceIn(r), TemperatureUnit.CELSIUS, onFailed = ::fail)
                }, i * TEMP_GAP_MS)
            }
        }, 500)
    }

    /** 호흡 가이드: 조명 4초 밝게(들숨) / 4초 어둡게(날숨), 분당 7.5회 (Stromberg 2015) */
    private fun breathing() {
        say("천천히 숨 쉬어 볼게요. 조명이 밝아지면 들이쉬고, 어두워지면 내쉬세요.")
        breathUntilMs = System.currentTimeMillis() + 60_000
        if (tripOn) trace.mark("호흡 가이드")
        val light = vehicle.getLight()
        for (i in 0 until 15) ui.postDelayed({
            ambient.forEach { (area, range) ->
                val v = if (i == 14) (range.first + range.last) / 2 else if (i % 2 == 0) range.last else range.first
                light.setAmbientLightBrightness(area, v, { _, _ -> }, ::fail)
            }
        }, i * 4000L)
        render()
    }

    /**
     * 동승석 등받이 살짝 젖히기 (TUM 연구). 동승자가 버튼으로 직접 실행.
     * Pleos는 P단이 아니면 시트 이동을 막음(오류 50001, 속도 0이어도 D면 거부) → P단에서만 시도.
     */
    private fun recline() {
        if (parked == false) { render(); return }
        val seat = vehicle.getSeat()
        val area = SeatArea(SeatValue.ROW_1_RIGHT)
        seat.moveSeatBackrestAngle(area, MoveControl.BACKWARD,
            onComplete = { _, _ -> reclined = true; ui.post { render() } }, onFailed = ::fail)
        ui.postDelayed({ seat.moveSeatBackrestAngle(area, MoveControl.STOP, onFailed = ::fail) }, RECLINE_MS)
    }

    /** 움직임 예고: 방향지시등이 켜지면 동승자에게 미리 알림 */
    private fun onSignal(s: Int) {
        if (s != 0 && s != lastSignal) {
            say(if (s < 0) "곧 왼쪽으로 돌아요" else "곧 오른쪽으로 돌아요")
            turnDir = s; turnAt = comfort.durationS
        }
        lastSignal = s
    }

    /** 운전자 코칭: 급조작 시 음성 한 마디 (30초에 한 번까지) */
    private fun coach() {
        val now = System.currentTimeMillis()
        if (now - lastCoachMs < 30_000) return
        lastCoachMs = now
        coachCount++
        say("동승자를 위해 조금만 더 부드럽게 운전해 주세요.")
    }

    // --- 주행 시작/종료 ---

    private fun startTrip(withDemo: Boolean) {
        comfort = Comfort(comfort.sensitivity)
        risk = Risk.LOW
        demo = if (withDemo) demoDrive(DT).iterator() else null
        tripOn = true; tripEnded = false
        airAt = null; airFan = null; turnAt = null; coachCount = 0; problem = null; feedback = null
        reclined = false
        trace.clear()
        render()
    }

    private fun endTrip() {
        if (!tripOn) return
        tripOn = false; tripEnded = true
        demo = null
        say("주행이 끝났어요. 편안함 점수는 ${comfort.score()}점이에요.")
        render()
    }

    private fun onMisc(m: Int) {
        if (!tripEnded) return
        comfort.feedback(m)
        feedback = m
        updateRider { it.copy(sensitivity = comfort.sensitivity) }
        render()
    }

    private fun setSensitivity(s: Double) {
        comfort.sensitivity = s
        updateRider { it.copy(sensitivity = s) }
        render()
    }

    // --- 화면 ---

    private lateinit var scope: TextView
    private lateinit var backBtn: LinearLayout
    private lateinit var finding: TextView
    private lateinit var startBtn: LinearLayout
    private lateinit var demoBtn: LinearLayout
    private lateinit var riskValue: TextView
    private lateinit var riskPill: TextView
    private lateinit var riskNote: TextView
    // 그래프는 화면을 다시 그려도 기록이 남도록 한 번만 만듦
    private val trace by lazy { TraceView(this) }
    private lateinit var airState: TextView
    private lateinit var airSub: TextView
    private lateinit var breathBtn: LinearLayout
    private lateinit var turnState: TextView
    private lateinit var coachState: TextView
    private lateinit var reclineBtn: LinearLayout
    private lateinit var reclineSub: TextView
    private lateinit var careFoot: TextView
    private lateinit var scoreV: TextView
    private lateinit var harshV: TextView
    private lateinit var maxV: TextView
    private lateinit var timeV: TextView
    private lateinit var taxiV: TextView
    private lateinit var taxiPill: TextView
    private val sensBtns = ArrayList<Pair<Double, TextView>>()
    private val miscBtns = ArrayList<TextView>()
    private lateinit var miscNote: TextView

    private fun buildUi(): View {
        sensBtns.clear(); miscBtns.clear()
        (trace.parent as? android.view.ViewGroup)?.removeView(trace)
        val g = dp(T.GAP)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.GROUND)
            setPadding(dp(29), dp(20), dp(29), dp(29))
        }

        // 상단: 제품 마크와 이름, 오른쪽에 대상 좌석과 민감도
        scope = label(T.SMALL, T.MUTED)
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            backBtn = button("탑승자", icon = R.drawable.ic_back) { onBackPressed() }.apply { minimumWidth = 0 }
            addView(backBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(T.GAP) })
            addView(MarkView(context), LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(label(T.SECTION, T.TEXT, 600, "멀미케어").apply { setPadding(dp(12), 0, 0, 0) })
            addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f))
            addView(scope)
        })

        // 제목 줄: 화면 이름 + 지금의 발견, 오른쪽에 주행 버튼
        finding = label(T.BODY, T.MUTED)
        startBtn = button("주행 시작", primary = true) { if (tripOn) endTrip() else startTrip(false) }
        demoBtn = button("데모 주행") { startTrip(true) }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(20))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(T.TITLE, T.TEXT, 600, "동승석 멀미 케어"))
                addView(finding.apply { setPadding(0, dp(6), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(row(demoBtn, startBtn, gap = 12))
        })

        // 1행: 위험 지수(2/3) | 케어(1/3)
        riskValue = label(T.LEAD, T.TEXT, 500)
        riskPill = pill("", 0, 0)
        riskNote = label(T.SMALL, T.MUTED)
        val riskCard = card().apply {
            addView(LinearLayout(context).apply {
                addView(label(T.SECTION, T.TEXT, 500, "멀미 위험 지수"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(T.SMALL, T.FAINT, 400, "누적 흔들림 · 멈추면 서서히 내려가요 · ISO 2631-1"))
            })
            addView(row(riskValue, riskPill, gap = 14).apply { setPadding(0, dp(10), 0, 0) })
            addView(riskNote)
            // 그래프가 카드의 남는 높이를 채움 (최소 280)
            addView(trace, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
            trace.minimumHeight = dp(280)
        }

        airState = label(T.SMALL, T.MUTED)
        airSub = label(T.SMALL, T.MUTED)
        breathBtn = button("시작") { breathing() }
        turnState = label(T.SMALL, T.MUTED)
        coachState = label(T.SMALL, T.MUTED)
        reclineBtn = button("젖히기") { recline() }
        careFoot = label(T.SMALL, T.FAINT)
        val careCard = card().apply {
            addView(label(T.SECTION, T.TEXT, 500, "지금 하는 케어"))
            // 다섯 줄이 카드 높이에 고르게 퍼져서 아래에 빈 띠가 남지 않게
            val each = { v: View -> addView(v, LinearLayout.LayoutParams(-1, 0, 1f)) }
            each(careRow("공기 케어", airSub, airState, first = true))
            each(careRow("호흡 가이드", label(T.SMALL, T.MUTED, 400, "조명이 밝아지면 들숨, 어두워지면 날숨"), breathBtn))
            each(careRow("회전 예고", label(T.SMALL, T.MUTED, 400, "방향지시등이 켜지면 음성으로 알려요"), turnState))
            each(careRow("운전자 안내", label(T.SMALL, T.MUTED, 400, "급조작 시 30초에 한 번까지"), coachState))
            reclineSub = label(T.SMALL, T.MUTED)
            each(careRow("등받이", reclineSub, reclineBtn))
            addView(careFoot.apply { setPadding(0, dp(12), 0, 0) })
        }
        // 1행이 창의 남는 높이를 모두 차지해서 바닥에 빈 띠가 생기지 않게
        root.addView(LinearLayout(this).apply {
            addView(riskCard, LinearLayout.LayoutParams(0, -1, 2f))
            addView(careCard, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = g })
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        // 2행: 이번 주행(2/3) | 탑승자(1/3)
        scoreV = label(T.KPI, T.TEXT, 500); harshV = label(T.KPI, T.TEXT, 500)
        maxV = label(T.KPI, T.TEXT, 500); timeV = label(T.KPI, T.TEXT, 500)
        taxiV = label(T.BODY, T.TEXT); taxiPill = pill("", 0, 0)
        val tripCard = card().apply {
            addView(label(T.SECTION, T.TEXT, 500, "이번 주행"))
            addView(LinearLayout(context).apply {
                setPadding(0, dp(14), 0, dp(18))
                listOf("편안함 점수" to scoreV, "급조작" to harshV, "가장 높았던 단계" to maxV, "주행 시간" to timeV)
                    .forEachIndexed { i, (name, v) ->
                        if (i > 0) addView(View(context).apply { setBackgroundColor(T.RULE) }, LinearLayout.LayoutParams(dp(1), -1).apply { marginEnd = dp(20) })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(T.SMALL, T.MUTED, 400, name)); addView(v)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
            })
            addView(View(context).apply { setBackgroundColor(T.RULE) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(T.SMALL, T.MUTED, 400, "택시 플랫폼 전송 미리보기"))
                    addView(taxiV)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(taxiPill)
            })
        }

        miscNote = label(T.SMALL, T.FAINT)
        val sensRow = LinearLayout(this).apply {
            background = fill(T.RAISED, dp(999).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            listOf(0.8 to "낮음", 1.0 to "보통", 1.3 to "높음").forEach { (v, name) ->
                val b = label(T.BODY, T.TEXT, 500, name).apply {
                    gravity = Gravity.CENTER; minHeight = dp(44)
                    setOnClickListener { setSensitivity(v) }
                }
                sensBtns += v to b
                addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val miscRow = LinearLayout(this).apply {
            for (m in 0..10) {
                val b = label(T.BODY, T.TEXT, 500, "$m").apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { onMisc(m) }
                }
                miscBtns += b
                addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (m > 0) marginStart = dp(4) })
            }
        }
        val riderCard = card().apply {
            addView(label(T.SECTION, T.TEXT, 500, "탑승자"))
            addView(label(T.SMALL, T.MUTED, 400, "멀미 민감도").apply { setPadding(0, dp(14), 0, dp(8)) })
            addView(sensRow)
            addView(label(T.SMALL, T.MUTED, 400, "이번 주행 멀미는 어땠나요? (0 괜찮음 · 10 토할 것 같음)").apply { setPadding(0, dp(18), 0, dp(8)) })
            addView(miscRow)
            addView(miscNote.apply { setPadding(0, dp(10), 0, 0) })
        }
        root.addView(LinearLayout(this).apply {
            addView(tripCard, LinearLayout.LayoutParams(0, -1, 2f))
            addView(riderCard, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = g })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = g })

        return ScrollView(this).apply { setBackgroundColor(T.GROUND); isFillViewport = true; addView(root) }
    }

    /** 케어 카드 한 줄: 이름과 설명, 오른쪽에 상태나 버튼 */
    private fun careRow(name: String, sub: TextView, right: View, first: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (!first) addView(View(context).apply { setBackgroundColor(T.RULE) }, LinearLayout.LayoutParams(-1, dp(1)))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(T.BODY, T.TEXT, 500, name))
                addView(sub)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(right)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun ago(s: Double) = (comfort.durationS - s).toInt().let { "%d:%02d 전".format(it / 60, it % 60) }

    private fun render() {
        if (screen != Screen.Drive && screen != Screen.Report) return
        val idx = comfort.windowMsdv * comfort.sensitivity
        val started = tripOn || tripEnded
        val sensName = when { comfort.sensitivity < 0.9 -> "낮음"; comfort.sensitivity > 1.15 -> "높음"; else -> "보통" }
        scope.text = (rider?.let { "${it.name} · ${seatName(it.seat)}" } ?: "게스트 · 기록 안 남김") + " · 민감도 $sensName"
        backBtn.visibility = if (tripOn) View.GONE else View.VISIBLE

        // 20초(40샘플) 전보다 낮으면 회복 중
        val falling = trace.count > 40 && idx < trace.values[(trace.count - 41) % TraceView.CAP] - 0.05
        finding.text = when {
            tripEnded -> "주행 종료 · 편안함 점수 ${comfort.score()}점, 급조작 ${comfort.harshEvents}회"
            !tripOn -> "주행 전 · 차량 연결됨"
            falling && risk != Risk.LOW -> "흔들림이 줄어 회복 중이에요 · 케어는 계속하고 있어요"
            risk == Risk.HIGH -> "멀미 위험 단계 · 호흡 가이드와 공기 케어를 하고 있어요"
            risk == Risk.CAUTION -> "흔들림이 커지고 있어요 · 바깥 공기를 넣는 중"
            else -> "흔들림이 적어요 · 주의 단계(6)까지 %.1f 남음".format((Comfort.CAUTION_MSDV - idx).coerceAtLeast(0.0))
        }
        startBtn.buttonText = if (tripOn) "주행 종료" else "주행 시작"
        demoBtn.visibility = if (tripOn) View.GONE else View.VISIBLE

        riskValue.text = if (started) "%.1f".format(idx) else "—"
        val (pText, pBg, pInk) = when {
            !started -> Triple("대기", T.RAISED, T.MUTED)
            tripEnded -> Triple("주행 종료", T.RAISED, T.MUTED)
            risk == Risk.HIGH -> Triple("위험", T.NEG_BG, T.NEG)
            risk == Risk.CAUTION -> Triple("주의", T.WARN_BG, T.WARN)
            else -> Triple("편안", T.POS_BG, T.POS)
        }
        riskPill.text = pText; riskPill.setTextColor(pInk); riskPill.background = fill(pBg, dp(999).toFloat())
        riskNote.text = when {
            !started -> "주의 6 · 위험 10부터 자동 케어가 시작돼요"
            tripEnded -> "주행이 끝난 시점의 값 · 주의 6 · 위험 10"
            else -> "주의 6 · 위험 10 · 민감도 %.2f 반영".format(comfort.sensitivity)
        }

        airSub.text = when {
            airAt == null -> "주의 단계가 되면 바깥 공기를 넣어요"
            else -> "외기 · " + (airFan?.let { "풍량 ${it}단 · " } ?: "") + "%.0f°C".format(CARE_TEMP_C)
        }
        airState.text = airAt?.let { ago(it) } ?: "대기"
        val breathLeft = ((breathUntilMs - System.currentTimeMillis()) / 1000).toInt()
        breathBtn.buttonText = if (breathLeft > 0) "%d:%02d 남음".format(breathLeft / 60, breathLeft % 60) else "시작"
        turnState.text = turnAt?.let { (if (turnDir < 0) "왼쪽 · " else "오른쪽 · ") + ago(it) } ?: "—"
        coachState.text = if (started) "${coachCount}회" else "—"
        // 등받이: P단이 아니면 버튼을 흐리게 하고 이유를 보여줌
        val seatLocked = parked == false
        reclineSub.text = if (seatLocked) "주행 중에는 시트를 움직일 수 없어요" else "동승석 등받이를 조금 젖혀요"
        reclineBtn.buttonText = when { reclined -> "젖혔어요"; seatLocked -> "P단에서 가능"; else -> "젖히기" }
        reclineBtn.isEnabled = !seatLocked || reclined
        careFoot.text = problem?.let { "차량 응답: $it" } ?: if (lastSpoken.isEmpty()) "" else "마지막 음성 · $lastSpoken"
        careFoot.setTextColor(if (problem != null) T.NEG else T.FAINT)

        scoreV.text = if (started) "${comfort.score()}" else "—"
        harshV.text = if (started) "${comfort.harshEvents}회" else "—"
        maxV.text = if (started) when (comfort.maxRisk) { Risk.HIGH -> "위험"; Risk.CAUTION -> "주의"; else -> "편안" } else "—"
        val sec = comfort.durationS.toInt()
        timeV.text = if (started) "%d:%02d".format(sec / 60, sec % 60) else "—"
        taxiV.text = if (tripEnded) "점수 ${comfort.score()} · 급조작 ${comfort.harshEvents}회 · 최고 ${maxV.text} · ${sec / 60}분" else "주행이 끝나면 만들어져요"
        val (tText, tBg, tInk) = if (tripEnded) Triple("전송 준비됨", T.POS_BG, T.POS) else Triple("주행 중", T.RAISED, T.MUTED)
        taxiPill.text = tText; taxiPill.setTextColor(tInk); taxiPill.background = fill(tBg, dp(999).toFloat())
        taxiPill.visibility = if (started) View.VISIBLE else View.INVISIBLE

        sensBtns.forEach { (v, b) ->
            val on = abs(v - comfort.sensitivity) < 0.12
            b.setTextColor(if (on) T.p.primaryText else T.MUTED)
            b.background = if (on) fill(T.p.primaryBg, dp(999).toFloat()) else null
        }
        miscBtns.forEachIndexed { m, b ->
            val on = feedback == m
            b.isEnabled = tripEnded
            b.setTextColor(when { on -> T.p.primaryText; tripEnded -> T.TEXT; else -> T.FAINT })
            b.background = fill(if (on) T.p.primaryBg else T.RAISED, dp(999).toFloat())
        }
        miscNote.text = when {
            feedback != null -> "반영했어요 · 다음 주행부터 민감도 %.2f".format(comfort.sensitivity)
            tripEnded -> "알려주시면 다음 주행부터 경고 시점을 맞춰요"
            else -> "주행이 끝나면 물어볼게요"
        }
    }

    private fun say(text: String) {
        android.util.Log.i("MotionCare", "say: $text")   // 시연 영상 음성 동기화용
        lastSpoken = text
        if (ttsReady) tts.speak(text, true)
    }

    private fun fail(e: Exception) {
        val m = e.message ?: "알 수 없는 오류"
        problem = when {
            "50001" in m -> "주행 중(P단 아님)이라 등받이를 움직일 수 없어요"
            "HVAC_FAN_SPEED" in m -> "풍량을 지금 바꿀 수 없어요"
            "HVAC" in m -> "공조 설정 일부를 바꿀 수 없어요"
            "Permission" in m -> "권한이 없어 일부 제어를 못 했어요"
            else -> m.take(60)
        }
        ui.post { render() }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        vehicle.getTurnSignal().unregisterTurnSignalState(signalListener)
        vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerListener)
        vehicle.getGear().unregisterGearSelection(gearListener)
        if (location.isSupported()) location.unregisterFusedLocationCallback(locationListener)
        vehicle.release()
        location.release()
        tts.release()
        car?.disconnect()
        super.onDestroy()
    }

    companion object {
        const val DT = 0.5
        const val CARE_TEMP_C = 21f
        const val TEMP_GAP_MS = 300L
        const val RECLINE_MS = 1000L   // 등받이 젖히는 시간. 실차에서 몇 도 움직이는지 보고 조정.
    }
}
