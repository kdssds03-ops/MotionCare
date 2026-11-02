package com.motioncare

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
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
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
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
import kotlin.math.roundToInt

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
    private var tripStartMs = 0L
    private var report: ReportState? = null
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
    private var parked: Boolean? = null   // 기어 P 여부, 모르면 null
    private var reclined = false
    private var reclineAreas: List<SeatArea>? = null   // 등받이를 움직일 수 있는 좌석, 모르면 null

    // 즉시 케어·음성·휴게소·내비
    private lateinit var stt: SpeechToText
    private var sttReady = false
    private var listening = false
    private lateinit var navi: NaviHelper
    private var naviReady = false
    private var routeActive = false      // 내비 경로 안내 중이면 방향지시등 예고 대신 내비 회전 예고
    private var lastTbtKey: String? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private val restAreas by lazy { parseRestAreas(resources.openRawResource(R.raw.rest_areas).bufferedReader().readText()) }
    private var offer: Pair<RestArea, Double>? = null   // 지금 묻고 있는 휴게소
    private var pendingRest: RestArea? = null           // 내비 상태 확인을 기다리는 휴게소
    private var assistNote: String? = null              // 한 줄 안내 (음성 인식·휴게소·내비 결과)
    private var highSinceS: Double? = null
    private var autoOffered = false
    private val gate = SpeechGate()
    /** 이번 주행에서 실제로 실행된 케어 (리포트·학습용) */
    val caresThisTrip = mutableSetOf<Care>()
    private val sentTemp = HashMap<SeatArea, Float>()   // 앱이 마지막으로 보낸 좌석별 온도
    private val tempListeners = HashMap<SeatArea, HvacTemperatureListener>()
    private var tempSentAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.p = Palette.of(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        profiles = store.load()
        show(Screen.RiderSelect)
        offerResume()

        vehicle = Vehicle(this).apply { initialize() }
        val hvac = vehicle.getHVAC()
        hvac.checkHvacPowerCapabilities({ _, list -> powerAreas = list }, ::fail)
        hvac.checkHvacRecirculationCapabilities({ _, list -> recircAreas = list }, ::fail)
        hvac.checkHvacFanSpeedCapabilities({ _, map -> fanMax = map.mapValues { it.value.maxValue } }, ::fail)
        hvac.checkHvacTemperatureCapabilities({ _, map: Map<SeatArea, AreaBounds<Float>> ->
            tempRange = map.mapValues { it.value.minValue..it.value.maxValue }
            tempRange.keys.filter { it !in tempListeners }.forEach { a ->
                tempListeners[a] = tempListener(a).also { hvac.registerHvacTemperature(a, it) }
            }
        }, ::fail)
        vehicle.getSeat().checkMoveSeatBackrestAngleCapabilities({ _, list -> reclineAreas = list; ui.post { render() } }, { reclineAreas = null })
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
        stt = SpeechToText(applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
        navi = NaviHelper(this)
        val perms = arrayOf(Car.PERMISSION_SPEED, "pleos.car.permission.FUSED_LOCATION", "pleos.car.permission.STT_SERVICE",
            "pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH")
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startSensors()
        else requestPermissions(perms, 1)

        ui.post(tick)
    }

    // --- 화면 전환 ---

    fun show(s: Screen) {
        screen = s
        setContentView(when (s) {
            Screen.RiderSelect -> riderSelectView()
            Screen.CreateProfile -> createProfile(draft, keyboardAllowed,
                onChange = { show(Screen.CreateProfile) }, onSave = ::saveDraft,
                onCancel = { show(if (draft.trip != null) Screen.Report else Screen.RiderSelect) })
            Screen.Drive -> buildUi()
            Screen.Report -> report?.let { r ->
                report(r, rider, columns() > 1, keyboardAllowed, onChange = { show(Screen.Report) }, onDone = ::finishReport,
                    onSaveGuest = { draft.trip = r.final(); show(Screen.CreateProfile) })
            } ?: buildUi().also { screen = Screen.Drive }
            is Screen.ProfileDetail -> profiles.firstOrNull { it.id == s.id }?.let { p ->
                profileDetail(p, columns() > 1,
                    onBack = { show(Screen.RiderSelect) }, onStart = { pickRider(p) },
                    onToggle = { c, on -> updateProfile(p.id) { if (on) it.enableCare(c) else it.disableCare(c) }; show(s) },
                    onTemp = { t -> updateProfile(p.id) { it.copy(careTempC = learnTemp(t)) }; show(s) },
                    onDelete = { confirmDelete(p) })
            } ?: riderSelectView().also { screen = Screen.RiderSelect }
        })
        render()
    }

    private fun riderSelectView() = riderSelect(
        profiles.sortedByDescending { p -> p.trips.firstOrNull()?.startMs ?: 0L }, columns(),
        onPick = ::pickRider, onDetail = { show(Screen.ProfileDetail(it.id)) },
        onGuest = { pickRider(null) }, onNew = { draft.trip = null; show(Screen.CreateProfile) })

    private fun confirmDelete(p: Profile) {
        android.app.AlertDialog.Builder(this)
            .setTitle("${p.name} 프로필을 삭제할까요?")
            .setMessage("주행 기록 ${p.trips.size}회와 학습한 설정이 함께 지워져요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                profiles = profiles.filter { it.id != p.id }
                store.save(profiles)
                if (rider?.id == p.id) rider = null
                show(Screen.RiderSelect)
            }
            .show()
    }

    /** 리포트 [완료]: 프로필이면 학습해서 저장, 게스트면 민감도만 이번 실행 동안 반영 */
    private fun finishReport() {
        val r = report ?: return show(Screen.Drive)
        val t = r.final()
        if (rider != null) updateRider { it.learn(t) }
        else t.misc?.let { comfort.feedback(it) }
        report = null; tripEnded = false
        comfort = Comfort(rider?.sensitivity ?: comfort.sensitivity)
        resetTripState()
        show(Screen.Drive)
    }

    // --- 생명주기: 다른 앱(내비)으로 가도 측정·케어는 계속, 화면 갱신만 멈춤 ---

    private var visible = true
    private var lastSaveMs = 0L

    override fun onStart() { super.onStart(); visible = true; if (screen == Screen.Drive) render() }
    override fun onStop() { visible = false; saveOngoing(); super.onStop() }

    /** 진행 중 주행 저장. 게스트·주행 아님이면 파일을 지움 (게스트 기록은 남기지 않음). */
    private fun saveOngoing() = runCatching {
        store.saveOngoing(if (tripOn && demo == null) Ongoing(rider?.id, false, tripStartMs, comfort.snapshot(), caresThisTrip.toSet()) else null)
    }

    /** 앱이 강제로 꺼졌다 다시 켜졌을 때: 6시간 안의 프로필 주행이면 이어 갈지 묻기 */
    private fun offerResume() {
        val o = store.loadOngoing() ?: return
        val p = profiles.firstOrNull { it.id == o.profileId }
        if (p == null || System.currentTimeMillis() - o.startMs > RESUME_MAX_MS) { store.saveOngoing(null); return }
        val min = ((o.comfort["durationS"] ?: 0.0) / 60).roundToInt()
        android.app.AlertDialog.Builder(this)
            .setTitle("진행 중이던 주행을 이어갈까요?")
            .setMessage("${p.name} · ${seatName(p.seat)} · ${min}분 진행")
            .setCancelable(false)
            .setNegativeButton("새로 시작") { _, _ -> store.saveOngoing(null) }
            .setPositiveButton("이어하기") { _, _ ->
                pickRider(p)
                comfort.restore(o.comfort)
                caresThisTrip += o.cares
                tripStartMs = o.startMs; tripOn = true; demo = null
                render()
            }
            .show()
    }

    private fun pickRider(p: Profile?) {
        rider = p
        comfort = Comfort(p?.sensitivity ?: 1.0)
        tripEnded = false
        resetTripState()
        show(Screen.Drive)
    }

    private fun saveDraft() {
        if (!draft.valid || !keyboardAllowed) { show(Screen.CreateProfile); return }
        val fresh = Profile(java.util.UUID.randomUUID().toString(), draft.name.take(10), draft.seat!!, surveySensitivity(draft.answers.toList()))
        val trip = draft.trip
        val p = trip?.let { fresh.learn(it) } ?: fresh   // 게스트 주행을 저장하면 첫 기록이 됨
        profiles = profiles + p
        store.save(profiles)
        draft.name = ""; draft.seat = null; draft.answers.fill(-1); draft.trip = null
        if (trip != null) { report = null; tripEnded = false }
        pickRider(p)
    }

    /** 게스트는 저장하지 않음. 프로필이면 목록에 반영하고 파일에 저장. */
    private fun updateRider(f: (Profile) -> Profile) { rider?.let { updateProfile(it.id, f) } }

    private fun updateProfile(id: String, f: (Profile) -> Profile) {
        profiles = profiles.map { if (it.id == id) f(it) else it }
        if (rider?.id == id) rider = profiles.first { it.id == id }
        store.save(profiles)
    }

    @Deprecated("Activity 기본 뒤로가기")
    override fun onBackPressed() {
        when (screen) {
            Screen.RiderSelect -> super.onBackPressed()
            // 주행 중에는 화면만 내리고 주행은 계속 (닫으면 기록이 사라짐)
            Screen.Drive -> if (tripOn) moveTaskToBack(true) else show(Screen.RiderSelect)
            Screen.Report -> finishReport()
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
        // 음성 인식·내비는 실패해도 버튼 흐름은 그대로 동작 (안내만 바뀜)
        runCatching {
            stt.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() { stt.setLocale(Locale.KOREA); stt.addListener(sttListener); sttReady = true }
                override fun onFailure(error: ai.pleos.playground.stt.constant.ErrorType) { sttReady = false }
            })
        }
        runCatching { navi.initialize({ naviReady = true; navi.addListener(naviListener) }, { naviReady = false }) }
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
        override fun onChanged(data: LocationData?) {
            data?.let { liveHeading = it.heading.toDouble(); lastLat = it.latitude; lastLon = it.longitude }
        }
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
            val v = when (state) { TurnSignalValue.LEFT -> -1; TurnSignalValue.RIGHT -> 1; else -> 0 }
            ui.post { onSignal(v) }   // SDK 콜백은 바인더 스레드 → 화면·음성은 메인 스레드에서
        }
        override fun onFailed(e: Exception) = fail(e)
    }

    /** 사람이 공조 온도를 직접 바꾸면 프로필의 선호 온도로 기억 (공기 케어를 한 좌석만) */
    private fun tempListener(a: SeatArea) = object : HvacTemperatureListener {
        override fun onHvacTemperatureUpdated(v: Float?) {
            if (v == null || a != careTempArea()) return
            ui.post {
                // 공기 케어 뒤 10분 안의 변경만: 그 밖의 변경(자동 공조, 다른 앱)은 선호로 보지 않음
                val recent = System.currentTimeMillis() - tempSentAtMs < TEMP_LEARN_MS
                if (recent && isUserTemp(sentTemp[a], v)) { sentTemp[a] = v; updateRider { it.copy(careTempC = learnTemp(v)) } }
            }
        }
        override fun onFailed(e: Exception) {}
    }

    private val sttListener = object : ResultListener {
        override fun onUpdated(text: String, completed: Boolean) { if (completed) ui.post { heard(text) } }
        override fun onError(e: ai.pleos.playground.stt.constant.ErrorType) { ui.post { heard(null) } }
        override fun onUpdatedEpdData(a: Long, b: Long) {}
        override fun onStartedRecognition() {}
        override fun onEndedRecognition() {}
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    // NaviHelper 콜백은 어느 스레드에서 올지 몰라 모두 메인 스레드로 넘김
    private val naviListener = object : NaviHelperEventListener {
        override fun onNavigationStatus(s: NavigationStatus) { ui.post { routeActive = s == NavigationStatus.ROUTE_DRIVE } }
        override fun onRouteEnded() { ui.post { routeActive = false } }
        override fun onRouteCancelled() { ui.post { routeActive = false } }
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) { ui.post { routeActive = true } }
        override fun onRouteStateInfo(info: RouteStateInfo) {
            // isRouting은 경로 계산 중 여부라서 안내 중인지는 naviMode로 판단 (에뮬레이터에서 확인)
            val guiding = info.naviMode == NaviMode.ROUTE_GUIDING || info.naviMode == NaviMode.SIMULATION_GUIDING
            ui.post { routeActive = guiding; pendingRest?.let { sendRest(it) } }
        }
        override fun onTBTInfo(list: List<TBTInfo>) { ui.post { onTbt(list.firstOrNull()) } }
        override fun onError(code: NaviErrorCode) { ui.post { assistNote = "내비가 요청을 처리하지 못했어요 · 케어는 계속할게요"; render() } }
    }

    // 0.5초마다 위험 지수 갱신. 데모 중에는 4배속(0.125초마다)으로 가상 주행을 재생.
    private val tick = object : Runnable {
        override fun run() {
            if (!tripOn) { if (visible) render(); ui.postDelayed(this, 500); return }
            val d = demo
            val t = d?.takeIf { it.hasNext() }?.next()
            if (d != null && t == null) { endTrip(); ui.postDelayed(this, 500); return }
            if (t != null) onSignal(t.signal)
            // 실제 주행에서는 핸들 각도 우선, 데모는 미리 만든 진행 방향 사용
            val r = comfort.update(t?.speed ?: liveSpeed, t?.heading ?: liveHeading, DT, if (t == null) liveSteer else null)
            trace.push(comfort.windowMsdv * comfort.sensitivity)
            if (r >= Risk.CAUTION && risk < Risk.CAUTION && careOn(Care.AIR)) airCare()
            if (r == Risk.HIGH && risk < Risk.HIGH && careOn(Care.BREATH)) breathing()
            risk = r
            // 위험 단계가 3분 이어지면 주행당 한 번 휴게소 제안
            highSinceS = if (r == Risk.HIGH) highSinceS ?: comfort.durationS else null
            if (!autoOffered && highSinceS?.let { comfort.durationS - it >= HIGH_OFFER_S } == true) { autoOffered = true; offerRestArea("high") }
            if (comfort.harshJustNow) coach()
            // 30초마다 진행 중 주행 저장 (앱이 강제로 꺼져도 이어 갈 수 있게)
            val now = System.currentTimeMillis()
            if (now - lastSaveMs >= SAVE_EVERY_MS) { lastSaveMs = now; saveOngoing() }
            if (visible) render()
            ui.postDelayed(this, if (demo != null) 125 else 500)
        }
    }

    // --- 케어 동작 ---

    /** 바람 케어: 외기 유입 + 풍량 올리기 + 온도 살짝 낮추기 (D'Amour 2017) */
    private fun airCare(quiet: Boolean = false) {
        if (!quiet) say("멀미 위험이 올라가고 있어요. 바깥 공기를 넣고 바람을 조금 세게 할게요. 앞쪽 창밖을 바라봐 주세요.")
        caresThisTrip += Care.AIR
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
                val t = careTemp().coerceIn(r)
                ui.postDelayed({
                    sentTemp[a] = t; tempSentAtMs = System.currentTimeMillis()
                    hvac.setHvacTemperature(a, t, TemperatureUnit.CELSIUS, onFailed = ::fail)
                }, i * TEMP_GAP_MS)
            }
        }, 500)
    }

    /** 호흡 가이드: 조명 4초 밝게(들숨) / 4초 어둡게(날숨), 분당 7.5회 (Stromberg 2015) */
    private fun breathing(quiet: Boolean = false) {
        if (!quiet) say("천천히 숨 쉬어 볼게요. 조명이 밝아지면 들이쉬고, 어두워지면 내쉬세요.")
        caresThisTrip += Care.BREATH
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
        val area = careSeatArea()
        caresThisTrip += Care.RECLINE
        seat.moveSeatBackrestAngle(area, MoveControl.BACKWARD,
            onComplete = { _, _ -> reclined = true; ui.post { render() } }, onFailed = ::fail)
        ui.postDelayed({ seat.moveSeatBackrestAngle(area, MoveControl.STOP, onFailed = ::fail) }, RECLINE_MS)
    }

    /** 움직임 예고: 방향지시등이 켜지면 동승자에게 미리 알림 */
    private fun onSignal(s: Int) {
        if (s != 0 && s != lastSignal && !routeActive && careOn(Care.TURN_CUE)) {
            caresThisTrip += Care.TURN_CUE
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

    /** 내비 회전 예고: 좌·우·급회전·유턴만, 같은 회전은 한 번 */
    private fun onTbt(t: TBTInfo?) {
        if (t == null || !careOn(Care.TURN_CUE)) return
        val side = turnSide(t.type.name)
        val key = "${t.type}-${t.description}"
        if (side == 0 || !shouldCueTurn(t.distance, key, lastTbtKey)) return
        lastTbtKey = key
        caresThisTrip += Care.TURN_CUE
        turnDir = side; turnAt = comfort.durationS
        say(turnCueText(side, t.distance))
        render()
    }

    private fun careOn(c: Care) = rider?.cares?.get(c)?.off != true
    private fun careTemp() = rider?.careTempC ?: CARE_TEMP_C
    private fun careSeat() = when (rider?.seat ?: Seat.FRONT_RIGHT) {
        Seat.FRONT_RIGHT -> SeatValue.ROW_1_RIGHT; Seat.REAR_LEFT -> SeatValue.ROW_2_LEFT; Seat.REAR_RIGHT -> SeatValue.ROW_2_RIGHT
    }
    private fun careSeatArea() = SeatArea(careSeat())
    /** 탑승자 자리의 공조 구역. 뒷좌석에 따로 구역이 없으면 동승석 구역을 따름. (구역 하나가 여러 좌석을 묶기도 함) */
    private fun careTempArea() = tempRange.keys.firstOrNull { careSeat() in it.values }
        ?: tempRange.keys.firstOrNull { SeatValue.ROW_1_RIGHT in it.values }

    /** "속이 안 좋아요": 공기 케어 + 호흡 가이드(꺼둔 케어는 건너뜀) + 휴게소 제안 */
    fun sickNow() {
        val air = careOn(Care.AIR); val breath = careOn(Care.BREATH)
        say(when {
            air && breath -> "바로 도와드릴게요. 바깥 공기를 넣고, 조명에 맞춰 천천히 숨 쉬어 볼게요."
            air -> "바로 도와드릴게요. 바깥 공기를 넣을게요. 앞쪽 창밖을 바라봐 주세요."
            breath -> "바로 도와드릴게요. 조명에 맞춰 천천히 숨 쉬어 볼게요."
            else -> "알겠어요. 가까운 휴게소를 찾아볼게요."
        })
        if (air) airCare(quiet = true)
        if (breath) breathing(quiet = true)
        // 첫 안내가 끝난 뒤 휴게소를 물어봄
        ui.postDelayed({ offerRestArea("sick") }, if (air || breath) SICK_ASK_DELAY_MS else 0)
        render()
    }

    /** 가장 가까운 휴게소(직선 50km 안)를 경유지로 제안. 위치·휴게소가 없으면 한 줄 안내만. */
    fun offerRestArea(reason: String) {
        val lat = lastLat; val lon = lastLon
        val hit = if (lat == null || lon == null) null else nearestRestArea(lat, lon, restAreas)
        offer = hit
        assistNote = when {
            lat == null || lon == null -> "아직 위치를 몰라서 휴게소를 찾지 못했어요"
            hit == null -> "50km 안에 휴게소가 없어요 · 케어는 계속할게요"
            else -> null
        }
        if (lat != null && hit == null) say("근처에 휴게소가 없어요. 케어는 계속할게요.")
        if (hit != null) {
            val km = hit.second.roundToInt()
            say((if (reason == "high") "멀미 위험이 계속 높아요. " else "") + "가까운 휴게소는 약 ${km}km 거리의 ${hit.first.name}입니다. 경유지로 추가할까요?")
        }
        render()
    }

    private fun acceptRest(yes: Boolean) {
        val (area, _) = offer ?: return
        offer = null
        if (!yes) { assistNote = null; render(); return }
        if (!naviReady) { assistNote = "내비에 연결하지 못했어요 · 운전자에게 ${area.name} 안내를 부탁해 주세요"; render(); return }
        // 경로 안내 중인지 먼저 묻고(목적지를 덮어쓰지 않도록), 답이 없으면 1.5초 뒤 마지막으로 아는 상태로 진행
        pendingRest = area
        runCatching { navi.getRouteStateInfo() }
        ui.postDelayed({ pendingRest?.let { sendRest(it) } }, 1500)
    }

    /** 안내 중이면 경유지로 추가, 아니면 휴게소까지 편안한 길(COMFORTABLE)로 바로 안내 */
    private fun sendRest(area: RestArea) {
        pendingRest = null
        assistNote = runCatching {
            if (routeActive) {
                navi.addWaypoint(RequestWaypointInfo(area.lat, area.lon, area.name, WaypointIndex.FIRST, "", "", "", ""))
                say("경유지로 추가했어요. ${area.name}에서 쉬어 가요.")
                "경유지 추가 · ${area.name}"
            } else {
                navi.requestRoute(RouteInfo(area.lat, area.lon, null, null, area.name, "", "", "", RouteOption.COMFORTABLE, "", false))
                say("${area.name}까지 편안한 길로 안내할게요.")
                "휴게소 안내 시작 · ${area.name}"
            }
        }.getOrElse { "내비에 연결하지 못했어요 · 운전자에게 ${area.name} 안내를 부탁해 주세요" }
        render()
    }

    /** 마이크: 8초 안에 알아들은 말이 없으면 버튼 안내 */
    private fun listen() {
        if (listening) return
        if (!sttReady) { assistNote = "음성 인식을 쓸 수 없어요 · 버튼을 눌러주세요"; render(); return }
        listening = true
        assistNote = if (offer != null) "듣고 있어요 · \"추가\" 또는 \"괜찮아\"" else "듣고 있어요 · \"속이 안 좋아\"라고 말해 보세요"
        runCatching { stt.request() }.onFailure { heard(null); return }
        ui.postDelayed(sttTimeout, STT_TIMEOUT_MS)
        render()
    }

    private val sttTimeout = Runnable { if (listening) heard(null) }

    private fun heard(text: String?) {
        if (!listening) return
        listening = false
        ui.removeCallbacks(sttTimeout)
        runCatching { stt.stop() }
        val yes = text?.let { answer(it) }
        when {
            text != null && offer != null && yes != null -> acceptRest(yes)
            text != null && isSick(text) -> { assistNote = null; sickNow() }
            else -> { assistNote = "잘 못 들었어요 · 버튼을 눌러주세요"; render() }
        }
    }

    // --- 주행 시작/종료 ---

    private fun startTrip(withDemo: Boolean) {
        comfort = Comfort(comfort.sensitivity)
        risk = Risk.LOW
        demo = if (withDemo) demoDrive(DT).iterator() else null
        tripOn = true; tripEnded = false
        tripStartMs = System.currentTimeMillis()
        resetTripState()
        render()
    }

    /** 주행마다 새로 시작하는 케어·안내 상태 (주행 시작, 리포트 완료, 탑승자 변경 때) */
    private fun resetTripState() {
        airAt = null; airFan = null; turnAt = null; turnDir = 0; coachCount = 0; problem = null; reclined = false
        caresThisTrip.clear(); offer = null; pendingRest = null; assistNote = null
        highSinceS = null; autoOffered = false; lastTbtKey = null
        risk = Risk.LOW
        trace.clear()
    }

    private fun endTrip() {
        if (!tripOn) return
        store.saveOngoing(null)
        tripOn = false; tripEnded = true
        val trip = Trip(tripStartMs, (comfort.durationS / 60).roundToInt(), comfort.score(), comfort.maxRisk,
            null, caresThisTrip.toSet(), emptySet(), demo = demo != null)
        demo = null
        report = ReportState(trip, comfort.harshEvents)
        say("주행이 끝났어요. 편안함 점수는 ${comfort.score()}점이에요. 멀미는 어땠는지 알려주세요.")
        show(Screen.Report)
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
    private val sensBtns = ArrayList<Pair<Double, TextView>>()
    private lateinit var riderNote: TextView
    private lateinit var titleV: TextView
    private lateinit var reclineRow: View
    private lateinit var micBtn: LinearLayout
    private lateinit var restCard: LinearLayout
    private lateinit var restText: TextView
    private lateinit var noteV: TextView

    private fun buildUi(): View {
        sensBtns.clear()
        (trace.parent as? android.view.ViewGroup)?.removeView(trace)
        val g = dp(T.GAP)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.GROUND)
            setPadding(dp(29), dp(20), dp(29), 0)
        }
        // 휴게소 제안·안내·"속이 안 좋아요"는 스크롤 밖 하단에 고정 (항상 보이게)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(29), 0, dp(29), dp(24))
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
                titleV = label(T.TITLE, T.TEXT, 600)
                addView(titleV)
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
            reclineRow = careRow("등받이", reclineSub, reclineBtn)
            each(reclineRow)
            addView(careFoot.apply { setPadding(0, dp(12), 0, 0) })
        }
        // 1행이 창의 남는 높이를 모두 차지해서 바닥에 빈 띠가 생기지 않게
        val wide = columns() > 1
        root.addView(pair(riskCard, careCard, wide), LinearLayout.LayoutParams(-1, if (wide) 0 else -2, 1f))

        // 2행: 이번 주행(2/3) | 탑승자(1/3)
        scoreV = label(T.KPI, T.TEXT, 500); harshV = label(T.KPI, T.TEXT, 500)
        maxV = label(T.KPI, T.TEXT, 500); timeV = label(T.KPI, T.TEXT, 500)
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
            addView(label(T.SMALL, T.MUTED, 400, "주행이 끝나면 리포트에서 멀미를 묻고 다음 주행에 반영해요"))
        }

        riderNote = label(T.SMALL, T.MUTED)
        val sensRow = LinearLayout(this).apply {
            background = fill(T.RAISED, dp(999).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            SENS.forEach { (v, name) ->
                val b = label(T.BODY, T.TEXT, 500, name).apply {
                    gravity = Gravity.CENTER; minHeight = dp(T.TOUCH)
                    setOnClickListener { setSensitivity(v) }
                }
                sensBtns += v to b
                addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val riderCard = card().apply {
            addView(label(T.SECTION, T.TEXT, 500, rider?.name ?: "게스트"))
            addView(label(T.SMALL, T.MUTED, 400, "멀미 민감도").apply { setPadding(0, dp(14), 0, dp(8)) })
            addView(sensRow)
            addView(riderNote.apply { setPadding(0, dp(12), 0, 0) })
            rider?.let { p -> addView(button("기록 · 설정") { show(Screen.ProfileDetail(p.id)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
        }
        root.addView(pair(tripCard, riderCard, wide), LinearLayout.LayoutParams(-1, -2).apply { topMargin = g })

        // 휴게소 제안 카드 (제안 중일 때만)
        restText = label(T.SECTION, T.TEXT, 600)
        restCard = card().apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            addView(row(icon(R.drawable.ic_rest, 32), restText), LinearLayout.LayoutParams(if (wide) 0 else -1, -2, 1f))
            addView(row(button("괜찮아요") { acceptRest(false) }, button("경유지 추가", primary = true, icon = R.drawable.ic_add) { acceptRest(true) }).apply {
                if (!wide) setPadding(0, g, 0, 0)
            })
        }
        bottom.addView(restCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // 맨 아래: 한 줄 안내 + "속이 안 좋아요" + 마이크
        noteV = label(T.BODY, T.TEXT, 500).apply { setPadding(dp(4), 0, 0, 0) }
        bottom.addView(noteV, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val sick = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(T.SICK_H)
            background = pressable(T.p.sickBg, T.CONTROL_R)
            addView(icon(R.drawable.ic_wind, 32, T.p.sickText).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12) })
            addView(label(T.SECTION, T.p.sickText, 700, "속이 안 좋아요"))
            isClickable = true
            setOnClickListener { sickNow() }
        }
        micBtn = button("말하기", icon = R.drawable.ic_mic) { listen() }.apply { minimumHeight = dp(T.SICK_H) }
        bottom.addView(LinearLayout(this).apply {
            addView(sick, LinearLayout.LayoutParams(0, -2, 1f))
            addView(micBtn, LinearLayout.LayoutParams(-2, -1).apply { marginStart = g })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.GROUND)
            addView(ScrollView(context).apply { isFillViewport = true; addView(root) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottom)
        }
    }

    /** 넓으면 2:1 가로 배치, 좁으면(세로 화면) 위아래로 쌓기 */
    private fun pair(a: View, b: View, wide: Boolean) = LinearLayout(this).apply {
        orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (wide) {
            addView(a, LinearLayout.LayoutParams(0, -1, 2f))
            addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(T.GAP) })
        } else {
            addView(a, LinearLayout.LayoutParams(-1, -2))
            addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.GAP) })
        }
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

    private fun ago(s: Double) = (comfort.durationS - s).toInt().coerceAtLeast(0).let { "%d:%02d 전".format(it / 60, it % 60) }

    private fun render() {
        if (screen != Screen.Drive) return
        val idx = comfort.windowMsdv * comfort.sensitivity
        val started = tripOn || tripEnded
        val sensName = SENS.minBy { abs(it.first - comfort.sensitivity) }.second
        scope.text = (rider?.let { "${it.name} · ${seatName(it.seat)}" } ?: "게스트 · 기록 안 남김") + " · 민감도 $sensName"
        backBtn.visibility = if (tripOn) View.GONE else View.VISIBLE
        val seat = rider?.seat ?: Seat.FRONT_RIGHT
        titleV.text = "${seatName(seat)} 멀미 케어"
        restCard.visibility = if (offer != null) View.VISIBLE else View.GONE
        offer?.let { (a, km) -> restText.text = "${a.name} · 약 ${km.roundToInt()}km · 경유지로 추가할까요?" }
        noteV.text = assistNote ?: ""
        noteV.visibility = if (assistNote != null) View.VISIBLE else View.GONE
        micBtn.buttonText = if (listening) "듣는 중" else "말하기"
        // 좌석이 등받이 이동을 지원하지 않으면 줄을 숨김 (지원 목록을 모르면 보여줌)
        reclineRow.visibility = if (reclineAreas?.any { careSeat() in it.values } == false) View.GONE else View.VISIBLE

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
            else -> "외기 · " + (airFan?.let { "풍량 ${it}단 · " } ?: "") + "%.1f°C".format(careTemp())
        }
        airState.text = airAt?.let { ago(it) } ?: "대기"
        val breathLeft = ((breathUntilMs - System.currentTimeMillis()) / 1000).toInt()
        breathBtn.buttonText = if (breathLeft > 0) "%d:%02d 남음".format(breathLeft / 60, breathLeft % 60) else "시작"
        turnState.text = turnAt?.let { (if (turnDir < 0) "왼쪽 · " else "오른쪽 · ") + ago(it) } ?: "—"
        coachState.text = if (started) "${coachCount}회" else "—"
        // 등받이: P단이 아니면 버튼을 흐리게 하고 이유를 보여줌
        val seatLocked = parked == false
        reclineSub.text = if (seatLocked) "주행 중에는 시트를 움직일 수 없어요" else "${seatName(seat)} 등받이를 조금 젖혀요"
        reclineBtn.buttonText = when { reclined -> "젖혔어요"; seatLocked -> "P단에서 가능"; else -> "젖히기" }
        reclineBtn.isEnabled = !seatLocked || reclined
        careFoot.text = problem?.let { "차량 응답: $it" } ?: if (lastSpoken.isEmpty()) "" else "마지막 음성 · $lastSpoken"
        careFoot.setTextColor(if (problem != null) T.NEG else T.FAINT)

        scoreV.text = if (started) "${comfort.score()}" else "—"
        harshV.text = if (started) "${comfort.harshEvents}회" else "—"
        maxV.text = if (started) when (comfort.maxRisk) { Risk.HIGH -> "위험"; Risk.CAUTION -> "주의"; else -> "편안" } else "—"
        val sec = comfort.durationS.toInt()
        timeV.text = if (started) "%d:%02d".format(sec / 60, sec % 60) else "—"

        // 학습으로 0.9처럼 중간 값이 되면 가장 가까운 단계 하나만 표시
        val nearest = sensBtns.minByOrNull { abs(it.first - comfort.sensitivity) }?.first
        sensBtns.forEach { (v, b) ->
            val on = v == nearest
            b.setTextColor(if (on) T.p.primaryText else T.MUTED)
            b.background = if (on) fill(T.p.primaryBg, dp(999).toFloat()) else null
        }
        riderNote.text = rider?.let { p ->
            val e = p.earlierPercent()
            val recent = p.trips.take(5)
            (when { e > 0 -> "경고 $e% 일찍"; e < 0 -> "경고 ${-e}% 늦게"; else -> "경고 시점 기본" }) +
                (if (recent.isEmpty()) " · 첫 주행" else " · 최근 ${recent.size}회 평균 ${recent.map { it.score }.average().roundToInt()}점")
        } ?: "게스트는 기록을 남기지 않아요"
    }

    private fun say(text: String) {
        if (!gate.allow(text, System.currentTimeMillis())) return   // 같은 말 30초 안 반복 금지
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
        runCatching { stt.removeListener(sttListener); stt.release() }
        runCatching { navi.removeListener(naviListener); navi.release() }
        tempListeners.forEach { (a, l) -> runCatching { vehicle.getHVAC().unregisterHvacTemperature(a, l) } }
        car?.disconnect()
        super.onDestroy()
    }

    companion object {
        const val DT = 0.5
        const val CARE_TEMP_C = 21f
        const val TEMP_GAP_MS = 300L
        val SENS = listOf(0.8 to "낮음", 1.0 to "보통", 1.3 to "높음")   // 설문 결과와 같은 세 단계
        const val HIGH_OFFER_S = 180.0      // 위험 단계가 이만큼 이어지면 휴게소 제안
        const val SICK_ASK_DELAY_MS = 6000L // 즉시 케어 안내가 끝난 뒤 휴게소 질문
        const val STT_TIMEOUT_MS = 8000L
        const val SAVE_EVERY_MS = 30_000L
        const val RESUME_MAX_MS = 6 * 3600_000L
        const val TEMP_LEARN_MS = 10 * 60_000L   // 공기 케어 뒤 이 시간 안에 바꾼 온도만 선호로 기억
        const val RECLINE_MS = 1000L   // 등받이 젖히는 시간. 실차에서 몇 도 움직이는지 보고 조정.
    }
}
