package com.motioncare

// 호흡 가이드 길이와 박자 (Stromberg 2015: 노출 내내 분당 약 8회 느린 복식호흡)

const val BREATH_MIN_MS = 300_000L     // 한 번 시작하면 최소 5분
const val BREATH_MAX_MS = 900_000L     // 길어도 15분
const val BREATH_EXTEND_MS = 60_000L   // 흔들림이 이어지면 1분씩 연장
const val BREATH_PHASE_MS = 4_000L     // 4초 들숨 / 4초 날숨 = 분당 7.5회

/** 진행 중이고 위험이 주의 이상이면 끝나는 시각을 늦춘다. 멈춘 호흡(untilMs ≤ now)은 그대로 둔다. */
fun breathUntil(startMs: Long, untilMs: Long, nowMs: Long, risk: Risk): Long =
    if (untilMs > nowMs && risk >= Risk.CAUTION) maxOf(untilMs, minOf(nowMs + BREATH_EXTEND_MS, startMs + BREATH_MAX_MS))
    else untilMs

fun isInhale(elapsedMs: Long): Boolean = (elapsedMs / BREATH_PHASE_MS) % 2 == 0L
