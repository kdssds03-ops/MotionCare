package com.motioncare

import android.content.Context
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// 주행 화면을 뺀 나머지 화면. 상태는 MainActivity가 갖고, 여기서는 그리기만 한다.

sealed interface Screen {
    data object RiderSelect : Screen
    data object CreateProfile : Screen
    data object Drive : Screen
    data object Report : Screen
    data class ProfileDetail(val id: String) : Screen
}

/** 화면 너비(dp)에 맞는 카드 열 수: 세로 화면(1000dp 미만)은 한 줄 */
fun Context.columns(): Int = resources.configuration.screenWidthDp.let { if (it >= 1600) 3 else if (it >= 1000) 2 else 1 }

fun seatName(s: Seat) = when (s) { Seat.FRONT_RIGHT -> "동승석"; Seat.REAR_LEFT -> "뒷좌석 왼쪽"; Seat.REAR_RIGHT -> "뒷좌석 오른쪽" }

/** 화면 공통 틀: 제품 마크 + 이름, 뒤로 버튼(선택), 제목, 부제, 본문 */
fun Context.page(title: String, sub: String?, onBack: (() -> Unit)?, body: View): View {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(T.EDGE), dp(20), dp(T.EDGE), dp(T.EDGE))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (onBack != null) addView(button("뒤로", icon = R.drawable.ic_back) { onBack() }.apply { minimumWidth = 0 },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(T.GAP) })
            addView(MarkView(context), LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(label(T.SECTION, T.TEXT, 600, "멀미케어").apply { setPadding(dp(12), 0, 0, 0) })
        })
        addView(label(T.TITLE, T.TEXT, 600, title).apply { setPadding(0, dp(24), 0, 0) })
        if (sub != null) addView(label(T.BODY, T.MUTED, 400, sub).apply { setPadding(0, dp(6), 0, dp(8)) })
        addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.GAP) })
    }
    return ScrollView(this).apply { setBackgroundColor(T.GROUND); isFillViewport = true; addView(col) }
}

/** 아이템을 cols개씩 줄로 배치 (같은 너비, 간격 GAP) */
fun Context.grid(items: List<View>, cols: Int) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    items.chunked(cols).forEachIndexed { r, rowItems ->
        addView(LinearLayout(context).apply {
            for (i in 0 until cols) {
                val v = rowItems.getOrNull(i) ?: android.widget.Space(context)   // View는 남는 높이를 다 차지함
                addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(T.GAP) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = dp(T.GAP) })
    }
}

/** 눌러지는 카드: 전체가 하나의 터치 대상 */
fun Context.tapCard(onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    minimumHeight = dp(120)
    setPadding(dp(T.PAD), dp(T.PAD), dp(T.PAD), dp(T.PAD))
    background = pressable(T.SURFACE, T.RADIUS, T.RULE)
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.avatar(text: String, filled: Boolean = true) = label(T.SECTION, T.TEXT, 600, text).apply {
    gravity = Gravity.CENTER
    background = fill(if (filled) T.RAISED else T.SURFACE, dp(28).toFloat())
    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
}

fun Context.riderSelect(
    profiles: List<Profile>, cols: Int,
    onPick: (Profile) -> Unit, onDetail: (Profile) -> Unit, onGuest: () -> Unit, onNew: () -> Unit,
): View {
    val cards = profiles.map { p ->
        tapCard { onPick(p) }.apply {
            setOnLongClickListener { onDetail(p); true }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(avatar(p.name.take(1)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(T.GAP), 0, 0, 0)
                    addView(label(T.SECTION, T.TEXT, 600, p.name))
                    addView(label(T.SMALL, T.MUTED, 500, seatName(p.seat)))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val last = p.trips.firstOrNull()
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    if (last != null) {
                        addView(label(T.KPI, T.TEXT, 700, "${last.score}"))
                        addView(label(T.SMALL, T.MUTED, 500, "최근 편안함"))
                    } else addView(label(T.SMALL, T.MUTED, 500, "첫 주행"))
                })
            })
            addView(button("기록 · 설정") { onDetail(p) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.GAP) })
        }
    } + tapCard { onGuest() }.apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(row(icon(R.drawable.ic_person, 32), label(T.SECTION, T.TEXT, 600, "게스트로 시작")))
        addView(label(T.SMALL, T.MUTED, 500, "기록을 남기지 않아요").apply { setPadding(0, dp(8), 0, 0) })
    } + tapCard { onNew() }.apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(row(icon(R.drawable.ic_add, 32), label(T.SECTION, T.TEXT, 600, "새 프로필")))
        addView(label(T.SMALL, T.MUTED, 500, "자주 타면 경고 시점과 케어를 맞춰 드려요").apply { setPadding(0, dp(8), 0, 0) })
    }
    return page("누가 타셨나요?", "탑승자를 고르면 바로 주행 화면으로 가요", null, grid(cards, cols))
}

