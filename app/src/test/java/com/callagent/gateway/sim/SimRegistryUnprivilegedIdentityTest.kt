package com.callagent.gateway.sim

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.Settings
import android.telephony.SubscriptionManager
import com.callagent.gateway.data.CredentialStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

/**
 * Without root or a privileged install Android hides the ICCID. A binding the
 * user confirmed must then survive reboots and processes that cannot read it,
 * while the same subscription is present, and still fail when it is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [SimRegistryUnprivilegedIdentityTest.Credentials::class])
class SimRegistryUnprivilegedIdentityTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val simId = "9d581e56-b6c5-451c-b5de-4b2aedd9fc77"
    private val prefs get() = context.getSharedPreferences("sim_registry_v1", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        boot(7)
        prefs.edit().clear().putLong("mapping_revision", 1)
            .putString("server_owner_id", "gateway-identity")
            .putString("server_owner_control_base_url", "https://control.example.test").commit()
    }

    @After
    fun tearDown() { prefs.edit().clear().commit() }

    @Test
    fun aLocalConfirmationWithoutIccidSurvivesARebootWithTheSameSubscription() {
        subscription(iccId = "")
        assertEquals(SimRegistry.IdentityState.LOCAL_CONFIRMED, confirm().identityState)
        boot(8)
        val mapping = SimRegistry.snapshot(context).mappings.single()
        assertEquals(SimRegistry.IdentityState.LOCAL_CONFIRMED, mapping.identityState)
        assertTrue(mapping.smsAvailable)
    }

    @Test
    fun anotherSubscriptionAfterARebootStillNeedsConfirmation() {
        subscription(iccId = "")
        confirm()
        boot(8)
        subscription(iccId = "", mcc = "460", mnc = "01")
        val mapping = SimRegistry.snapshot(context).mappings.single()
        assertEquals(SimRegistry.IdentityState.UNVERIFIED, mapping.identityState)
        assertFalse(mapping.smsAvailable)
    }

    @Test
    fun aFingerprintVerifiedBindingStaysVerifiedWhereTheIccidCannotBeRead() {
        subscription(iccId = "8901234567890123456")
        assertEquals(SimRegistry.IdentityState.FINGERPRINT_VERIFIED, confirm().identityState)
        // The app process lacks the privilege the confirming one had.
        subscription(iccId = "")
        assertEquals(SimRegistry.IdentityState.FINGERPRINT_VERIFIED, SimRegistry.snapshot(context).mappings.single().identityState)
        // A readable ICCID that differs is still another card.
        subscription(iccId = "8909999999999999999")
        assertEquals(SimRegistry.IdentityState.UNVERIFIED, SimRegistry.snapshot(context).mappings.single().identityState)
    }

    @Test
    fun aBindingConfirmedBeforeTheUpgradeRecordsItsSubscriptionWhileStillValid() {
        subscription(iccId = "")
        val legacy = JSONObject().put("sim_id", simId).put("sub_id", 1).put("slot_index", 0)
            .put("fingerprint", "").put("identity_state", "LOCAL_CONFIRMED")
            .put("confirmed_boot_count", 7).put("phone_account", "")
        prefs.edit().putString("mappings", JSONArray().put(legacy).toString()).commit()
        SimRegistry.snapshot(context)
        boot(8)
        assertEquals(SimRegistry.IdentityState.LOCAL_CONFIRMED, SimRegistry.snapshot(context).mappings.single().identityState)
    }

    @Test
    fun theSubscriptionRuleIsOnlyAnExtensionOfALocalConfirmation() {
        assertFalse(SimRegistry.Policy.sameSubscription(null, "token"))
        assertFalse(SimRegistry.Policy.sameSubscription("token-a", "token-b"))
        assertTrue(SimRegistry.Policy.sameSubscription("token-a", "token-a"))
        assertFalse(SimRegistry.Policy.localConfirmationExpired(
            SimRegistry.IdentityState.LOCAL_CONFIRMED, null, 7, 8, sameSubscription = true))
        assertTrue(SimRegistry.Policy.localConfirmationExpired(
            SimRegistry.IdentityState.LOCAL_CONFIRMED, null, 7, 8, sameSubscription = false))
    }

    private fun confirm() = SimRegistry.confirmMappings(context, listOf(SimRegistry.SimBindingConfirmation(simId, 1)), 2).single()

    private fun boot(count: Int) = Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, count)

    private fun subscription(iccId: String, mcc: String = "310", mnc: String = "260") {
        val info = SubscriptionInfoBuilder.newBuilder().setId(1).setSimSlotIndex(0).setIccId(iccId)
            .setMcc(mcc).setMnc(mnc).setDisplayName("SIM 1").setCarrierName("Test").buildSubscriptionInfo()
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(listOf(info))
    }

    @Implements(value = CredentialStore::class, isInAndroidSdk = false)
    class Credentials {
        @Implementation
        fun load(context: Context): CredentialStore.Session = CredentialStore.Session(
            "gateway-identity", "https://control.example.test", "test-access", "2030-01-01T00:00:00Z",
            "test-refresh", "2031-01-01T00:00:00Z"
        )
    }
}
