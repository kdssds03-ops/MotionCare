package com.motioncare

import android.content.Context
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// 주행 화면을 뺀 나머지 화면. 상태는 MainActivity가 갖고, 여기서는 그리기만 한다.
// 모든 화면이 주행 중 화면과 같은 틀: 머리줄 · 왼쪽 한 마디 · 가운데 그림 · 오른쪽 타일 · 아래 큰 버튼 줄.

sealed interface Screen {
    data object RiderSelect : Screen
    data object CreateProfile : Screen
    data object Drive : Screen
    data object Report : Screen
    data class ProfileDetail(val id: String) : Screen
}

/** 화면 너비(dp)에 맞는 열 수: 세로 화면(1000dp 미만)은 한 줄 */
fun Context.columns(): Int = resources.configuration.screenWidthDp.let { if (it >= 1600) 3 else if (it >= 1000) 2 else 1 }

fun seatName(s: Seat) = when (s) { Seat.FRONT_RIGHT -> "동승석"; Seat.REAR_LEFT -> "뒷좌석 왼쪽"; Seat.REAR_RIGHT -> "뒷좌석 오른쪽" }

/** 아이템을 cols개씩 줄로 배치 (같은 너비, 간격 20) */
fun Context.grid(items: List<View>, cols: Int) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    items.chunked(cols).forEachIndexed { r, rowItems ->
        addView(LinearLayout(context).apply {
            for (i in 0 until cols) {
                val v = rowItems.getOrNull(i) ?: android.widget.Space(context)   // View는 남는 높이를 다 차지함
                addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(20) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = dp(20) })
    }
}

/** 눌러지는 큰 타일: 전체가 하나의 터치 대상 */
fun Context.tapCard(onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(32), dp(32), dp(32), dp(32))
    background = pressable(T.SURFACE, 28)
    isClickable = true
    setOnClickListener { onClick() }
}

// --- 탑승자 선택 ---

fun Context.riderSelect(
    profiles: List<Profile>, cols: Int,
    onPick: (Profile) -> Unit, onDetail: (Profile) -> Unit, onGuest: () -> Unit, onNew: () -> Unit,
): View {
    val wide = cols > 1
    val tall = wide && profiles.size <= 1   // 타일이 한 줄(3개 이하)이면 세로로 길게, 차 그림까지
    val tileH = dp(if (tall) 560 else 300)
    val tiles = profiles.map { p ->
        tapCard { onPick(p) }.apply {
            minimumHeight = tileH
            setOnLongClickListener { onDetail(p); true }
            contentDescription = "${p.name} · ${seatName(p.seat)} · 길게 누르면 기록과 설정"
            addView(label(40f, T.TEXT, 700, p.name))
            addView(label(T.BODY, T.MUTED, 500, seatName(p.seat)).apply { setPadding(0, dp(4), 0, 0) })
            // 그 사람이 앉는 자리를 보여 주는 작은 차 그림
            if (tall) addView(CabinView(context).apply { update(p.seat, false, false, 0L, false) },
                LinearLayout.LayoutParams(dp(150), dp(262)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20); bottomMargin = dp(20) })
            else addView(View(context), LinearLayout.LayoutParams(1, dp(40)))
            val last = p.trips.firstOrNull()
            addView(LinearLayout(context).apply {
                gravity = Gravity.BOTTOM
                if (last != null) {
                    addView(label(T.STATE, T.ACCENT, 700, "${last.score}"))
                    addView(label(T.SMALL, T.MUTED, 500, "최근 편안함\n주행 ${p.trips.size}회").apply { setPadding(dp(14), 0, 0, dp(8)) })
                } else addView(label(T.BODY, T.MUTED, 500, "첫 주행"))
            })
        }
    } + startTile(R.drawable.ic_person, "게스트로 시작", "기록을 남기지 않아요", false, tileH, onGuest) +
        startTile(R.drawable.ic_add, "새 프로필", "자주 타면 경고 시점과\n케어를 맞춰 드려요", true, tileH, onNew)

    val left = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hero(null, "누가\n타셨나요?", "고르면 바로 주행 화면으로 가요"))
        if (profiles.isNotEmpty()) addView(label(T.BODY, T.FAINT, 400, "프로필을 길게 누르면 기록과 설정을 볼 수 있어요").apply { setPadding(0, dp(24), 0, 0) })
    }
    val body = threeCols(left, null, grid(tiles, if (wide) 3 else 1), wide, wl = 0.38f, wr = 1f)
    return frame(header(null, null, headerChip("차량 연결됨")), body, null)
}

