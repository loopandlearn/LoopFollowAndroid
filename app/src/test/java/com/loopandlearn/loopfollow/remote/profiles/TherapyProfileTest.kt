package com.loopandlearn.loopfollow.remote.profiles

import com.loopandlearn.loopfollow.engine.NSProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TherapyProfileTest {
    private val vector = TherapyProfileValues(
        "mg/dL",
        listOf(ProfileScheduleItem(0, 0.85), ProfileScheduleItem(21600, 1.0)),
        listOf(ProfileScheduleItem(0, 10.0)),
        listOf(ProfileScheduleItem(0, 50.0)),
        listOf(ProfileRangeItem(0, 100.0, 110.0)),
    )

    private val weekend = ScheduledProfileSwitch("5A3D9D6E-1C7F-4A52-9C1D-2B3F9E0F7A11", "Weekend", 7, 25200)
    private val school = ScheduledProfileSwitch("B0E6C2F4-8D1B-4F0E-A6A7-3C2E5D9B1F02", "Default", 2, 21600)

    private fun store(units: String, sens: Double, low: Double, high: Double): NSProfile.Store = NSProfile.Store(
        Json.parseToJsonElement(
            """{"timezone": "UTC", "units": "$units",
                "basal": [{"time": "00:00", "value": 0.85, "timeAsSeconds": 0}, {"time": "06:00", "value": 1.1, "timeAsSeconds": 21600}],
                "carbratio": [{"time": "00:00", "value": 10, "timeAsSeconds": 0}],
                "sens": [{"time": "00:00", "value": $sens, "timeAsSeconds": 0}],
                "target_low": [{"time": "00:00", "value": $low, "timeAsSeconds": 0}],
                "target_high": [{"time": "00:00", "value": $high, "timeAsSeconds": 0}]}""",
        ).jsonObject,
    )

    @Test
    fun profileFingerprintMatchesSharedVector() {
        assertEquals("3e603478d512da04", vector.fingerprint)
    }

    @Test
    fun profileFingerprintMatchesLoop() {
        assertEquals("fec4f066b5a54ecd", TherapyProfile("Sport", store("mg/dL", 45.0, 100.0, 110.0)).fingerprint)
        val mmol = TherapyProfile("Sport", store("mmol/L", 2.4976469047529, 5.550747991045533, 6.105822790150087))
        assertEquals("f7d9520ff02af97c", mmol.fingerprint)
        assertEquals("mmol/L", mmol.values.units)
    }

    @Test
    fun valuesAreSentAsShortDecimals() {
        val values = vector.copy(basal = listOf(ProfileScheduleItem(0, 0.7), ProfileScheduleItem(43200, 0.1 + 0.2), ProfileScheduleItem(64800, 1.15)))
        assertTrue(values.json.toString().contains("\"basal\":[[0,0.7],[43200,0.3],[64800,1.15]]"))
        assertTrue(vector.json.toString().contains("\"target\":[[0,100,110]]"))
    }

    @Test
    fun scheduledSwitchFingerprintMatchesSharedVectors() {
        val entries = listOf(weekend, school)
        assertEquals(
            "profile=Default;weekday=2;date=-;time=21600;paused=false|profile=Weekend;weekday=7;date=-;time=25200;paused=false",
            ScheduledProfileSwitch.canonicalString(entries),
        )
        assertEquals("1f4d16404c846d2f", ScheduledProfileSwitch.fingerprint(entries))
        assertEquals("bc784950a457ba2c", ScheduledProfileSwitch.fingerprint(entries.map { it.copy(paused = true) }))
        assertEquals("e3b0c44298fc1c14", ScheduledProfileSwitch.fingerprint(emptyList()))
    }

    @Test
    fun scheduledSwitchReadsOnlyWeeklyEntries() {
        fun read(text: String) = ScheduledProfileSwitch.from(Json.parseToJsonElement(text))
        assertEquals(weekend, read("""{"id": "5A3D9D6E-1C7F-4A52-9C1D-2B3F9E0F7A11", "profile": "Weekend", "weekday": 7, "time": 25200}"""))
        assertNull(read("""{"id": "a", "profile": "Weekend", "weekday": 7, "time": 25200, "date": "2026-01-01"}"""))
        assertNull(read("""{"id": "a", "profile": "Weekend", "weekday": 7, "time": 25200.5}"""))
        assertTrue(read("""{"id": "a", "profile": "Weekend", "weekday": 7, "time": 25200, "paused": true}""")!!.paused)
    }

    @Test
    fun scheduledSwitchListChecksTheUploadedFingerprint() {
        fun document(fingerprint: String) = NSProfile(
            Json.parseToJsonElement(
                """{"defaultProfile": "Default", "store": {"Default": {"timezone": "UTC", "units": "mg/dL"}},
                    "scheduledSwitches": [${weekend.json}, ${school.json}], "scheduledSwitchesFingerprint": "$fingerprint"}""",
            ).jsonObject,
        )
        assertTrue(ScheduledSwitchList(document("1f4d16404c846d2f")).isComplete)
        assertFalse(ScheduledSwitchList(document("0000000000000000")).isComplete)
    }

    @Test
    fun loopSavePayloadCarriesTheCompactValues() {
        val fields = com.loopandlearn.loopfollow.remote.loop.LoopAPNSCommands.profileSaveCommandPayload("Weekend 2", "Weekend", "3e603478d512da04", vector, "123456", now = 0.0)
        assertEquals("\"Weekend 2\"", fields["profile-save"].toString())
        assertEquals("\"Weekend\"", fields["profile-save-original-name"].toString())
        assertEquals("\"3e603478d512da04\"", fields["profile-save-base-fingerprint"].toString())
        assertEquals(setOf("units", "basal", "carbratio", "sens", "target"), (fields["profile-save-values"] as JsonObject).keys)
        val switches = com.loopandlearn.loopfollow.remote.loop.LoopAPNSCommands.scheduledSwitchesSaveCommandPayload(listOf(weekend.copy(paused = true)), "1f4d16404c846d2f", "123456", now = 0.0)
        assertEquals(true.toString(), ((switches["profile-scheduled-switches-save"] as JsonArray)[0] as JsonObject)["paused"].toString())
    }

    @Test
    fun limitsReadLenientlyAndValidate() {
        val json = Json.parseToJsonElement(
            """{"units": "mg/dL", "maxBasal": 2, "basalRates": [0.05, 0.1, 0.5, 1, 1.5, 2, 3], "carbRatio": {"min": 2, "max": 150, "step": 0.1},
                "sensitivity": {"min": 10, "max": 500, "step": 1, "recommendedMin": 16, "recommendedMax": 399},
                "target": {"min": 87, "max": 180, "step": 1}, "maxSegments": 48, "segmentSeconds": 1800}""",
        ).jsonObject
        val limits = ProfileEditLimits.from(json)!!
        assertTrue(limits.isUsable)
        assertEquals(listOf(0.05, 0.1, 0.5, 1.0, 1.5, 2.0), limits.allowedBasalRates)
        assertEquals(ProfileValueStatus.OUTSIDE_RECOMMENDED, limits.status(12.0, ProfileScheduleKind.SENSITIVITY))
        assertEquals(ProfileValueStatus.NOT_ALLOWED, limits.status(3.0, ProfileScheduleKind.BASAL))
        assertNull(ProfileEditLimits.from(Json.parseToJsonElement("""{"units": "mg/dL"}""").jsonObject))
        assertEquals(listOf("Basal Rates: the first time must be midnight."), ProfileValidation.scheduleProblems(listOf(1800), ProfileScheduleKind.BASAL, limits))
    }

    @Test
    fun namesFollowTheRules() {
        assertEquals("“Default” is the name of the Default profile. Choose another name.", ProfileValidation.nameProblem("default", emptyList()))
        assertEquals("A profile with this name already exists.", ProfileValidation.nameProblem("sport", listOf("Sport")))
        assertNull(ProfileValidation.nameProblem("sport", listOf("Sport"), originalName = "Sport"))
        assertEquals("A name can't contain a period (.).", ProfileValidation.nameProblem("a.b", emptyList()))
    }

    @Test
    fun moreInsulinPeriodsJoin() {
        val higher = vector.copy(basal = listOf(ProfileScheduleItem(0, 0.9), ProfileScheduleItem(21600, 1.0)))
        val increases = ProfileInsulinComparison.increases(vector, higher)
        assertEquals(1, increases.size)
        assertEquals(ProfileScheduleKind.BASAL, increases[0].kind)
        assertEquals(listOf(0..21600), increases[0].periods)
    }
}
