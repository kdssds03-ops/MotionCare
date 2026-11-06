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
import android.media.AudioManager
import android.media.ToneGenerator
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
    private var curSpeed = 0.0   // 이번 틱에 쓴 속도(데모면 가상 속도), 회전 예고 시간 계산용
    private var demo: Iterator<DemoTick>? = null
    private var demoCourse: DemoCourse? = null
    private var tripOn = false
    private var tripEnded = false
    private var tripStartMs = 0L
    private var report: ReportState? = null
    private var lastSignal = 0
    private val coachQ = CoachQueue()
    private val autoTrip = AutoTrip()

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
    private var breathStartMs = 0L
    private var breathStoppedByUser = false   // 직접 멈추면 흔들림이 가라앉을 때까지 자동 시작 안 함
    private var tone: ToneGenerator? = null   // 호흡 박자·회전 예고용 짧은 소리
    private var turnAt: Double? = null
    private var turnDir = 0
    private var coachCount = 0
    private var lastSpoken = ""
    private var problem: String? = null
    private var parked: Boolean? = null   // 기어 P 여부, 모르면 null
    private var reclined = false
    private var reclineReserved = false   // 주행 중 예약, 정차(P)하면 젖힘
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
    private val observedTemp = HashMap<SeatArea, Float>()   // 차가 알려 준 좌석별 현재 설정 온도
    // 이번 주행에서 처음 공기 케어를 켤 때의 온도. 케어가 여러 번 켜져도 이 값 기준으로만 낮춤(계속 내려가지 않게)
    private val airBaseTemp = HashMap<SeatArea, Float>()
    private val tempListeners = HashMap<SeatArea, HvacTemperatureListener>()
    // 케어 전 차량 상태: 주행이 끝나거나 호흡 가이드가 멈추면 되돌림
    private val origRecirc = HashMap<SeatArea, Boolean>()
    private val origAuto = HashMap<SeatArea, Boolean>()
    private val origFan = HashMap<SeatArea, Int>()
    private val origAmbient = HashMap<SeatArea, Int>()
    private var lastTbtDist: Double? = null
    private var ambientTouched = false
    private var tempSentAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.p = Palette.of(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        profiles = store.load()
        show(Screen.RiderSelect)
        offerResume()

        vehicle = Vehicle(this).apply { runCatching { initialize() }.onFailure { fail(Exception("차량에 연결할 수 없어요")) } }

        tts = TextToSpeech(applicationContext, Mode.ON_DEVICE)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() { tts.setLocale(Locale.KOREA); ttsReady = true }
            override fun onFailure(error: ErrorType) = fail(Exception("음성 안내를 켤 수 없어요 ($error)"))
        })

        location = FusedLocation(this)
        stt = SpeechToText(applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
        navi = NaviHelper(this)
        // 매니페스트의 권한을 모두 요청: adb install -g 없이 Android Studio로 설치해도 공조·조명·시트 제어가 되게
        val perms = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
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
        if (rider != null && worthKeeping(t)) updateRider { it.learn(t) }
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

    /**
     * 차량 기능 확인과 신호 구독: 권한을 받은 뒤에. 하나가 실패해도 나머지는 계속.
     * (Pleos가 아닌 기기나 권한 거부에서도 앱이 꺼지지 않고 데모 주행은 가능)
     */
    private fun startVehicle() {
        val hvac = runCatching { vehicle.getHVAC() }.getOrNull()
        fun step(what: () -> Unit) = runCatching(what).onFailure { fail(it as? Exception ?: Exception(it)) }
        step { hvac!!.checkHvacPowerCapabilities({ _, list -> powerAreas = list }, ::fail) }
        step { hvac!!.checkHvacRecirculationCapabilities({ _, list -> recircAreas = list }, ::fail) }
        step { hvac!!.checkHvacFanSpeedCapabilities({ _, map -> fanMax = map.mapValues { it.value.maxValue } }, ::fail) }
        step {
            hvac!!.checkHvacTemperatureCapabilities({ _, map: Map<SeatArea, AreaBounds<Float>> ->
                tempRange = map.mapValues { it.value.minValue..it.value.maxValue }
                tempRange.keys.filter { it !in tempListeners }.forEach { a ->
                    tempListeners[a] = tempListener(a).also { hvac.registerHvacTemperature(a, it) }
                }
            }, ::fail)
        }
        step { vehicle.getSeat().checkMoveSeatBackrestAngleCapabilities({ _, list -> reclineAreas = list; ui.post { render() } }, { reclineAreas = null }) }
        step {
            vehicle.getLight().checkAmbientLightBrightnessCapabilities({ _, map ->
                ambient = map.mapValues { it.value.minValue..it.value.maxValue }
            }, ::fail)
        }
        step { vehicle.getTurnSignal().registerTurnSignalState(signalListener) }
        step { vehicle.getSteeringWheel().registerSteeringWheelAngle(steerListener) }
        step { vehicle.getGear().getCurrentGearSelectionState({ gearListener.onSelected(it) }, ::fail) }
        step { vehicle.getGear().registerGearSelection(gearListener) }
    }

    // 권한이 거부돼도 앱은 꺼지지 않고 데모 주행은 가능하도록 실패만 기록
    private fun startSensors() {
        startVehicle()
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
                    override fun onChangeEvent(v: CarPropertyValue<*>) {
                        val s = (v.value as? Number)?.toDouble() ?: return   // 값이 비거나 다른 형식이면 무시 (콜백 안 크래시 방지)
                        liveSpeed = abs(s)
                    }
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
            val wasParked = parked
            parked = gear?.let { it == VehicleGear.GEAR_PARK }
            if (parked == true && problem?.startsWith("주행 중") == true) problem = null
            val autoRecline = reclineOnPark(wasParked, parked, reclineReserved, reclined)
            ui.post {
                if (autoRecline && reclineReserved) {   // 그사이 취소됐으면 움직이지 않음
                    reclineReserved = false
                    say("정차했어요. 예약한 대로 등받이를 조금 젖힐게요")
                    recline()
                }
                if (screen == Screen.CreateProfile && was != keyboardAllowed) show(screen) else render()
            }
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
            if (v == null) return
            ui.post { observedTemp[a] = v }
            if (a != careTempArea()) return
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
            if (demo == null) autoStartEnd()   // 데모는 가상 주행이라 실제 차 상태로 시작·종료하지 않음
            if (!tripOn) { if (visible) render(); ui.postDelayed(this, 500); return }
            val d = demo
            val t = d?.takeIf { it.hasNext() }?.next()
            if (d != null && t == null) { endTrip(); ui.postDelayed(this, 500); return }
            if (t != null) onSignal(t.signal)
            // 실제 주행에서는 핸들 각도 우선, 데모는 미리 만든 진행 방향 사용
            curSpeed = t?.speed ?: liveSpeed
            val r = comfort.update(curSpeed, t?.heading ?: liveHeading, DT, if (t == null) liveSteer else null)
            trace.push(comfort.windowMsdv * comfort.sensitivity)
            route.add(curSpeed, t?.heading ?: liveHeading, DT, r, comfort.windowMsdv * comfort.sensitivity)
            if (r >= Risk.CAUTION && risk < Risk.CAUTION && careOn(Care.AIR) && autoAirAgain(airAt, comfort.durationS)) airCare()
            breathStoppedByUser = keepUserStop(r, breathStoppedByUser)
            if (autoStartBreath(r, risk, breathStoppedByUser) && careOn(Care.BREATH)) breathing()
            // 흔들림이 이어지는 동안 호흡 가이드 연장 (직접 멈춘 경우는 그대로)
            breathUntilMs = breathUntil(breathStartMs, breathUntilMs, System.currentTimeMillis(), r)
            risk = r
            // 위험 단계가 3분 이어지면 주행당 한 번 휴게소 제안
            highSinceS = if (r == Risk.HIGH) highSinceS ?: comfort.durationS else null
            if (!autoOffered && highSinceS?.let { comfort.durationS - it >= HIGH_OFFER_S } == true) { autoOffered = true; offerRestArea("high") }
            if (comfort.harshJustNow) coachQ.onHarsh()
            coach()
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
        if (!quiet) say("흔들림이 커지고 있어요. 바깥 공기를 넣고 바람을 조금 세게, 온도는 살짝 낮출게요. 앞쪽 창밖을 바라봐 주세요.")
        caresThisTrip += Care.AIR
        airAt = comfort.durationS
        trace.mark("공기 케어")
        if (tripOn) route.mark(Care.AIR)
        val hvac = vehicle.getHVAC()
        // 풍량·온도는 탑승자 자리 구역만 (운전석에 찬바람이 가지 않게). 외기 유입은 차 전체 공기라 모든 구역.
        val fans = mine(fanMax.keys)
        // 케어 전 상태를 주행당 한 번 기억해 두었다가 주행이 끝나면 되돌림 (restoreAir)
        recircAreas.filter { it !in origRecirc }.forEach { a ->
            hvac.getHvacRecirculationState(a, { v -> if (v != null) ui.post { origRecirc.putIfAbsent(a, v) } }, { _ -> })
        }
        fans.filter { it !in origAuto }.forEach { a ->
            hvac.getHvacAutoState(a, { v -> if (v != null) ui.post { origAuto.putIfAbsent(a, v) } }, { _ -> })
            hvac.getHvacFanSpeed(a, { v -> if (v != null) ui.post { origFan.putIfAbsent(a, v) } }, { _ -> })
        }
        powerAreas.forEach { hvac.setHvacPower(it, true, onFailed = ::fail) }
        // ponytail: 전원 켜짐 콜백을 기다리지 않고 0.5초 뒤 설정. 실차에서 실패가 보이면 onComplete로 연결.
        ui.postDelayed({
            recircAreas.forEach { hvac.setHvacRecirculation(it, false, onFailed = ::fail) }
            // 자동 공조 중에는 풍량을 직접 못 바꾸므로 자동을 끈 뒤 설정
            fans.forEach { a ->
                val level = (fanMax.getValue(a) * 2 + 2) / 3
                hvac.setHvacAuto(a, false,
                    onComplete = { _, _ -> hvac.setHvacFanSpeed(a, level, onComplete = { _, _ -> airFan = level }, onFailed = ::fail) },
                    onFailed = ::fail)
            }
            careTempArea()?.let { a -> lowerTemp(a, tempRange.getValue(a)) }
        }, 500)
    }

    /** 주행이 끝나면 공기 케어 전 상태로: 내·외기, 자동 공조(또는 원래 풍량), 온도. 사람이 직접 바꾼 온도는 그대로 둔다. */
    private fun restoreAir() {
        if (airAt == null) return
        val hvac = runCatching { vehicle.getHVAC() }.getOrNull() ?: return
        origRecirc.forEach { (a, v) -> hvac.setHvacRecirculation(a, v, onFailed = ::fail) }
        origAuto.forEach { (a, auto) ->
            if (auto) hvac.setHvacAuto(a, true, onFailed = ::fail)
            else origFan[a]?.let { hvac.setHvacFanSpeed(a, it, onFailed = ::fail) }
        }
        airBaseTemp.forEach { (a, t) ->
            restoreTemp(t, sentTemp[a], observedTemp[a])?.let {
                sentTemp[a] = it   // 앱이 되돌린 값을 선호 온도로 배우지 않게
                hvac.setHvacTemperature(a, it, TemperatureUnit.CELSIUS, onFailed = ::fail)
            }
        }
        origRecirc.clear(); origAuto.clear(); origFan.clear()
    }

    /** 탑승자 자리를 포함한 구역 (없으면 동승석, 차 전체가 한 구역이면 그 구역) */
    private fun <A : SeatArea> mine(areas: Collection<A>) = careZones(areas, { it.values.toList() }, careSeat(), SeatValue.ROW_1_RIGHT)

    /**
     * 공기 케어 온도 한 좌석: 이번 주행 기준 온도보다 1~2°C 낮게.
     * 현재 설정을 아직 모르면 차에 물어보고, 그래도 모르면 온도는 그대로 둔다(데우지 않게).
     */
    private fun lowerTemp(a: SeatArea, r: ClosedFloatingPointRange<Float>) {
        val hvac = vehicle.getHVAC()
        fun set(base: Float?) {
            val t = careTarget(base, careTemp())?.coerceIn(r) ?: return
            sentTemp[a] = t; tempSentAtMs = System.currentTimeMillis()
            hvac.setHvacTemperature(a, t, TemperatureUnit.CELSIUS, onFailed = ::fail)
        }
        val known = airBaseTemp[a] ?: observedTemp[a]?.also { airBaseTemp[a] = it }
        if (known != null) return set(known)
        hvac.getHvacTemperature(a, TemperatureUnit.CELSIUS,
            { v -> ui.post { if (v != null) { observedTemp[a] = v; airBaseTemp[a] = v }; set(v) } },
            { _ -> })
    }

    /**
     * 호흡 가이드: 4초 들숨 / 4초 날숨, 분당 7.5회 (Stromberg 2015).
     * 최소 5분, 흔들림이 이어지면 최대 15분까지 연장(tick). 박자는 소리로 알려서 화면 대신 창밖을 볼 수 있게 함.
     */
    private fun breathing(quiet: Boolean = false) {
        // 이미 진행 중이면 다시 시작하지 않음: 박자 일정이 겹치지 않게
        if (breathActive()) return
        if (!quiet) say("천천히 숨 쉬어 볼게요. 삐 소리가 나면 들이쉬고, 다음 소리에 내쉬세요. 눈은 앞쪽 창밖에 두세요.")
        caresThisTrip += Care.BREATH
        val now = System.currentTimeMillis()
        breathStartMs = now
        breathUntilMs = now + BREATH_MIN_MS
        if (tripOn) { trace.mark("호흡 가이드"); route.mark(Care.BREATH) }
        breathStoppedByUser = false
        ui.removeCallbacksAndMessages(BREATH_TOKEN)   // 이전 박자 예약이 남아 있으면 겹치지 않게
        ui.postDelayed(breathStep, BREATH_TOKEN, 0)
        render()
    }

    /** 박자 한 번: 조명 밝기 + 짧은 소리, 다음 박자 경계에 다시 예약. 끝나면 조명을 중간으로. */
    private val breathStep = object : Runnable {
        override fun run() {
            if (!breathActive()) { setAmbient(null); return }
            val e = System.currentTimeMillis() - breathStartMs
            val inhale = isInhale(e)
            setAmbient(inhale)
            beep(if (inhale) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, 150)
            ui.postDelayed(this, BREATH_TOKEN, BREATH_PHASE_MS - e % BREATH_PHASE_MS)
        }
    }

    private fun beep(type: Int, ms: Int) {
        runCatching {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
            tone?.startTone(type, ms)
        }
    }

    private fun breathActive() = breathUntilMs > System.currentTimeMillis()

    /** 호흡 가이드 멈추기: 남은 밝기 일정을 지우고 조명을 중간 밝기로 */
    private fun stopBreathing() {
        ui.removeCallbacksAndMessages(BREATH_TOKEN)
        breathUntilMs = 0L
        setAmbient(null)
        render()
    }

    private fun toggleBreathing() = if (breathActive()) { stopBreathing(); breathStoppedByUser = true } else breathing()

    /**
     * 앰비언트 밝기, 탑승자 자리 구역만 (운전석 쪽 조명은 깜빡이지 않게).
     * true = 최대(들숨), false = 최소(날숨), null = 호흡 전 밝기로 되돌림 (모르면 중간)
     */
    private fun setAmbient(bright: Boolean?) {
        if (bright == null && !ambientTouched) return   // 호흡 가이드를 켠 적 없으면 조명을 건드리지 않음
        ambientTouched = bright != null
        val light = runCatching { vehicle.getLight() }.getOrNull() ?: return
        mine(ambient.keys).forEach { area ->
            val range = ambient.getValue(area)
            if (bright != null && area !in origAmbient)
                light.getAmbientLightBrightness(area, { v -> if (v != null) ui.post { origAmbient.putIfAbsent(area, v) } }, { _ -> })
            val v = when (bright) { true -> range.last; false -> range.first; null -> origAmbient.remove(area) ?: ((range.first + range.last) / 2) }
            light.setAmbientLightBrightness(area, v, { _, _ -> }, ::fail)
        }
    }

    /** 등받이 판단용 P단 여부. 데모 주행은 가상으로 달리는 중이라 실제 기어와 상관없이 '주행 중'으로 본다. */
    private fun seatParked(): Boolean? = if (demo != null) false else parked

    private fun reclineText(seatLocked: Boolean) = when {
        reclined -> "젖혔어요"
        reclineReserved -> "예약됨 · 정차(P)하면 젖혀요 · 누르면 취소"
        seatLocked -> "주행 중 · 눌러서 예약하면 정차(P) 때 젖혀요"
        else -> "정차(P) 중 · 눌러서 조금 젖혀요"
    }

    /** 등받이 버튼: P단이면 바로 젖히고, 주행 중이면 예약/취소 */
    private fun onReclineTap() {
        when (reclineTap(seatParked(), reclined, reclineReserved)) {
            ReclineTap.NOW -> recline()
            ReclineTap.RESERVE -> { reclineReserved = true; say("예약했어요. 정차하면 등받이를 조금 젖힐게요") }
            ReclineTap.CANCEL -> { reclineReserved = false; say("등받이 예약을 취소했어요") }
            ReclineTap.NONE -> {}
        }
        render()
    }

    /**
     * 동승석 등받이 살짝 젖히기 (TUM 연구). 동승자가 버튼으로 직접 실행하거나, 주행 중 예약해 두면 정차(P) 때 실행.
     * Pleos는 P단이 아니면 시트 이동을 막음(오류 50001, 속도 0이어도 D면 거부) → P단에서만 시도.
     */
    private fun recline() {
        if (parked == false) { render(); return }
        val seat = vehicle.getSeat()
        val area = careSeatArea()
        caresThisTrip += Care.RECLINE
        if (tripOn) route.mark(Care.RECLINE)
        seat.moveSeatBackrestAngle(area, MoveControl.BACKWARD,
            onComplete = { _, _ -> reclined = true; ui.post { render() } }, onFailed = ::fail)
        ui.postDelayed({ seat.moveSeatBackrestAngle(area, MoveControl.STOP, onFailed = ::fail) }, RECLINE_MS)
    }

    /** 움직임 예고: 방향지시등이 켜지면 동승자에게 미리 알림 */
    private fun onSignal(s: Int) {
        if (s != 0 && s != lastSignal && !routeActive && careOn(Care.TURN_CUE)) {
            caresThisTrip += Care.TURN_CUE
            cue(turnCueText(s))
            turnDir = s; turnAt = comfort.durationS
        }
        lastSignal = s
    }

    /** 운전자 코칭: 주행 중 급조작은 모아 두었다가 정차했을 때 한 번 음성으로 */
    private fun coach() {
        val n = coachQ.poll(curSpeed)
        if (n == 0) return
        coachCount += n
        say("아까 흔들림이 있었어요. 출발과 제동을 조금 더 천천히 하면 동승자가 편해요.")
    }

    /** 내비 회전 예고: 좌·우·급회전·유턴만, 같은 회전은 한 번 */
    private fun onTbt(t: TBTInfo?) {
        if (t == null || !careOn(Care.TURN_CUE)) return
        val side = turnSide(t.type.name)
        val key = "${t.type}-${t.description}"
        lastTbtKey = nextTurnKey(lastTbtKey, key, lastTbtDist, t.distance.toDouble())   // 같은 이름 회전이 이어져도 다시 예고
        lastTbtDist = t.distance.toDouble()
        if (side == 0 || !shouldCueTurn(t.distance.toDouble(), curSpeed, key, lastTbtKey)) return
        lastTbtKey = key
        caresThisTrip += Care.TURN_CUE
        turnDir = side; turnAt = comfort.durationS
        cue(turnCueText(side))
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
            air && breath -> "바로 도와드릴게요. 바깥 공기를 넣고, 소리에 맞춰 천천히 숨 쉬어 볼게요."
            air -> "바로 도와드릴게요. 바깥 공기를 넣을게요. 앞쪽 창밖을 바라봐 주세요."
            breath -> "바로 도와드릴게요. 소리에 맞춰 천천히 숨 쉬어 볼게요."
            else -> "알겠어요. 가까운 휴게소를 찾아볼게요."
        })
        // 1분 안에 이미 공기 케어를 했으면 공조 명령을 다시 보내지 않음 (반복 터치 대비)
        if (air && airAt?.let { comfort.durationS - it < 60 } != true) airCare(quiet = true)
        if (breath) breathing(quiet = true)
        // 첫 안내가 끝난 뒤 휴게소를 물어봄
        ui.removeCallbacks(askRest)
        ui.postDelayed(askRest, if (air || breath) SICK_ASK_DELAY_MS else 0)
        render()
    }

    // 주행이 끝나면 취소: 리포트 화면에서 카드 없이 질문만 들리지 않게
    private val askRest = Runnable { offerRestArea("sick") }

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
            say((if (reason == "high") "많이 흔들리는 상태가 계속되고 있어요. " else "") + "가까운 휴게소는 약 ${km}km 거리의 ${hit.first.name}입니다. 경유지로 추가할까요?")
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

    /** 데모 코스 고르기: 운전 방식에 따라 점수가 어떻게 달라지는지 보여 줌 (앱과 같은 타일로 만든 창) */
    private fun pickDemoCourse() {
        val dlg = android.app.Dialog(this)
        val icons = mapOf(DemoCourse.SMOOTH to R.drawable.ic_check, DemoCourse.WINDING to R.drawable.ic_turn, DemoCourse.CITY to R.drawable.ic_rest)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fill(T.GROUND, dp(28).toFloat())
            setPadding(dp(32), dp(28), dp(32), dp(28))
            addView(label(T.TITLE, T.TEXT, 700, "데모 코스를 고르세요"))
            addView(label(T.SMALL, T.MUTED, 400, "같은 계산식으로 운전 방식만 달라요 · 매번 조금씩 다르게 달려요").apply { setPadding(0, dp(6), 0, dp(6)) })
            addView(tileList(null, DemoCourse.entries.map { c ->
                tile(icons.getValue(c), c.title, label(T.SMALL, T.MUTED, 400, c.sub), { dlg.dismiss(); startTrip(true, c) })
            }), LinearLayout.LayoutParams(-1, -2))
            addView(bigButton("취소", 1) { dlg.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        }
        dlg.setContentView(box)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dlg.window?.setLayout(dp(720), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    /**
     * 버튼 없이: 탑승자를 고른 주행 화면에서 차가 출발하면 시작, P단에 3분 서 있으면 종료.
     * 리포트를 보는 중(tripEnded)에는 새 주행을 시작하지 않음.
     */
    private fun autoStartEnd() {
        when (autoTrip.update(DT, liveSpeed, parked, tripOn)) {
            AutoTripAction.START -> if (!tripOn && !tripEnded && screen == Screen.Drive) {
                startTrip(false)
                say("출발했네요. 멀미 케어를 시작할게요")
            }
            AutoTripAction.END -> if (tripOn) endTrip()
            AutoTripAction.NONE -> {}
        }
    }

    private fun startTrip(withDemo: Boolean, course: DemoCourse = DemoCourse.CITY) {
        comfort = Comfort(comfort.sensitivity)
        risk = Risk.LOW
        // 데모도 매번 조금씩 다르게 (실제 주행처럼). 코스 성격은 그대로.
        demo = if (withDemo) demoDrive(DT, course, kotlin.random.Random(System.nanoTime())).iterator() else null
        demoCourse = if (withDemo) course else null
        tripOn = true; tripEnded = false
        tripStartMs = System.currentTimeMillis()
        resetTripState()
        route = RouteTrace()
        render()
    }

    /** 주행마다 새로 시작하는 케어·안내 상태 (주행 시작, 리포트 완료, 탑승자 변경 때) */
    private fun resetTripState() {
        airAt = null; airFan = null; airBaseTemp.clear(); coachQ.clear(); breathStoppedByUser = false; turnAt = null; turnDir = 0; coachCount = 0; problem = null; reclined = false; reclineReserved = false
        caresThisTrip.clear(); offer = null; pendingRest = null; assistNote = null
        ui.removeCallbacks(askRest)
        highSinceS = null; autoOffered = false; lastTbtKey = null; lastTbtDist = null
        origRecirc.clear(); origAuto.clear(); origFan.clear()
        risk = Risk.LOW
        trace.clear()
    }

    private fun endTrip() {
        if (!tripOn) return
        store.saveOngoing(null)
        ui.removeCallbacks(askRest); offer = null
        tripOn = false; tripEnded = true
        val trip = Trip(tripStartMs, tripMinutes(comfort.durationS), comfort.score(), comfort.maxRisk,
            null, caresThisTrip.toSet(), emptySet(), demo = demo != null)
        demo = null
        report = ReportState(trip, comfort.harshEvents, route)
        // 주행이 끝날 때 차가 서 있으면: 주행 중 쌓인 운전자 코칭을 종료 안내에 붙여 한 번에 (달리는 중이면 말하지 않음)
        stopBreathing()   // 리포트 화면에서 박자 소리가 계속 나지 않게 (조명도 원래 밝기로)
        restoreAir()
        // 데모 주행 중 예약한 등받이: 가상 주행이 끝나면 실제 차는 서 있으므로 이때 젖힘
        if (trip.demo && reclineReserved && parked != false) { reclineReserved = false; recline() }
        // 데모는 가상 주행이라 실제 차는 서 있음 → 실제 속도 기준은 실주행에만
        val harsh = coachQ.poll(if (trip.demo) 0.0 else liveSpeed)
        coachCount += harsh
        val tip = if (harsh > 0) " 출발과 제동을 조금 더 천천히 하면 동승자가 더 편해요." else ""
        say("주행이 끝났어요. 편안함 점수는 ${comfort.score()}점이에요.$tip 멀미는 어땠는지 알려주세요.")
        show(Screen.Report)
    }

    private fun setSensitivity(s: Double) {
        comfort.sensitivity = s
        updateRider { it.copy(sensitivity = s) }
        render()
    }

    // --- 화면 ---
    // 주행 전·주행 중 모두 같은 틀: 머리줄 · 왼쪽 한 마디 · 가운데 그림 · 오른쪽 타일 · 아래 큰 버튼 줄.
    // 주행 중에는 그래프·수치 대신 상태 한 마디와 차 그림만: 화면을 오래 보면 멀미가 심해짐.

    // 그래프 뷰는 화면에 보이지 않지만, 최근 3분 값으로 "나아지고 있어요"를 판단하고 케어 시점을 표시함
    private val trace by lazy { TraceView(this) }
    /** 이번 주행에서 지나온 길 (리포트에 그림). 앱이 강제로 꺼졌다 이어지면 이어진 뒤부터 */
    private var route = RouteTrace()
    private lateinit var statusV: TextView
    private lateinit var scope: TextView
    private var backBtn: View? = null
    private lateinit var endBtn: LinearLayout
    private lateinit var preView: View
    private lateinit var tripView: View
    // 주행 전
    private lateinit var gauge: GaugeView
    private lateinit var sensBox: LinearLayout
    private lateinit var riderNote: TextView
    private var detailBtn: View? = null
    private lateinit var preFoot: TextView
    private lateinit var preAir: LinearLayout
    private lateinit var preAirSub: TextView
    private lateinit var preBreath: LinearLayout
    private lateinit var preBreathSub: TextView
    private lateinit var preRecline: LinearLayout
    private lateinit var preReclineSub: TextView
    // 주행 중
    private lateinit var stateTag: TextView
    private lateinit var stateDot: View
    private lateinit var stateWord: TextView
    private lateinit var stateCare: TextView
    private lateinit var stateFoot: TextView
    private lateinit var chipValue: TextView
    private lateinit var chipName: TextView
    private lateinit var cabin: CabinView
    private lateinit var breathView: BreathView
    private lateinit var airTile: LinearLayout
    private lateinit var airTileSub: TextView
    private lateinit var breathTile: LinearLayout
    private lateinit var breathTileSub: TextView
    private lateinit var reclineTile: LinearLayout
    private lateinit var reclineTileSub: TextView
    private lateinit var turnTile: LinearLayout
    private lateinit var turnTileSub: TextView
    // 아래
    private lateinit var restCard: LinearLayout
    private lateinit var restText: TextView
    private lateinit var noteV: TextView
    private lateinit var startBtn: LinearLayout
    private lateinit var demoBtn: LinearLayout
    private lateinit var sickBtn: LinearLayout
    private lateinit var micBtn: LinearLayout
    private var sickWide: Boolean? = null   // 주행 중이면 "속이 안 좋아요"가 아래 줄을 넓게 차지

    private fun buildUi(): View {
        (trace.parent as? android.view.ViewGroup)?.removeView(trace)
        val wide = columns() > 1
        val seat = rider?.seat ?: Seat.FRONT_RIGHT
        sickWide = null

        statusV = label(T.SECTION, T.MUTED, 400)
        scope = headerChip("")
        // 주행 종료는 길게 눌러야 함: 흔들리는 차에서 잘못 누르거나 시작 직후 두 번 눌러도 끝나지 않게
        endBtn = button("길게 눌러 주행 종료") {
            assistNote = HOLD_NOTE; render()
            ui.postDelayed({ if (assistNote == HOLD_NOTE) { assistNote = null; render() } }, 4000)
        }.apply {
            setOnLongClickListener { if (tripOn) { if (assistNote == HOLD_NOTE) assistNote = null; endTrip() }; true }
            contentDescription = "주행 종료. 길게 누르세요"
        }
        val head = header("탑승자" to { onBackPressed() }, statusV, scope, endBtn)
        backBtn = head.findViewWithTag("back")

        // --- 주행 전: 준비 한 마디 + 민감도 | 대기 게이지 | 차가 알아서 하는 일 ---
        sensBox = LinearLayout(this)
        riderNote = label(T.SMALL, T.FAINT)
        preFoot = label(T.SMALL, T.FAINT).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        val preLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hero("${seatName(seat)} · 차량 연결됨", "출발할\n준비가 됐어요", "흔들림이 커지면 차가 먼저 챙겨요"))
            addView(label(T.BODY, T.MUTED, 500, "멀미 민감도").apply { setPadding(0, dp(36), 0, dp(10)) })
            addView(sensBox, LinearLayout.LayoutParams(-1, -2))
            addView(riderNote.apply { setPadding(0, dp(10), 0, 0) })
            // 주행 중에는 숨김: 상세 화면의 시작·삭제가 진행 중 주행을 초기화하거나 게스트로 바꿀 수 있음
            detailBtn = rider?.let { p -> button("기록 · 설정") { show(Screen.ProfileDetail(p.id)) }.also {
                addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(T.GAP) })
            } }
            addView(preFoot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.GAP) })
        }
        gauge = GaugeView(this)
        val preCenter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(gauge, LinearLayout.LayoutParams(-1, dp(420)))
            addView(label(T.SMALL, T.FAINT, 400, "ISO 2631-1 기준 · 주행하면 바로 재기 시작해요").apply { gravity = Gravity.CENTER })
        }
        preAirSub = label(T.SMALL, T.MUTED); preBreathSub = label(T.SMALL, T.MUTED); preReclineSub = label(T.SMALL, T.MUTED)
        preAir = tile(R.drawable.ic_wind, "공기 케어", preAirSub)
        preBreath = tile(R.drawable.ic_breath, "호흡 가이드", preBreathSub, { toggleBreathing() })
        val preTurn = tile(R.drawable.ic_turn, "회전 예고", label(T.SMALL, T.MUTED, 400, "돌기 전에 음성으로 알려요"))
        preRecline = tile(R.drawable.ic_seat, "등받이 젖히기", preReclineSub, { onReclineTap() })
        preView = threeCols(preLeft, preCenter, tileList("차가 알아서 하는 일", listOf(preAir, preBreath, preTurn, preRecline)), wide)

        // --- 주행 중: 상태 한 마디 | 차 실내 그림(호흡 가이드 중에는 호흡 원) | 케어 타일 ---
        stateDot = View(this).apply { background = fill(T.ACCENT, dp(999).toFloat()) }
        stateTag = label(T.BODY, T.ACCENT, 600)
        stateWord = label(T.STATE, T.TEXT, 700).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        stateCare = label(T.SECTION, T.TEXT, 500).apply { setLineSpacing(0f, 1.2f) }
        stateFoot = label(T.SMALL, T.FAINT).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        chipValue = label(T.LEAD, T.WARN, 700)
        chipName = label(T.SECTION, T.WARN, 600)
        val tripLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(stateDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
                addView(stateTag)
            })
            addView(stateWord.apply { setPadding(0, dp(10), 0, 0) })
            addView(stateCare.apply { setPadding(0, dp(14), 0, 0) })
            addView(label(T.BODY, T.MUTED, 400, "화면보다 앞쪽 창밖을 보면 덜 어지러워요").apply { setPadding(0, dp(10), 0, 0) })
            // 흔들림 지수: 숫자 + 단계 이름. 판을 따로 깔지 않고 글자만
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(chipValue)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), 0, 0, 0)
                    addView(chipName)
                    addView(label(T.SMALL, T.MUTED, 400, "흔들림 지수 · 10부터 호흡 가이드"))
                })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(36) })
            addView(stateFoot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        cabin = CabinView(this)
        breathView = BreathView(this)
        val tripCenter = android.widget.FrameLayout(this).apply {
            addView(cabin, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(breathView, android.widget.FrameLayout.LayoutParams(-1, -1))
            minimumHeight = dp(440)
        }
        airTileSub = label(T.SMALL, T.MUTED); breathTileSub = label(T.SMALL, T.MUTED); turnTileSub = label(T.SMALL, T.MUTED)
        airTile = tile(R.drawable.ic_wind, "공기 케어", airTileSub)
        breathTile = tile(R.drawable.ic_breath, "호흡 가이드", breathTileSub, { toggleBreathing() })
        reclineTileSub = label(T.SMALL, T.MUTED)
        reclineTile = tile(R.drawable.ic_seat, "등받이 젖히기", reclineTileSub, { onReclineTap() })
        turnTile = tile(R.drawable.ic_turn, "회전 예고", turnTileSub)
        tripView = threeCols(tripLeft, tripCenter, tileList(null, listOf(airTile, breathTile, reclineTile, turnTile)), wide)

        val body = android.widget.FrameLayout(this).apply {
            addView(preView, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(tripView, android.widget.FrameLayout.LayoutParams(-1, -1))
        }

        // --- 아래: 휴게소 제안 · 한 줄 안내 · 큰 버튼 줄 (스크롤 밖에 고정, 항상 보이게) ---
        val g = dp(T.GAP)
        restText = label(T.SECTION, T.TEXT, 600)
        restCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = fill(T.SURFACE, dp(T.RADIUS).toFloat())
            setPadding(dp(T.PAD), dp(T.PAD - 4), dp(T.PAD), dp(T.PAD - 4))
            addView(row(icon(R.drawable.ic_rest, 32), restText), LinearLayout.LayoutParams(if (wide) 0 else -1, -2, 1f))
            addView(row(button("괜찮아요") { acceptRest(false) }, button("경유지 추가", primary = true, icon = R.drawable.ic_add) { acceptRest(true) }).apply {
                if (!wide) setPadding(0, g, 0, 0)
            })
        }
        noteV = label(T.BODY, T.TEXT, 500).apply { setPadding(dp(4), 0, 0, 0) }
        startBtn = bigButton("주행 시작", 0) { if (!tripOn) startTrip(false) }
        demoBtn = bigButton("데모 주행", 1) { if (!tripOn) pickDemoCourse() }
        sickBtn = bigButton("속이 안 좋아요", 2, R.drawable.ic_wind) { sickNow() }
        micBtn = bigButton("말하기", 1, R.drawable.ic_mic) { listen() }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(restCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(noteV, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(buttonBar(startBtn to 0, demoBtn to 260, sickBtn to 300, micBtn to 220))
        }
        return frame(head, body, footer)
    }

    private fun ago(s: Double) = (comfort.durationS - s).toInt().coerceAtLeast(0).let { "%d:%02d 전".format(it / 60, it % 60) }

    private fun render() {
        if (screen != Screen.Drive) return
        val idx = comfort.windowMsdv * comfort.sensitivity
        val started = tripOn || tripEnded
        val sensName = SENS.minBy { abs(it.first - comfort.sensitivity) }.second
        val seat = rider?.seat ?: Seat.FRONT_RIGHT
        scope.text = (rider?.name ?: "게스트 · 기록 안 남김") + " · 민감도 $sensName"
        backBtn?.visibility = if (tripOn) View.GONE else View.VISIBLE
        endBtn.visibility = if (tripOn) View.VISIBLE else View.GONE
        detailBtn?.visibility = if (tripOn) View.GONE else View.VISIBLE
        restCard.visibility = if (offer != null) View.VISIBLE else View.GONE
        offer?.let { (a, km) -> restText.text = "${a.name} · 약 ${km.roundToInt()}km · 경유지로 추가할까요?" }
        noteV.text = assistNote ?: ""
        noteV.visibility = if (assistNote != null) View.VISIBLE else View.GONE
        micBtn.buttonText = if (listening) "듣는 중" else "말하기"
        // 좌석이 등받이 이동을 지원하지 않으면 숨김 (지원 목록을 모르면 보여줌)
        val reclineSupported = reclineAreas?.any { careSeat() in it.values } != false
        val sec = comfort.durationS.toInt()
        statusV.text = when {
            tripEnded -> "주행 종료 · 편안함 ${comfort.score()}점"
            !tripOn -> "${seatName(seat)} · 출발하면 자동 시작"
            else -> "${seatName(seat)} · 주행 중 %d:%02d".format(sec / 60, sec % 60) + (demoCourse?.let { " · 데모: ${it.title}" } ?: "")
        }
        preView.visibility = if (tripOn) View.GONE else View.VISIBLE
        tripView.visibility = if (tripOn) View.VISIBLE else View.GONE
        startBtn.visibility = if (tripOn) View.GONE else View.VISIBLE
        demoBtn.visibility = if (tripOn) View.GONE else View.VISIBLE
        if (sickWide != tripOn) {   // 주행 중에는 "속이 안 좋아요"가 넓게
            sickWide = tripOn
            sickBtn.layoutParams = LinearLayout.LayoutParams(if (tripOn) 0 else dp(300), -2, if (tripOn) 1f else 0f).apply { marginStart = if (tripOn) 0 else dp(T.GAP) }
            // 주행 전에는 "주행 시작"이 주인공: 속이 안 좋아요는 옅은 주황으로 한 단계 낮춤
            val (bg, ink) = if (tripOn) T.p.sickBg to T.p.sickText else T.WARN_BG to T.WARN
            sickBtn.background = pressable(bg, 24)
            sickBtn.findViewWithTag<TextView>("label").setTextColor(ink)
            (sickBtn.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(ink)
        }
        val breathLeft = ((breathUntilMs - System.currentTimeMillis()) / 1000).toInt()
        val foot = problem?.let { "차량 응답: $it" } ?: if (lastSpoken.isEmpty()) "" else "마지막 음성 · $lastSpoken"
        val footInk = if (problem != null) T.NEG else T.FAINT
        val seatLocked = seatParked() == false   // 등받이는 P단에서만 움직임

        // --- 주행 전 ---
        gauge.set(if (started) idx else null, if (started) riskName(risk) else "대기",
            when { !started -> T.MUTED; risk == Risk.LOW -> T.POS; else -> T.WARN })
        tileState(preAir, airAt != null)
        preAirSub.text = airAt?.let { "켜짐 · ${ago(it)}" } ?: "흔들림 6부터 바깥 공기를 넣어요"
        tileState(preBreath, breathLeft > 0)
        preBreathSub.text = if (breathLeft > 0) "진행 중 · %d:%02d · 누르면 멈춰요".format(breathLeft / 60, breathLeft % 60) else "10부터 자동 · 눌러서 지금 시작"
        preBreathSub.setTextColor(if (breathLeft > 0) T.ACCENT else T.MUTED)
        preRecline.visibility = if (reclineSupported) View.VISIBLE else View.GONE
        preReclineSub.text = reclineText(seatLocked)
        tileState(preRecline, reclineReserved)
        preRecline.isEnabled = !reclined
        preRecline.alpha = if (preRecline.isEnabled) 1f else 0.5f
        preFoot.text = foot; preFoot.setTextColor(footInk)
        preFoot.visibility = if (foot.isEmpty()) View.GONE else View.VISIBLE
        // 학습으로 0.9처럼 중간 값이 되면 가장 가까운 단계 하나만 표시
        val nearest = SENS.indexOfFirst { it.first == SENS.minBy { s -> abs(s.first - comfort.sensitivity) }.first }
        if (sensBox.tag != nearest) {
            sensBox.tag = nearest
            sensBox.removeAllViews()
            sensBox.addView(segmented(SENS.map { it.second }, nearest) { i -> setSensitivity(SENS[i].first) }, LinearLayout.LayoutParams(-1, -2))
        }
        riderNote.text = rider?.let { p ->
            val e = p.earlierPercent()
            val recent = p.trips.take(5)
            (when { e > 0 -> "경고 $e% 일찍"; e < 0 -> "경고 ${-e}% 늦게"; else -> "경고 시점 기본" }) +
                (if (recent.isEmpty()) " · 첫 주행" else " · 최근 ${recent.size}회 평균 ${recent.map { it.score }.average().roundToInt()}점")
        } ?: "게스트는 기록을 남기지 않아요"

        // --- 주행 중 ---
        // 20초(40샘플) 전보다 확실히 낮으면 회복 중. 호흡 중에는 호흡 안내가 먼저
        val falling = trace.count > 40 && isEasing(idx, trace.values[(trace.count - 41) % TraceView.CAP].toDouble(), risk)
        stateWord.text = when {
            breathLeft > 0 -> "천천히 숨 쉬어요"
            falling -> "나아지고 있어요"
            risk == Risk.HIGH -> "케어 중이에요"
            risk == Risk.CAUTION -> "조금 흔들려요"
            else -> "편안해요"
        }
        val caring = airAt != null || breathLeft > 0
        stateTag.text = if (caring) "차가 케어하는 중" else "흔들림을 지켜보는 중"
        stateTag.setTextColor(if (caring) T.ACCENT else T.MUTED)
        stateDot.background = fill(if (caring) T.ACCENT else T.FAINT, dp(999).toFloat())
        stateCare.text = when {
            breathLeft > 0 -> "소리가 나면 들이쉬고\n다음 소리에 내쉬세요"
            airAt != null -> "바깥 공기를 넣고 바람을 세게 했어요"
            else -> "흔들림이 커지면 차가 먼저\n바깥 공기를 넣어요"
        }
        val warnInk = if (risk == Risk.LOW) T.POS else T.WARN   // 빨강 대신 주황: 멀미 중인 사람을 놀라게 하지 않게
        chipValue.text = "%.1f".format(idx); chipValue.setTextColor(warnInk)
        chipName.text = riskName(risk); chipName.setTextColor(warnInk)

        // 가운데: 호흡 가이드 중에는 호흡 원, 아니면 차 실내 그림
        val breathStart = breathStartMs
        cabin.update(seat, airAt != null, breathLeft > 0, breathStart, reclined)
        breathView.visibility = if (breathLeft > 0) View.VISIBLE else View.GONE
        cabin.visibility = if (breathLeft > 0) View.GONE else View.VISIBLE
        if (breathLeft > 0 && (breathView.startMs != breathStart || breathView.untilMs != breathUntilMs)) { breathView.startMs = breathStart; breathView.untilMs = breathUntilMs; breathView.invalidate() }

        tileState(airTile, airAt != null)
        airTileSub.text = if (airAt != null) "켜짐 · 바깥 공기" + (airFan?.let { " · 풍량 ${it}단" } ?: "") else "대기 · 흔들리면 바깥 공기를 넣어요"
        airTileSub.setTextColor(if (airAt != null) T.ACCENT else T.MUTED)
        tileState(breathTile, breathLeft > 0)
        breathTileSub.text = if (breathLeft > 0) "진행 중 · %d:%02d · 누르면 멈춰요".format(breathLeft / 60, breathLeft % 60) else "눌러서 시작"
        breathTileSub.setTextColor(if (breathLeft > 0) T.ACCENT else T.MUTED)
        // 등받이는 P단에서만 움직임: 정차(P)면 바로, 주행 중이면 예약해 두고 정차하면 젖힘
        reclineTile.visibility = if (!reclined && reclineSupported) View.VISIBLE else View.GONE
        tileState(reclineTile, reclineReserved)
        reclineTileSub.text = reclineText(seatLocked)
        reclineTileSub.setTextColor(if (reclineReserved) T.ACCENT else T.MUTED)
        val turnRecent = turnAt?.let { comfort.durationS - it < 15 } == true
        tileState(turnTile, turnRecent)
        turnTileSub.text = if (turnRecent) (if (turnDir < 0) "곧 왼쪽으로 돌아요" else "곧 오른쪽으로 돌아요") else "대기 · 회전 전에 음성으로 알려요"
        turnTileSub.setTextColor(if (turnRecent) T.ACCENT else T.MUTED)
        stateFoot.text = foot; stateFoot.setTextColor(footInk)
        stateFoot.visibility = if (foot.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 움직임 예고: 짧은 소리 + 한 마디. 회전마다 같은 말이 나와야 해서 30초 반복 금지(gate)를 거치지 않음 */
    private fun cue(text: String) {
        beep(ToneGenerator.TONE_PROP_BEEP2, 120)
        android.util.Log.i("MotionCare", "cue: $text")
        lastSpoken = text
        if (ttsReady) tts.speak(text, true)
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
        // 리스너 해제를 release보다 먼저, 하나가 실패해도 나머지는 계속 (등록 안 된 상태에서도 안전)
        fun quietly(what: () -> Unit) { runCatching(what) }
        quietly { tone?.release() }
        quietly { vehicle.getTurnSignal().unregisterTurnSignalState(signalListener) }
        quietly { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerListener) }
        quietly { vehicle.getGear().unregisterGearSelection(gearListener) }
        tempListeners.forEach { (a, l) -> quietly { vehicle.getHVAC().unregisterHvacTemperature(a, l) } }
        quietly { if (location.isSupported()) location.unregisterFusedLocationCallback(locationListener) }
        quietly { stt.removeListener(sttListener) }
        quietly { navi.removeListener(naviListener) }
        quietly { vehicle.release() }
        quietly { location.release() }
        quietly { tts.release() }
        quietly { stt.release() }
        quietly { navi.release() }
        quietly { car?.disconnect() }
        super.onDestroy()
    }

    companion object {
        const val DT = 0.5
        const val CARE_TEMP_C = 21f
        const val TEMP_GAP_MS = 300L
        val SENS = listOf(0.8 to "낮음", 1.0 to "보통", 1.3 to "높음")   // 설문 결과와 같은 세 단계
        const val HIGH_OFFER_S = 180.0      // 위험 단계가 이만큼 이어지면 휴게소 제안
        const val HOLD_NOTE = "주행 종료 버튼을 길게 누르면 주행이 끝나요"
        val BREATH_TOKEN = Any()   // 호흡 가이드 밝기 일정 묶음 (멈출 때 한 번에 지움)
        const val SICK_ASK_DELAY_MS = 6000L // 즉시 케어 안내가 끝난 뒤 휴게소 질문
        const val STT_TIMEOUT_MS = 8000L
        const val SAVE_EVERY_MS = 30_000L
        const val RESUME_MAX_MS = 6 * 3600_000L
        const val TEMP_LEARN_MS = 10 * 60_000L   // 공기 케어 뒤 이 시간 안에 바꾼 온도만 선호로 기억
        const val RECLINE_MS = 1000L   // 등받이 젖히는 시간. 실차에서 몇 도 움직이는지 보고 조정.
    }
}