/** 게스트·새 프로필 타일: 둥근 아이콘 + 이름 + 설명. 새 프로필은 점선 테두리. */
private fun Context.startTile(iconRes: Int, title: String, sub: String, dashed: Boolean, h: Int, onClick: () -> Unit) = tapCard { onClick() }.apply {
    minimumHeight = h
    if (dashed) background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(28).toFloat(); setStroke(dp(2), T.RULE, dp(10).toFloat(), dp(8).toFloat())
    }
    addView(iconBadge(iconRes, 88, if (dashed) T.ACCENT_BG else T.RAISED, if (dashed) T.ACCENT else T.TEXT), LinearLayout.LayoutParams(dp(88), dp(88)))
    addView(View(context), LinearLayout.LayoutParams(1, h - dp(88 + 64 + 110)))   // 이름이 타일 아래쪽에 오게
    addView(label(40f, T.TEXT, 700, title))
    addView(label(T.BODY, T.MUTED, 500, sub).apply { setPadding(0, dp(8), 0, 0) })
}

// --- 새 프로필 ---

/** 프로필 만들기 입력값 (화면을 다시 그려도 유지) */
class Draft(var name: String = "", var seat: Seat? = null, val answers: IntArray = intArrayOf(-1, -1, -1), var trip: Trip? = null) {
    val valid get() = name.isNotBlank() && seat != null && answers.all { it >= 0 }
    /** 저장 버튼 옆에 보여 줄 남은 일 (다 채웠으면 null) */
    val missing get() = when {
        name.isBlank() -> "이름을 적어 주세요"
        seat == null -> "자리를 골라 주세요"
        answers.any { it < 0 } -> "질문 ${answers.count { it < 0 }}개 남았어요"
        else -> null
    }
}

val SURVEY = listOf("차를 타면 멀미를 하나요?", "차에서 화면이나 책을 보면 어지럽나요?", "뒷좌석에서 더 힘든 편인가요?")
val SEAT_SHORT = listOf(Seat.FRONT_RIGHT to "동승석", Seat.REAR_LEFT to "뒤 왼쪽", Seat.REAR_RIGHT to "뒤 오른쪽")