/** 프로필 만들기 입력값 (화면을 다시 그려도 유지) */
class Draft(var name: String = "", var seat: Seat? = null, val answers: IntArray = intArrayOf(-1, -1, -1), var trip: Trip? = null) {
    val valid get() = name.isNotBlank() && seat != null && answers.all { it >= 0 }
}

val SURVEY = listOf("차를 타면 멀미를 하나요?", "차에서 화면이나 책을 보면 어지럽나요?", "뒷좌석에서 더 힘든 편인가요?")

fun Context.createProfile(d: Draft, keyboardAllowed: Boolean, onChange: () -> Unit, onSave: () -> Unit, onCancel: () -> Unit): View {
    if (!keyboardAllowed) {
        return page("새 프로필", null, onCancel, card().apply {
            addView(label(T.SECTION, T.TEXT, 600, "주행 중에는 프로필을 만들 수 없어요"))
            addView(label(T.BODY, T.MUTED, 400, "정차한 뒤 다시 눌러주세요. 지금은 게스트로 탈 수 있어요.").apply { setPadding(0, dp(8), 0, 0) })
        })
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(card().apply {
            addView(label(T.SECTION, T.TEXT, 600, "부를 이름"))
            addView(EditText(context).apply {
                setText(d.name); setSelection(d.name.length)
                textSize = T.SECTION; setTextColor(T.TEXT); setHintTextColor(T.FAINT); hint = "예: 지우, 엄마"
                filters = arrayOf(InputFilter.LengthFilter(10))
                isSingleLine = true
                // 가로 화면에서 키보드가 전체 화면을 덮지 않게, 완료 누르면 키보드 닫기
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                background = fill(T.RAISED, dp(T.CONTROL_R).toFloat())
                setPadding(dp(20), dp(16), dp(20), dp(16))
                minimumHeight = dp(T.PRIMARY_H)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { d.name = s.toString().trim(); saveBtnState?.invoke() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(label(T.SMALL, T.MUTED, 500, "실명 대신 별칭을 써도 돼요 · 최대 10자").apply { setPadding(0, dp(8), 0, 0) })
        })
        addView(card().apply {
            addView(label(T.SECTION, T.TEXT, 600, "주로 앉는 자리"))
            addView(chipRow(Seat.entries.map { s -> chip(seatName(s), d.seat == s) { d.seat = s; onChange() } }))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.GAP) })
        addView(card().apply {
            addView(label(T.SECTION, T.TEXT, 600, "멀미 설문 3가지"))
            addView(label(T.SMALL, T.MUTED, 500, "첫 경고 시점을 정하는 데만 써요").apply { setPadding(0, dp(4), 0, 0) })
            SURVEY.forEachIndexed { q, text ->
                addView(label(T.BODY, T.TEXT, 500, "${q + 1}. $text").apply { setPadding(0, dp(20), 0, 0) })
                addView(chipRow(listOf("아니요", "가끔", "자주").mapIndexed { v, n -> chip(n, d.answers[q] == v) { d.answers[q] = v; onChange() } }))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.GAP) })
        val save = button("저장하고 시작", primary = true) { if (d.valid) onSave() }
        saveBtnState = { save.isEnabled = d.valid; save.alpha = if (d.valid) 1f else 0.5f }
        saveBtnState?.invoke()
        addView(LinearLayout(context).apply {
            addView(button("취소") { onCancel() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(save, LinearLayout.LayoutParams(0, -2, 2f).apply { marginStart = dp(T.GAP) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.GAP * 2) })
    }
    val sub = if (d.trip != null) "방금 주행을 첫 기록으로 저장해요" else "한 번만 입력하면 다음부터는 이름만 누르면 돼요"
    return page("새 프로필", sub, onCancel, body)
}

// ponytail: 저장 버튼 활성 상태를 글자 입력마다 갱신하려고 둔 전역 콜백. 화면이 하나뿐이라 충분.
private var saveBtnState: (() -> Unit)? = null

fun Context.chipRow(chips: List<View>) = LinearLayout(this).apply {
    chips.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(T.GAP) }) }
    setPadding(0, dp(12), 0, 0)
}

fun TextView.muted() = apply { setTextColor(T.MUTED) }

// --- 주행 리포트 ---

/** 리포트에서 고르는 값 (화면을 다시 그려도 유지) */
class ReportState(val trip: Trip, val harsh: Int, var misc: Int? = null, val helped: MutableSet<Care> = mutableSetOf()) {
    fun final() = trip.copy(misc = misc, helped = helped.toSet())
}

fun riskName(r: Risk) = when (r) { Risk.HIGH -> "많이 흔들림"; Risk.CAUTION -> "조금 흔들림"; else -> "편안" }

/** 넓으면 a:b 가로, 좁으면 위아래 */
fun Context.columnsOf(a: View, b: View, wide: Boolean, wa: Float = 1f, wb: Float = 1f) = LinearLayout(this).apply {
    orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    if (wide) {
        addView(a, LinearLayout.LayoutParams(0, -2, wa))
        addView(b, LinearLayout.LayoutParams(0, -2, wb).apply { marginStart = dp(T.GAP) })
    } else {
        addView(a, LinearLayout.LayoutParams(-1, -2))
        addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.GAP) })
    }
}

