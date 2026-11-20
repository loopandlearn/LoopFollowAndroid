package com.loopandlearn.loopfollow.remote.profiles

import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.remote.RemoteCommandRefresh
import com.loopandlearn.loopfollow.remote.TRCCommandType
import com.loopandlearn.loopfollow.remote.TrioRemote
import com.loopandlearn.loopfollow.remote.loop.LoopAPNSCommands
import com.loopandlearn.loopfollow.remote.loop.LoopAPNSService
import com.loopandlearn.loopfollow.remote.loop.LoopCommands
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/** The profile commands LoopFollow can send. */
enum class ProfileCommandKind { ACTIVATE, SAVE, DELETE, SAVE_SCHEDULED_SWITCHES }

/** A profile command and what it acts on. */
sealed class ProfileCommand {
    data class Activate(val profile: TherapyProfile) : ProfileCommand()

    /** Creates [name] when [original] is null; otherwise updates [original], renaming it when [name] differs. */
    data class Save(val name: String, val original: TherapyProfile?, val values: TherapyProfileValues) : ProfileCommand()

    data class Delete(val profile: TherapyProfile) : ProfileCommand()

    /** Replaces the scheduled switches; the phone only does so while its list has [baseFingerprint]. */
    data class SaveScheduledSwitches(val entries: List<ScheduledProfileSwitch>, val baseFingerprint: String) : ProfileCommand()

    val kind: ProfileCommandKind
        get() = when (this) {
            is Activate -> ProfileCommandKind.ACTIVATE
            is Save -> ProfileCommandKind.SAVE
            is Delete -> ProfileCommandKind.DELETE
            is SaveScheduledSwitches -> ProfileCommandKind.SAVE_SCHEDULED_SWITCHES
        }

    /** The profile the command names. For a rename it is the new name. */
    val targetName: String
        get() = when (this) {
            is Activate -> profile.name
            is Delete -> profile.name
            is Save -> name
            is SaveScheduledSwitches -> "scheduled switches"
        }

    /** True for a save that gives an existing profile a new name. */
    val isRename: Boolean get() = this is Save && original != null && original.name != name
}

/** The AID app the profile commands go to, with its command names. */
enum class ProfileRemote(val appName: String) {
    LOOP("Loop"),
    TRIO("Trio"),
    ;

    /** Trio doses to one glucose target per time, so its profiles carry a target whose high equals its low. */
    val hasSingleTarget: Boolean get() = this == TRIO

    /** The name the app lists in its supported remote commands. */
    fun commandName(kind: ProfileCommandKind): String = when (this) {
        LOOP -> when (kind) {
            ProfileCommandKind.ACTIVATE -> "profile-switch"
            ProfileCommandKind.SAVE -> "profile-save"
            ProfileCommandKind.DELETE -> "profile-delete"
            ProfileCommandKind.SAVE_SCHEDULED_SWITCHES -> "profile-scheduled-switches-save"
        }
        TRIO -> when (kind) {
            ProfileCommandKind.ACTIVATE -> TRCCommandType.SWITCH_PROFILE.raw
            ProfileCommandKind.SAVE -> TRCCommandType.SAVE_PROFILE.raw
            ProfileCommandKind.DELETE -> TRCCommandType.DELETE_PROFILE.raw
            ProfileCommandKind.SAVE_SCHEDULED_SWITCHES -> TRCCommandType.SAVE_SCHEDULED_SWITCHES.raw
        }
    }

    fun supports(kind: ProfileCommandKind, commands: List<String>) = commandName(kind) in commands

    fun supportsAny(commands: List<String>) = ProfileCommandKind.entries.any { supports(it, commands) }

    /**
     * The commands the app accepts: those in its profile document. Loop builds with the Profile Save & Load
     * customization list only `profile-switch`, in their device status; they get switches, never saves or deletes.
     * A Loop that lists commands in its profile document is used through those alone.
     */
    fun acceptedCommands(profileDocument: List<String>, loopDeviceStatus: List<String>): List<String> {
        val activate = commandName(ProfileCommandKind.ACTIVATE)
        if (this != LOOP || activate !in loopDeviceStatus || activate in profileDocument) return profileDocument
        return profileDocument + activate
    }

    fun successMessage(command: ProfileCommand): String = when (command.kind) {
        ProfileCommandKind.ACTIVATE -> "Profile '${command.targetName}' activated"
        ProfileCommandKind.SAVE -> "Profile '${command.targetName}' saved"
        ProfileCommandKind.DELETE -> "Profile '${command.targetName}' deleted"
        ProfileCommandKind.SAVE_SCHEDULED_SWITCHES -> "Scheduled switches saved"
    }
}