fun Context.createProfile(d: Draft, keyboardAllowed: Boolean, onChange: () -> Unit, onSave: () -> Unit, onCancel: () -> Unit): View {
    val wide = columns() > 1
    val head = header("뒤로" to onCancel, null, headerChip("정차 중에만 만들 수 있어요"))
    if (!keyboardAllowed) {
        return frame(head, threeCols(hero("새 프로필", "주행 중에는\n만들 수 없어요", "정차한 뒤 다시 눌러 주세요 · 지금은 게스트로 탈 수 있어요"), null, View(this), wide),
            buttonBar(bigButton("돌아가기", 1) { onCancel() } to 0))
    }
    // 1 · 이름
    val left = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hero("새 프로필", "한 번만\n알려 주세요", if (d.trip != null) "방금 주행을 첫 기록으로 저장해요" else "다음부터는 이름만 누르면 돼요"))
        addView(label(T.SECTION, T.TEXT, 600, "1 · 부를 이름").apply { setPadding(0, dp(36), 0, dp(10)) })
        addView(EditText(context).apply {
            setText(d.name); setSelection(d.name.length)
            textSize = T.TITLE; setTextColor(T.TEXT); setHintTextColor(T.FAINT); hint = "예: 지우, 엄마"
            filters = arrayOf(InputFilter.LengthFilter(10))
            isSingleLine = true
            // 가로 화면에서 키보드가 전체 화면을 덮지 않게, 완료 누르면 키보드 닫기
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background = fill(T.SURFACE, dp(T.RADIUS).toFloat(), dp(2), if (d.name.isNotBlank()) T.ACCENT else T.RULE)
            setPadding(dp(24), dp(18), dp(24), dp(18))
            minimumHeight = dp(80)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { d.name = s.toString().trim(); saveBtnState?.invoke() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        addView(label(T.SMALL, T.FAINT, 400, "실명 대신 별칭을 써도 돼요 · 최대 10자").apply { setPadding(0, dp(10), 0, 0) })
    }
    // 2 · 자리: 차 그림에서 고른 자리가 청록
    val center = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(label(T.SECTION, T.TEXT, 600, "2 · 주로 앉는 자리"), LinearLayout.LayoutParams(-1, -2))
        addView(CabinView(context).apply { update(d.seat, false, false, 0L, false) },
            LinearLayout.LayoutParams(dp(220), dp(385)).apply { topMargin = dp(16); bottomMargin = dp(16) })
        addView(segmented(SEAT_SHORT.map { it.second }, SEAT_SHORT.indexOfFirst { it.first == d.seat }) { i -> d.seat = SEAT_SHORT[i].first; onChange() },
            LinearLayout.LayoutParams(-1, -2))
    }
    // 3 · 설문
    val right = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(T.SECTION, T.TEXT, 600, "3 · 멀미 설문 3가지"))
        addView(label(T.SMALL, T.FAINT, 400, "첫 경고 시점을 정하는 데만 써요").apply { setPadding(0, dp(4), 0, 0) })
        SURVEY.forEachIndexed { q, text ->
            addView(label(T.BODY, T.TEXT, 500, text).apply { setPadding(0, dp(24), 0, dp(10)) })
            addView(segmented(listOf("아니요", "가끔", "자주"), d.answers[q]) { v -> d.answers[q] = v; onChange() })
        }
    }
    val save = bigButton("저장하고 시작", 0, R.drawable.ic_check) { if (d.valid) onSave() }
    // 다 채우기 전에는 회색 버튼에 남은 일을 적어 둠 (흐리게 하면 글자가 안 읽힘)
    saveBtnState = {
        save.isEnabled = d.valid
        save.background = if (d.valid) pressable(T.p.primaryBg, 24) else fill(T.RAISED, dp(24).toFloat())
        val ink = if (d.valid) T.p.primaryText else T.MUTED
        save.findViewWithTag<TextView>("label").setTextColor(ink)
        (save.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(ink)
        save.buttonText = d.missing?.let { "저장하고 시작 · $it" } ?: "저장하고 시작"
    }
    saveBtnState?.invoke()
    return frame(head, threeCols(left, center, right, wide, wl = 1.1f, wc = 0.8f, wr = 1.1f),
        buttonBar(bigButton("취소", 1) { onCancel() } to 260, save to 0))
}

// ponytail: 저장 버튼 상태를 글자 입력마다 갱신하려고 둔 전역 콜백. 화면이 하나뿐이라 충분.
private var saveBtnState: (() -> Unit)? = null

// --- 주행 리포트 ---

/** 리포트에서 고르는 값 (화면을 다시 그려도 유지) */
class ReportState(val trip: Trip, val harsh: Int, var misc: Int? = null, val helped: MutableSet<Care> = mutableSetOf()) {
    fun final() = trip.copy(misc = misc, helped = helped.toSet())
}

fun riskName(r: Risk) = when (r) { Risk.HIGH -> "많이 흔들림"; Risk.CAUTION -> "조금 흔들림"; else -> "편안" }

fun Context.kpi(name: String, value: String, ink: Int = T.TEXT) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(label(T.SMALL, T.MUTED, 500, name))
    addView(label(T.KPI, ink, 700, value).apply { setPadding(0, dp(2), 0, 0) })
}