fun Context.stack(vararg v: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    v.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(T.GAP) }) }
}

fun Context.kpi(name: String, value: String) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(label(T.SMALL, T.MUTED, 500, name))
    addView(label(T.KPI, T.TEXT, 600, value).apply { setPadding(0, dp(4), 0, 0) })
}

fun Context.report(
    r: ReportState, rider: Profile?, wide: Boolean, canSaveGuest: Boolean,
    onChange: () -> Unit, onDone: () -> Unit, onSaveGuest: () -> Unit,
): View {
    val t = r.trip
    val summary = card().apply {
        addView(label(T.SECTION, T.TEXT, 600, "이번 주행"))
        addView(LinearLayout(context).apply {
            setPadding(0, dp(T.GAP), 0, 0)
            listOf("편안함 점수" to "${t.score}", "가장 높았던 단계" to riskName(t.maxRisk), "급한 움직임" to "${r.harsh}회", "주행 시간" to "${t.minutes}분")
                // "많이 흔들림"처럼 긴 단계 이름이 옆 숫자와 붙지 않게 단계 칸을 넓게
                .forEach { (n, v) -> addView(kpi(n, v), LinearLayout.LayoutParams(0, -2, if (n == "가장 높았던 단계") 1.6f else 1f)) }
        })
    }
    val taxi = card().apply {
        addView(label(T.SMALL, T.MUTED, 500, "택시 연동 미리보기 · 예시"))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(label(T.BODY, T.TEXT, 500, "편안함 점수 ${t.score} · 급한 움직임 ${r.harsh}회 · ${t.minutes}분 주행"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill("기사님이 공유를 켜면 전송", T.RAISED, T.MUTED))
        })
    }
    val feedback = card().apply {
        addView(label(T.SECTION, T.TEXT, 600, "멀미는 어땠나요?"))
        addView(label(T.SMALL, T.MUTED, 500, "0 괜찮음 · 10 토할 것 같음 · 알려주시면 다음 경고 시점을 맞춰요").apply { setPadding(0, dp(4), 0, 0) })
        // 0~10을 두 줄로: 칩 사이 16dp 간격을 지키면서 64dp 높이 유지 (Pleos 터치 규칙)
        listOf(0..5, 6..10).forEach { range ->
            addView(LinearLayout(context).apply {
                setPadding(0, dp(12), 0, 0)
                for (m in 0..5) {
                    val v = range.first + m
                    val c = if (v in range) chip("$v", r.misc == v) { r.misc = v; onChange() }.apply { setPadding(0, 0, 0, 0) }
                            else android.widget.Space(context)
                    addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { if (m > 0) marginStart = dp(T.GAP) })
                }
            })
        }
        if (t.cares.isNotEmpty()) {
            addView(label(T.BODY, T.TEXT, 500, "도움이 된 케어 (선택)").apply { setPadding(0, dp(20), 0, 0) })
            addView(chipRow(Care.entries.filter { it in t.cares }.map { c ->
                chip(careName(c), c in r.helped) { if (!r.helped.remove(c)) r.helped += c; onChange() }
            }))
        }
    }
    val learn = card().apply {
        addView(label(T.SECTION, T.TEXT, 600, if (rider != null) "${rider.name}님 학습" else "게스트 주행"))
        val line = when {
            !worthKeeping(t) -> "1분 미만 주행은 기록하지 않아요"
            rider != null -> learningLine(rider, rider.learn(r.final()))
            else -> "게스트는 기록을 남기지 않아요 · 프로필로 저장하면 다음부터 맞춰 드려요"
        }
        addView(label(T.BODY, T.TEXT, 400, line).apply { setPadding(0, dp(8), 0, 0) })
    }
    val actions = LinearLayout(this).apply {
        gravity = Gravity.END
        if (rider == null && canSaveGuest && worthKeeping(t)) addView(button("프로필로 저장", icon = R.drawable.ic_person) { onSaveGuest() },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(T.GAP) })
        addView(button(if (rider != null) "저장하고 완료" else "완료", primary = true, icon = R.drawable.ic_check) { onDone() })
    }
    val sub = "${seatName(rider?.seat ?: Seat.FRONT_RIGHT)} · ${t.minutes}분" + if (t.demo) " · 데모 주행" else ""
    return page("주행 리포트", sub, null, stack(columnsOf(stack(summary, taxi), stack(feedback, learn), wide), actions))
}

