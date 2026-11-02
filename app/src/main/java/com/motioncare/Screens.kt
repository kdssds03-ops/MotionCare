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
                addView(label(if (last != null) T.KPI else T.SMALL, if (last != null) T.TEXT else T.MUTED, 700, last?.score?.toString() ?: "첫 주행"))
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
class Draft(var name: String = "", var seat: Seat? = null, val answers: IntArray = intArrayOf(-1, -1, -1)) {
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
    return page("새 프로필", "한 번만 입력하면 다음부터는 이름만 누르면 돼요", onCancel, body)
}

// ponytail: 저장 버튼 활성 상태를 글자 입력마다 갱신하려고 둔 전역 콜백. 화면이 하나뿐이라 충분.
private var saveBtnState: (() -> Unit)? = null

fun Context.chipRow(chips: List<View>) = LinearLayout(this).apply {
    chips.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(T.GAP) }) }
    setPadding(0, dp(12), 0, 0)
}

fun TextView.muted() = apply { setTextColor(T.MUTED) }