fun Context.report(
    r: ReportState, rider: Profile?, wide: Boolean, canSaveGuest: Boolean,
    onChange: () -> Unit, onDone: () -> Unit, onSaveGuest: () -> Unit,
): View {
    val t = r.trip
    // 왼쪽: 점수 고리 + 한 마디 + 두 수치
    val left = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply { background = fill(T.ACCENT, dp(999).toFloat()) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
            addView(label(T.BODY, T.ACCENT, 600, "주행이 끝났어요"))
        })
        addView(ScoreView(context).apply { set(t.score) }, LinearLayout.LayoutParams(dp(320), dp(300)).apply { topMargin = dp(12) })
        addView(label(T.LEAD, T.TEXT, 700, when (t.maxRisk) {
            Risk.HIGH -> "많이 흔들린\n길이었어요"; Risk.CAUTION -> "조금 흔들린\n길이었어요"; else -> "편안한\n길이었어요"
        }).apply { setPadding(0, dp(12), 0, 0) })
        addView(LinearLayout(context).apply {
            setPadding(0, dp(20), 0, 0)
            addView(kpi("급한 움직임", "${r.harsh}회"))
            addView(kpi("가장 높았던 단계", riskName(t.maxRisk), if (t.maxRisk == Risk.LOW) T.POS else T.WARN),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(36) })
        })
    }
    // 가운데: 이번 주행에서 차가 한 일 (주행 화면과 같은 타일) + 택시 미리보기
    val did = Care.entries.filter { it in t.cares }.map { c ->
        tile(careIcon(c), careName(c), label(T.SMALL, T.ACCENT, 500, careDone(c))).also { tileState(it, true) }
    }.ifEmpty { listOf(tile(R.drawable.ic_check, "케어 없이 도착", label(T.SMALL, T.MUTED, 400, "케어가 필요할 만큼 흔들리지 않았어요"))) }
    val taxi = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = fill(T.SURFACE, dp(T.RADIUS).toFloat())
        setPadding(dp(24), dp(18), dp(24), dp(18))
        addView(label(T.SMALL, T.FAINT, 500, "택시 연동 미리보기 · 예시"))
        addView(label(T.BODY, T.TEXT, 500, "편안함 ${t.score} · 급한 움직임 ${r.harsh}회 · ${t.minutes}분 주행").apply { setPadding(0, dp(6), 0, dp(4)) })
        addView(label(T.SMALL, T.MUTED, 400, "기사님이 공유를 켜면 전송돼요"))
    }
    val center = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(tileList("이번 주행에서 차가 한 일", did))
        addView(taxi, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
    }
    // 오른쪽: 멀미 응답 0~10 큰 숫자 버튼 + 도움이 된 케어
    val right = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(36f, T.TEXT, 700, "멀미는 어땠나요?"))
        (0..10).chunked(4).forEach { rowVals ->
            addView(LinearLayout(context).apply {
                for (m in 0 until 4) {
                    val v = rowVals.getOrNull(m)
                    val on = v != null && r.misc == v
                    val cell: View = if (v == null) android.widget.Space(context) else label(T.TITLE, if (on) T.p.primaryText else T.TEXT, if (on) 700 else 600, "$v").apply {
                        gravity = Gravity.CENTER; minHeight = dp(80)
                        background = pressable(if (on) T.p.primaryBg else T.RAISED, 20)
                        isClickable = true; setOnClickListener { r.misc = v; onChange() }
                        contentDescription = "멀미 $v" + if (on) " · 선택됨" else ""
                    }
                    addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (m > 0) marginStart = dp(12) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        addView(LinearLayout(context).apply {
            setPadding(0, dp(10), 0, 0)
            addView(label(T.SMALL, T.MUTED, 400, "0 괜찮음"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(T.SMALL, T.MUTED, 400, "10 토할 것 같음"))
        })
        if (t.cares.isNotEmpty()) {
            addView(label(T.BODY, T.MUTED, 500, "도움이 된 케어 (선택)").apply { setPadding(0, dp(24), 0, dp(10)) })
            addView(LinearLayout(context).apply {
                Care.entries.filter { it in t.cares }.forEachIndexed { i, c ->
                    val on = c in r.helped
                    addView(label(T.BODY, if (on) T.ACCENT else T.TEXT, if (on) 600 else 400, careName(c)).apply {
                        gravity = Gravity.CENTER; minHeight = dp(T.TOUCH); setPadding(dp(24), 0, dp(24), 0)
                        background = pressable(if (on) T.ACCENT_BG else T.RAISED, 999)
                        isClickable = true; setOnClickListener { if (!r.helped.remove(c)) r.helped += c; onChange() }
                        contentDescription = careName(c) + if (on) " · 선택됨" else ""
                    }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(12) })
                }
            })
        }
        val line = when {
            !worthKeeping(t) -> "1분 미만 주행은 기록하지 않아요"
            rider != null -> learningLine(rider, rider.learn(r.final()))
            else -> "알려 주시면 다음 경고 시점을 맞춰요 · 게스트는 기록이 남지 않아요"
        }
        addView(label(T.SMALL, T.FAINT, 400, line).apply { setPadding(0, dp(20), 0, 0) })
    }
    val status = label(T.SECTION, T.MUTED, 400, "주행 리포트 · ${seatName(rider?.seat ?: Seat.FRONT_RIGHT)} · ${t.minutes}분" + if (t.demo) " · 데모 주행" else "")
    val done = bigButton(if (rider != null) "저장하고 완료" else "완료", 0, R.drawable.ic_check) { onDone() }
    val footer = if (rider == null && canSaveGuest && worthKeeping(t))
        buttonBar(done to 0, bigButton("프로필로 저장", 1, R.drawable.ic_person) { onSaveGuest() } to 380)
    else buttonBar(done to 0)
    return frame(header(null, status, headerChip(rider?.name ?: "게스트 · 기록 안 남김")),
        threeCols(left, center, right, wide, wl = 0.85f, wc = 1f, wr = 1.05f), footer)
}

fun careIcon(c: Care) = when (c) { Care.AIR -> R.drawable.ic_wind; Care.BREATH -> R.drawable.ic_breath; Care.TURN_CUE -> R.drawable.ic_turn; Care.RECLINE -> R.drawable.ic_seat }
fun careDone(c: Care) = when (c) { Care.AIR -> "바깥 공기를 넣고 바람을 세게 했어요"; Care.BREATH -> "조명과 소리로 숨 쉬는 박자를 알려 줬어요"; Care.TURN_CUE -> "돌기 전에 미리 알려 줬어요"; Care.RECLINE -> "등받이를 조금 젖혔어요" }

// --- 프로필 상세 ---

/** 켜기/끄기 스위치 (96×56) */
fun Context.switch(on: Boolean, desc: String, onToggle: () -> Unit) = android.widget.FrameLayout(this).apply {
    background = fill(if (on) T.ACCENT else T.RAISED, dp(999).toFloat())
    addView(View(context).apply { background = fill(if (on) T.GROUND else T.FAINT, dp(999).toFloat()) },
        android.widget.FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply { marginStart = dp(6); marginEnd = dp(6) })
    layoutParams = LinearLayout.LayoutParams(dp(96), dp(56))
    isClickable = true; setOnClickListener { onToggle() }
    contentDescription = desc + if (on) " · 켜짐" else " · 꺼짐"
}

fun Context.profileDetail(
    p: Profile, wide: Boolean,
    onBack: () -> Unit, onStart: () -> Unit, onToggle: (Care, Boolean) -> Unit, onTemp: (Float) -> Unit, onDelete: () -> Unit,
): View {
    val e = p.earlierPercent()
    // 왼쪽: 이름 · 경고 시점 · 공기 케어 온도
    val left = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(T.STATE, T.TEXT, 700, p.name))
        addView(label(T.SECTION, T.MUTED, 400, "${seatName(p.seat)} · 주행 ${p.trips.size}회").apply { setPadding(0, dp(6), 0, 0) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = fill(T.SURFACE, dp(24).toFloat())
            setPadding(dp(28), dp(22), dp(28), dp(22))
            addView(label(T.BODY, T.MUTED, 500, "경고 시점"))
            addView(label(T.LEAD, if (e != 0) T.ACCENT else T.TEXT, 700, when { e > 0 -> "$e% 일찍"; e < 0 -> "${-e}% 늦게"; else -> "기본" }))
            addView(label(T.SMALL, T.FAINT, 400, "설문과 주행 후 멀미 응답으로\n자동으로 맞춰져요").apply { setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) })
        addView(label(T.BODY, T.MUTED, 500, "공기 케어 온도").apply { setPadding(0, dp(28), 0, dp(10)) })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(bigButton("−", 1) { onTemp(p.careTempC - 0.5f) }.apply { contentDescription = "온도 낮추기" }, LinearLayout.LayoutParams(dp(88), -2))
            addView(label(T.LEAD, T.TEXT, 700, "%.1f°C".format(p.careTempC)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(bigButton("+", 1) { onTemp(p.careTempC + 0.5f) }.apply { contentDescription = "온도 올리기" }, LinearLayout.LayoutParams(dp(88), -2))
        })
        addView(label(T.SMALL, T.FAINT, 400, "케어 중에 온도를 직접 바꾸면 그 값을 기억해요").apply { setPadding(0, dp(10), 0, 0) })
    }
    // 가운데: 최근 점수 막대 + 멀미 응답 점
    val center = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(T.SECTION, T.TEXT, 600, "최근 편안함 점수"), LinearLayout.LayoutParams(0, -2, 1f))
            listOf(Triple(T.ACCENT, 4, "점수"), Triple(T.WARN, 8, "멀미 응답")).forEach { (color, r, name) ->
                addView(View(context).apply { background = fill(color, dp(r).toFloat()) },
                    LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(T.GAP); marginEnd = dp(8) })
                addView(label(T.SMALL, T.MUTED, 500, name))
            }
        })
        addView(TrendView(context, p.trips.take(10).reversed()), LinearLayout.LayoutParams(-1, dp(380)).apply { topMargin = dp(16) })
    }
    // 오른쪽: 케어별 효과 + 켜기/끄기
    val tiles = Care.entries.map { c ->
        val s = p.cares[c] ?: CareStat()
        val on = !s.off
        val sub = label(T.SMALL, if (on) T.MUTED else T.WARN, 400, when {
            !on && s.runs > 0 -> "도움이 적어 꺼졌어요 · ${s.helped}/${s.runs}회"
            !on -> "꺼짐"
            s.runs == 0 -> "아직 기록 없음"
            else -> "도움 ${s.helped}/${s.runs}회"
        })
        tile(careIcon(c), careName(c), sub, right = switch(on, careName(c)) { onToggle(c, !on) }).also {
            it.findViewWithTag<android.widget.ImageView>("icon").setColorFilter(if (on) T.ACCENT else T.FAINT)
        }
    }
    val right = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(T.SECTION, T.TEXT, 600, "케어별 효과").apply { setPadding(dp(4), 0, 0, 0) })
        addView(label(T.SMALL, T.FAINT, 400, "멀미했던 주행에서 3번 넘게 도움이 적으면 꺼져요").apply { setPadding(dp(4), dp(4), 0, 0) })
        addView(tileList(null, tiles), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }
    val delete = bigButton("프로필 삭제", 1) { onDelete() }.apply { findViewWithTag<TextView>("label").setTextColor(T.NEG) }
    return frame(header("탑승자" to onBack, null, headerChip("기록 · 설정")),
        threeCols(left, center, right, wide, wl = 0.8f, wc = 1f, wr = 1f),
        buttonBar(delete to 260, bigButton("이 프로필로 시작", 0, R.drawable.ic_check) { onStart() } to 0))
}
