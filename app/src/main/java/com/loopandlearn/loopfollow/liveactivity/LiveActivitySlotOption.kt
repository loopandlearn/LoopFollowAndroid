package com.loopandlearn.loopfollow.liveactivity

import com.loopandlearn.loopfollow.storage.Storage

/** One displayable metric that can occupy a slot in the Live Activity 2×2 grid. [raw] is the persisted value. */
enum class LiveActivitySlotOption(val raw: String, val displayName: String, val gridLabel: String) {
    NONE("none", "Empty", ""),
    DELTA("delta", "Delta", "Delta"),
    PROJECTED_BG("projectedBG", "Projected BG", "Proj"),
    MIN_MAX("minMax", "Min/Max", "Min/Max"),
    IOB("iob", "IOB", "IOB"),
    COB("cob", "COB", "COB"),
    REC_BOLUS("recBolus", "Rec. Bolus", "Rec."),
    AUTOSENS("autosens", "Autosens", "Sens"),
    TDD("tdd", "TDD", "TDD"),
    BASAL("basal", "Basal", "Basal"),
    PUMP("pump", "Pump", "Pump"),
    PUMP_BATTERY("pumpBattery", "Pump Battery", "Pump%"),
    BATTERY("battery", "Battery", "Bat."),
    TARGET("target", "Target", "Target"),
    ISF("isf", "ISF", "ISF"),
    CARB_RATIO("carbRatio", "CR", "CR"),
    SAGE("sage", "SAGE", "SAGE"),
    CAGE("cage", "CAGE", "CAGE"),
    IAGE("iage", "IAGE", "IAGE"),
    CARBS_TODAY("carbsToday", "Carbs today", "Carbs"),
    OVERRIDE("override", "Override", "Ovrd"),
    PROFILE("profile", "Profile", "Prof"),
    ;

    companion object {
        fun fromRaw(raw: String): LiveActivitySlotOption? = entries.firstOrNull { it.raw == raw }

        /** Options selectable for the grid. The active override has its own countdown row on the card. */
        val gridCases: List<LiveActivitySlotOption> get() = entries.filter { it != OVERRIDE }
    }
}

object LiveActivitySlotDefaults {
    val all = listOf(
        LiveActivitySlotOption.IOB,
        LiveActivitySlotOption.COB,
        LiveActivitySlotOption.PROJECTED_BG,
        LiveActivitySlotOption.NONE,
    )
}

object LASettings {
    /** The four grid slots, falling back to the defaults when no valid configuration is stored. */
    fun slots(): List<LiveActivitySlotOption> {
        val raw = Storage.laSlots.value
        if (raw.size != 4) return LiveActivitySlotDefaults.all
        return raw.map { LiveActivitySlotOption.fromRaw(it) ?: LiveActivitySlotOption.NONE }
            .map { if (it == LiveActivitySlotOption.OVERRIDE) LiveActivitySlotOption.NONE else it }
    }

    fun setSlots(slots: List<LiveActivitySlotOption>) {
        Storage.laSlots.value = slots.take(4).map { it.raw }
    }
}
