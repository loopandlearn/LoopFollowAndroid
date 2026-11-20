package com.loopandlearn.loopfollow.settings.importexport

import com.loopandlearn.loopfollow.remote.RemoteSetupMessage
import com.loopandlearn.loopfollow.settings.importexport.RemoteKeyAdoption.Decision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeyAdoptionTest {
    private val keyId = "ABCDE12345"
    private val key = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"

    private fun decide(remoteKeyId: String = "", remoteKey: String = "", lfTeam: String? = null, remoteTeam: String? = null, lfKey: String = key) =
        RemoteKeyAdoption.decide(remoteKeyId, remoteKey, keyId, lfKey, lfTeam, remoteTeam)

    @Test fun adoptsWhenTeamsMatch() = assertEquals(Decision.ADOPT, decide(lfTeam = "TEAM123456", remoteTeam = "TEAM123456"))

    @Test fun adoptsWhenTeamUnknown() {
        assertEquals(Decision.ADOPT, decide())
        assertEquals(Decision.ADOPT, decide(lfTeam = "TEAM123456"))
        assertEquals(Decision.ADOPT, decide(remoteTeam = "TEAM123456"))
    }

    @Test fun keepsKeyOfOtherTeamOut() = assertEquals(Decision.OTHER_TEAM, decide(lfTeam = "TEAMAAAAAA", remoteTeam = "TEAMBBBBBB"))

    @Test fun neverOverwritesRemoteKey() {
        assertEquals(Decision.REMOTE_KEY_SET, decide(remoteKeyId = "ZZZZZ99999"))
        assertEquals(Decision.REMOTE_KEY_SET, decide(remoteKey = key))
    }

    @Test fun needsValidKey() = assertEquals(Decision.NO_KEY, decide(lfKey = "not a key"))

    @Test fun apnsSectionReadsAndWritesTeamId() {
        val withTeam = Json.parseToJsonElement("""{"version":"5.0","keyId":"$keyId","apnsKey":"k","teamId":"TEAM123456"}""").jsonObject
        val parsed = APNSSettingsExport.fromJson(withTeam)
        assertEquals("TEAM123456", parsed.teamId)
        assertEquals("TEAM123456", APNSSettingsExport.fromJson(parsed.toJson()).teamId)

        val withoutTeam = Json.parseToJsonElement("""{"version":"5.0","keyId":"$keyId","apnsKey":"k"}""").jsonObject
        val plain = APNSSettingsExport.fromJson(withoutTeam)
        assertNull(plain.teamId)
        assertTrue("teamId" !in plain.toJson())
    }

    @Test fun setupMessageNamesWhatIsMissing() {
        assertNull(RemoteSetupMessage.missing("Trio", apnsKey = true, teamId = true, device = true))
        val message = RemoteSetupMessage.missing("Trio", apnsKey = false, teamId = false, device = true)!!
        assertTrue(message.contains("APNS Key ID and APNS Key in Remote Settings"))
        assertTrue(message.contains("Team ID, which comes from the Trio profile in Nightscout"))
    }
}