/** Sending profile commands, checking them against the newest upload, and wording their results. */
object ProfileCommands {
    /** Sends [command]; null when APNs accepted the push, else the error to show. Starts the early profile fetches. */
    suspend fun send(command: ProfileCommand, remote: ProfileRemote): String? {
        val change = RemoteCommandRefresh.Change.Profile(command)
        return when (remote) {
            ProfileRemote.LOOP -> LoopCommands.withOTP(command.kind.name.lowercase()) { otp ->
                sendLoop(command, otp).also { if (it == null) RemoteCommandRefresh.commandSent(change) }
            }
            ProfileRemote.TRIO -> {
                val type = trioType(command)
                TrioRemote.profileCommand(type, change, tooLargeMessage(command), trioFields(command)).also {
                    if (it == null) LogManager.log(LogManager.Category.APNS, "Sent ${type.raw}${baseText(command)}")
                }
            }
        }
    }

    private suspend fun sendLoop(command: ProfileCommand, otp: String): String? = when (command) {
        is ProfileCommand.Activate -> LoopAPNSService.sendProfileCommand(
            LoopAPNSCommands.profileSwitchCommandPayload(command.profile.name, command.profile.fingerprint, otp),
            "profile switch fingerprint=${command.profile.fingerprint}",
        )
        is ProfileCommand.Save -> LoopAPNSService.sendProfileCommand(
            LoopAPNSCommands.profileSaveCommandPayload(command.name, command.original?.name, command.original?.fingerprint, command.values, otp),
            "profile save base=${command.original?.fingerprint ?: "-"} new=${command.values.fingerprint}",
            ProfilePushSize::tooLargeMessage,
        )
        is ProfileCommand.Delete -> LoopAPNSService.sendProfileCommand(
            LoopAPNSCommands.profileDeleteCommandPayload(command.profile.name, command.profile.fingerprint, otp),
            "profile delete fingerprint=${command.profile.fingerprint}",
        )
        is ProfileCommand.SaveScheduledSwitches -> LoopAPNSService.sendProfileCommand(
            LoopAPNSCommands.scheduledSwitchesSaveCommandPayload(command.entries, command.baseFingerprint, otp),
            "scheduled switches save base=${command.baseFingerprint} new=${ScheduledProfileSwitch.fingerprint(command.entries)}",
            ProfilePushSize::scheduledSwitchesTooLargeMessage,
        )
    }

    private fun trioType(command: ProfileCommand) = when (command) {
        is ProfileCommand.Activate -> TRCCommandType.SWITCH_PROFILE
        is ProfileCommand.Save -> TRCCommandType.SAVE_PROFILE
        is ProfileCommand.Delete -> TRCCommandType.DELETE_PROFILE
        is ProfileCommand.SaveScheduledSwitches -> TRCCommandType.SAVE_SCHEDULED_SWITCHES
    }

    private fun tooLargeMessage(command: ProfileCommand): ((Int) -> String)? = when (command) {
        is ProfileCommand.Save -> ProfilePushSize::tooLargeMessage
        is ProfileCommand.SaveScheduledSwitches -> ProfilePushSize::scheduledSwitchesTooLargeMessage
        else -> null
    }

    private fun baseText(command: ProfileCommand) = when (command) {
        is ProfileCommand.Activate -> " base=${command.profile.fingerprint}"
        is ProfileCommand.Delete -> " base=${command.profile.fingerprint}"
        is ProfileCommand.Save -> " base=${command.original?.fingerprint ?: "-"} new=${command.values.fingerprint}"
        is ProfileCommand.SaveScheduledSwitches -> " base=${command.baseFingerprint} new=${ScheduledProfileSwitch.fingerprint(command.entries)}"
    }

    /** The fields a Trio profile command carries besides its header and command id. */
    private fun trioFields(command: ProfileCommand): JsonObjectBuilder.() -> Unit = {
        when (command) {
            is ProfileCommand.Activate -> {
                put("profile_name", command.profile.name)
                put("base_fingerprint", command.profile.fingerprint)
            }
            is ProfileCommand.Delete -> {
                put("profile_name", command.profile.name)
                put("base_fingerprint", command.profile.fingerprint)
            }
            is ProfileCommand.Save -> {
                put("profile_name", command.name)
                command.original?.let {
                    put("original_profile_name", it.name)
                    put("base_fingerprint", it.fingerprint)
                }
                put("profile", command.values.json)
            }
            is ProfileCommand.SaveScheduledSwitches -> {
                put("base_fingerprint", command.baseFingerprint)
                put("scheduled_switches", JsonArray(command.entries.map { it.json }))
            }
        }
    }

