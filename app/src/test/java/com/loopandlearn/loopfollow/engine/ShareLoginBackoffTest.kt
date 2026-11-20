package com.loopandlearn.loopfollow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLoginBackoffTest {
    @Test fun blocksTheSameCredentialsForFifteenMinutes() {
        ShareLoginBackoff.block("user-a", "wrong", "server", "AccountPasswordInvalid", now = 1000.0)
        assertEquals("AccountPasswordInvalid", ShareLoginBackoff.blockedCode("user-a", "wrong", "server", now = 1000.0 + 899))
        assertNull(ShareLoginBackoff.blockedCode("user-a", "wrong", "server", now = 1000.0 + 900))
    }

    @Test fun otherCredentialsAreNotBlocked() {
        ShareLoginBackoff.block("user-b", "wrong", "server", "AccountPasswordInvalid", now = 1000.0)
        assertNull(ShareLoginBackoff.blockedCode("user-b", "corrected", "server", now = 1001.0))
        assertNull(ShareLoginBackoff.blockedCode("user-c", "wrong", "server", now = 1001.0))
    }

    @Test fun onlyCredentialRejectionsBlock() {
        assertTrue(ShareLoginBackoff.rejectsCredentials("SSO_AuthenticatePasswordInvalid"))
        assertTrue(ShareLoginBackoff.rejectsCredentials("SSO_AuthenticateMaxAttemptsExceeed"))
        assertTrue(ShareLoginBackoff.rejectsCredentials("AccountNotFound"))
        assertFalse(ShareLoginBackoff.rejectsCredentials("SSO_InternalError"))
        assertFalse(ShareLoginBackoff.rejectsCredentials("unknown"))
    }
}