// --- 프로필 상세 ---

fun Context.profileDetail(
    p: Profile, wide: Boolean,
    onBack: () -> Unit, onStart: () -> Unit, onToggle: (Care, Boolean) -> Unit, onTemp: (Float) -> Unit, onDelete: () -> Unit,
): View {
    val trend = card().apply {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(T.SECTION, T.TEXT, 600, "최근 10회 편안함 점수"), LinearLayout.LayoutParams(0, -2, 1f))
            // 범례: 색 견본은 크기를 정해 줘야 함 (wrap_content View는 남는 높이를 다 차지)
            listOf(Triple(T.DATA, 4, "점수"), Triple(blend(T.DATA, T.SURFACE, 0.6f), 4, "데모 주행"), Triple(T.WARN, 8, "멀미 응답(0~10)")).forEach { (color, r, name) ->
                addView(View(context).apply { background = fill(color, dp(r).toFloat()) },
                    LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(T.GAP); marginEnd = dp(8) })
                addView(label(T.SMALL, T.MUTED, 500, name))
            }
        })
        addView(TrendView(context, p.trips.take(10).reversed()), LinearLayout.LayoutParams(-1, dp(240)).apply { topMargin = dp(12) })
    }
    val e = p.earlierPercent()
    val timing = card().apply {
        addView(label(T.SECTION, T.TEXT, 600, "경고 시점"))
        addView(label(T.KPI, T.TEXT, 600, when { e > 0 -> "$e% 일찍"; e < 0 -> "${-e}% 늦게"; else -> "기본" }).apply { setPadding(0, dp(8), 0, 0) })
        addView(label(T.SMALL, T.MUTED, 500, "설문과 주행 후 멀미 응답으로 자동 조정돼요"))
    }
    val temp = card().apply {
        addView(label(T.SECTION, T.TEXT, 600, "공기 케어 온도"))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(button("−") { onTemp(p.careTempC - 0.5f) }.apply { minimumWidth = dp(T.TOUCH) })
            addView(label(T.KPI, T.TEXT, 600, "%.1f°C".format(p.careTempC)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("+") { onTemp(p.careTempC + 0.5f) }.apply { minimumWidth = dp(T.TOUCH) })
        })
        addView(label(T.SMALL, T.MUTED, 500, "케어 중에 온도를 직접 바꾸면 그 값을 기억해요").apply { setPadding(0, dp(8), 0, 0) })
    }
    val cares = card().apply {
        addView(label(T.SECTION, T.TEXT, 600, "케어별 효과"))
        addView(label(T.SMALL, T.MUTED, 500, "3번 넘게 써도 도움이 적으면 자동으로 꺼져요").apply { setPadding(0, dp(4), 0, dp(4)) })
        Care.entries.forEach { c ->
            val s = p.cares[c] ?: CareStat()
            val on = !s.off
            addView(View(context).apply { setBackgroundColor(T.RULE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(T.ROW_H)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(T.BODY, T.TEXT, 500, careName(c)))
                    addView(label(T.SMALL, T.MUTED, 400, if (s.runs == 0) "아직 기록 없음" else "도움 ${s.helped}/${s.runs}회"))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(chip(if (on) "켜짐" else "꺼짐", on) { onToggle(c, !on) })
            })
        }
    }
    val actions = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(button("프로필 삭제") { onDelete() }.apply { (getChildAt(0) as TextView).setTextColor(T.NEG) })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(button("이 프로필로 시작", primary = true) { onStart() })
    }
    val sub = "${seatName(p.seat)} · 주행 ${p.trips.size}회"
    return page(p.name, sub, onBack, stack(trend, columnsOf(stack(timing, temp), cares, wide), actions))
}
