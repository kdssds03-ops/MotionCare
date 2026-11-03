package com.motioncare

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Pleos UI 가이드: 텍스트·아이콘과 배경 대비 4.5:1 이상 (주간·야간 모두) */
class UiContrastTest {

    private fun lum(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    private fun ratio(a: Int, b: Int): Double {
        val (x, y) = lum(a) to lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test fun everyTextPairPasses() {
        for (night in listOf(false, true)) {
            val p = Palette.of(night)
            val pairs = listOf(
                "TEXT/SURFACE" to (p.text to p.surface), "TEXT/GROUND" to (p.text to p.ground),
                "MUTED/SURFACE" to (p.muted to p.surface), "FAINT/SURFACE" to (p.faint to p.surface),
                "DATA/SURFACE" to (p.data to p.surface), "PRIMARY" to (p.primaryText to p.primaryBg),
                "SECONDARY" to (p.text to p.secondaryBg), "POS" to (p.pos to p.posBg),
                "WARN" to (p.warn to p.warnBg), "NEG" to (p.neg to p.negBg), "SICK" to (p.sickText to p.sickBg),
                "TEXT/RAISED" to (p.text to p.raised),
                "ACCENT/SURFACE" to (p.accent to p.surface), "ACCENT/GROUND" to (p.accent to p.ground),
                "ACCENT/ACCENT_BG" to (p.accent to p.accentBg), "WARN/SURFACE" to (p.warn to p.surface),
                "FAINT/GROUND" to (p.faint to p.ground), "MUTED/RAISED" to (p.muted to p.raised),
            )
            for ((name, c) in pairs) {
                val r = ratio(c.first, c.second)
                assertTrue("${if (night) "야간" else "주간"} $name 대비 %.2f".format(r), r >= 4.5)
            }
        }
    }
}
