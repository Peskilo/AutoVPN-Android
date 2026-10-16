package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRetryPolicyTest {
    @Test
    fun retriesTransientTransportFailures() {
        assertTrue(isRetryableSubscriptionFetchError(SubscriptionError.Network))
        assertTrue(isRetryableSubscriptionFetchError(SubscriptionError.Timeout))
    }

    @Test
    fun retriesTemporaryHttpFailuresButNotRateLimitsOrPermanentErrors() {
        listOf(408, 425, 500, 502, 503, 504, 599).forEach { code ->
            assertTrue("HTTP $code should be retried", isRetryableSubscriptionFetchError(SubscriptionError.Http(code, "provider")))
        }

        listOf(400, 401, 403, 404, 429).forEach { code ->
            assertFalse("HTTP $code should not be retried automatically", isRetryableSubscriptionFetchError(SubscriptionError.Http(code, "provider")))
        }
    }

    @Test
    fun doesNotRetryInvalidOrUnusableSubscriptions() {
        assertFalse(isRetryableSubscriptionFetchError(SubscriptionError.BadUrl))
        assertFalse(isRetryableSubscriptionFetchError(SubscriptionError.InsecureTransport))
        assertFalse(isRetryableSubscriptionFetchError(SubscriptionError.ConfigRejected))
        assertFalse(isRetryableSubscriptionFetchError(SubscriptionError.EmptyResult()))
    }
}