    /** The size of the push [command] would send, or null when it can not be computed. */
    fun pushSize(command: ProfileCommand, remote: ProfileRemote): Int? = when (remote) {
        ProfileRemote.LOOP -> {
            val otp = "000000"
            val payload = when (command) {
                is ProfileCommand.Save -> LoopAPNSCommands.profileSaveCommandPayload(command.name, command.original?.name, command.original?.fingerprint, command.values, otp)
                is ProfileCommand.SaveScheduledSwitches -> LoopAPNSCommands.scheduledSwitchesSaveCommandPayload(command.entries, command.baseFingerprint, otp)
                is ProfileCommand.Activate -> LoopAPNSCommands.profileSwitchCommandPayload(command.profile.name, command.profile.fingerprint, otp)
                is ProfileCommand.Delete -> LoopAPNSCommands.profileDeleteCommandPayload(command.profile.name, command.profile.fingerprint, otp)
            }
            LoopAPNSService.pushSize(payload)
        }
        ProfileRemote.TRIO -> TrioRemote.profileCommandPushSize(trioType(command), trioFields(command))
    }

    /**
     * Why a save of [original], reviewed while it was [reviewedActive], must not be sent now that [current] is the
     * newest profile document, or null when it can be sent.
     */
    fun saveStaleReason(original: TherapyProfile, reviewedActive: Boolean, current: TherapyProfileSet?, remote: ProfileRemote): String? {
        current ?: return "LoopFollow could not load the profiles from Nightscout to check that “${original.name}” is unchanged, so nothing was sent. Try again."
        val profile = current.profile(original.name)
            ?: return "“${original.name}” is no longer in the list uploaded by ${remote.appName}, so nothing was sent."
        if (profile.fingerprint != original.fingerprint) {
            return "“${original.name}” changed in ${remote.appName} while you were editing, so nothing was sent. Review it again."
        }
        if (current.isActive(profile) != reviewedActive) {
            return if (reviewedActive) {
                "“${original.name}” is no longer the active profile in ${remote.appName}, so nothing was sent. Review it again."
            } else {
                "“${original.name}” became active in ${remote.appName} while you were editing, so nothing was sent. Review it again."
            }
        }
        return null
    }

    /** Why the reviewed scheduled switches must not be sent now that [current] is the newest profile document, or null. */
    fun scheduledSwitchesStaleReason(baseFingerprint: String, entries: List<ScheduledProfileSwitch>, current: TherapyProfileSet?, remote: ProfileRemote): String? {
        current ?: return "LoopFollow could not load the scheduled switches from Nightscout to check that they are unchanged, so nothing was sent. Try again."
        if (current.scheduledSwitches.fingerprint != baseFingerprint || !current.scheduledSwitches.isComplete) {
            return "The scheduled switches changed in ${remote.appName} while you were editing, so nothing was sent. Review them again."
        }
        val names = current.profiles.map { it.name }
        val missing = entries.firstOrNull { entry -> names.none { it.equals(entry.profile, ignoreCase = true) } }
        if (missing != null) {
            return "“${missing.profile}” is no longer in the list uploaded by ${remote.appName}, so nothing was sent. Review the scheduled switches again."
        }
        return null
    }

    /**
     * True once [now] shows the command's result: the profile is active, deleted, or saved with values other than
     * those in [before] (a new or renamed profile has none there), or the scheduled switches are no longer the list
     * the command replaced.
     */
    fun arrived(command: ProfileCommand, before: TherapyProfileSet?, now: TherapyProfileSet?): Boolean {
        now ?: return false
        return when (command) {
            is ProfileCommand.Activate -> now.activeName.equals(command.profile.name, ignoreCase = true)
            is ProfileCommand.Delete -> now.profile(command.profile.name) == null
            is ProfileCommand.Save -> {
                val saved = now.profile(command.name) ?: return false
                saved.fingerprint != before?.profile(command.name)?.fingerprint
            }
            is ProfileCommand.SaveScheduledSwitches -> now.scheduledSwitches.fingerprint != command.baseFingerprint
        }
    }

    fun failureTitle(kind: ProfileCommandKind): String = when (kind) {
        ProfileCommandKind.ACTIVATE -> "Profile Not Activated"
        ProfileCommandKind.SAVE -> "Profile Not Saved"
        ProfileCommandKind.DELETE -> "Profile Not Deleted"
        ProfileCommandKind.SAVE_SCHEDULED_SWITCHES -> "Scheduled Switches Not Saved"
    }

    /** The message for a command refused because its profile was out of date: what happens next. */
    fun outdatedMessage(message: String, remote: ProfileRemote, scheduledSwitches: Boolean = false): String =
        if (scheduledSwitches) {
            "$message\n\nLoopFollow is loading the scheduled switches ${remote.appName} uploaded. Open them again to review the current list."
        } else {
            "$message\n\nLoopFollow is loading the profiles ${remote.appName} uploaded. Open the profile again to see its current values."
        }
}
