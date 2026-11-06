# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

- **Primary: the front-seat passenger** who gets carsick. They read and tap the shared center display (Pleos Connect, Android Automotive) while the car is moving. Their job: feel better without doing anything, and understand what the car is doing for them.
- **The driver** is a secondary user. They get voice only, plus at most a glance at the screen. Driver coaching is spoken, mostly while stopped.
- **First audience to win: contest judges** for Hyundai Motor Group's Pleos Connect in-car app contest. They see the demo video, screenshots, and proposal (first deadline 2026-11-15). Taxi platforms, insurers, and family car owners are later audiences described in the proposal.

## Product Purpose

멀미케어 (MotionCare): "멀미가 오기 전에 차가 먼저 챙겨주는 동승자 케어 + 동승자 편안함 점수." The app estimates carsickness risk in real time from vehicle data. When risk rises, it acts automatically through climate, ambient light, seat, and voice. After the trip it produces a passenger comfort score.

Success means a passenger feels cared for before symptoms build, and the trip ends with a score and a learned adjustment that make the next ride better.

## Positioning

The care is proactive and runs inside the car itself. Risk is computed from a standard (ISO 2631-1 MSDV, 0.1–0.5 Hz motion), and the app intervenes through real vehicle controls via the Pleos Vehicle SDK. Every intervention traces to a cited study with an evidence grade (A/B/C). The comfort score turns ride smoothness into a metric that platforms can use, much like a passenger-side TMAP driving score.

## Operating Context

- Runs on the in-car center display while driving. Input is limited by CarUxRestrictions (no keyboard while moving). Profile creation is blocked while driving.
- Trip flow: start as a guest or pick or create a profile (nickname, seat, 3-question sensitivity survey) → drive with live risk and active care → end the trip → report, MISC 0–10 feedback, and a preview of what would be sent to a taxi platform.
- The demo runs on the Pleos Connect Emulator 2.1.0 as a 4× speed virtual drive. HVAC and location are fed through the Virtual Console and adb.
- Has day and night themes. Supports rotation and backgrounding, and resumes an in-progress trip after the app is killed.

## Capabilities and Constraints

- **Measure:** sensitivity profile (MSSQ-Short, reduced to 3 questions; low/normal/high), live risk index (주의 / 위험 thresholds scaled by sensitivity), comfort score 0–100 with harsh-maneuver counts, MISC 0–10 feedback that tunes each person's thresholds.
- **Care:** air care (outside air, fan up, temperature down; fan, temperature and light change only in the passenger's zone and are restored after the trip), breathing guide (ambient light 4 s brighter / 4 s dimmer, plus voice), turn warnings (navigation turn-by-turn info or the turn signal, spoken), seatback recline (only in P, only after the passenger confirms), "속이 안 좋아요" button or voice that adds the nearest rest area as a navigation waypoint (auto-suggested after 3 minutes at 위험), favorite music (planned).
- **Safety rules:** voice only for the driver and as few on-screen alerts as possible; seat changes are small and need the passenger's confirmation; claims say "helps relieve" (완화에 도움), never "treats."
- **Taxi integration** is a mockup (preview card only). The driver opts in to sharing; the score works as an incentive, not surveillance; data is anonymized and collected with consent.
- Stack: Kotlin, Android (compileSdk 35, minSdk 28), Pleos Vehicle / FusedLocation / TTS / STT / NaviHelper SDKs. There are 98 unit tests, including UI contrast.

## Brand Commitments

None yet. The name 멀미케어 / MotionCare and the voice are both open. The current copy uses gentle, plain 해요체, which is the incumbent voice but not a commitment.

## Evidence on Hand

- Feature spec with citations and evidence grades: `../멀미케어_기능명세서.md`
- Submission materials in `../제출자료/`: demo videos (1080p/720p/540p), proposal (`멀미케어_접수양식_기획서.docx`), screenshots of the driving and trip-end screens.
- Rest-area data: 670 Korean rest areas from OpenStreetMap (ODbL), `app/src/main/res/raw/rest_areas.json`.
- Emulator verification log: `docs/superpowers/2026-11-02-motioncare-v2-verification.md`.
- No real-road trials, user studies, testimonials, or partner agreements exist. Do not invent efficacy numbers beyond the cited studies, or claim taxi or insurer partnerships.

## Product Principles

1. **The car acts first; the passenger shouldn't have to.** Care is automatic. The screen explains what is happening; it is not a control panel the passenger must operate.
2. **Never pull the driver's eyes.** Anything meant for the driver is spoken. The screen is the passenger's.
3. **Looking down makes it worse.** Reading a screen worsens carsickness, so moving-state screens must be readable in a glance and push attention back out the window.
4. **Every claim has a source.** Interventions and numbers trace to the spec's cited evidence, with modest wording.
5. **A score is an incentive, not surveillance.** Anything about driver quality is framed as reward and stays the driver's choice to share.

## Accessibility & Inclusion

- Readable at in-car viewing distance in a moving vehicle, in both day and night themes. Contrast is covered by `UiContrastTest`.
- Touch targets sized for use while moving. No text entry while driving (CarUxRestrictions).
- Key care events are also spoken, so the screen is never the only channel.
