package com.callagent.gateway.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayPrefetchTest {
    @Test fun ownerSeenInABindingIsKeptPerNode() {
        val app = RuntimeEnvironment.getApplication()
        val node = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        assertNull(RelayPrefetch.owner(app, node))
        RelayPrefetch.learned(app, node, "owner-a")
        assertEquals("owner-a", RelayPrefetch.owner(app, node))
        assertNull(RelayPrefetch.owner(app, other))
        // Persisted: a restarted process still prefetches before its first call.
        assertEquals("owner-a", app.getSharedPreferences("rtc-relay-owners", 0).getString(node, null))
        RelayPrefetch.learned(app, node, "")
        assertEquals("owner-a", RelayPrefetch.owner(app, node))
    }
}
