package com.callagent.gateway.rtc

import com.callagent.gateway.data.GatewayDatabase
import com.callagent.rtcmedia.MediaApi
import com.callagent.rtcmedia.MediaApiException
import com.callagent.rtcmedia.RtcCallControl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class TestCallRecoveryTest {
    @Test fun failedEndRemainsDurablyRetryable() {
        val app = RuntimeEnvironment.getApplication()
        val gateway = UUID.randomUUID().toString()
        val callId = UUID.randomUUID().toString()
        val api = MediaApi { _, _, _ -> throw MediaApiException(503, "NETWORK_UNAVAILABLE") }
        val node = GatewayCallControl.Node(gateway, api, RtcCallControl(api), true)
        val runner = TestCallRunner(app, { true }, {})
        try {
            val type = TestCallRunner::class.java.declaredClasses.single { it.simpleName == "Active" }
            val ctor = type.declaredConstructors.single().apply { isAccessible = true }
            val call = ctor.newInstance(node, UUID.randomUUID().toString(), callId)
            type.getDeclaredField("snapshot").apply { isAccessible = true }.set(call,
                JSONObject().put("call_id",callId).put("state","ringing"))
            TestCallRunner::class.java.getDeclaredField("active").apply { isAccessible = true }.set(runner,call)
            TestCallRunner::class.java.getDeclaredMethod("finish",type,String::class.java).apply { isAccessible = true }
                .invoke(runner,call,"remote_end")
            val queued = GatewayDatabase.get(app).rtcTerminals(gateway)
            println("review: busy=${runner.busy}, durableEndQueue=${queued.size}")
            assertTrue("Failed test-call END must remain queued after local retirement", queued.any { it.first == callId })
        } finally { runner.close() }
    }
    @Test fun restartRecoveryIsolatesNodesAndKeepsActiveTest() {
        val db=GatewayDatabase.get(RuntimeEnvironment.getApplication())
        val node=UUID.randomUUID().toString();val other=UUID.randomUUID().toString()
        val lost=UUID.randomUUID().toString();val active=UUID.randomUUID().toString();val foreign=UUID.randomUUID().toString()
        db.rememberTestCall(lost,node);db.rememberTestCall(active,node);db.rememberTestCall(foreign,other)
        db.recoverTestCalls(node,active)
        assertTrue(db.rtcTerminals(node).any{it.first==lost})
        assertFalse(db.rtcTerminals(node).any{it.first==active})
        assertTrue(db.rtcTerminals(other).isEmpty())
        assertTrue(db.testCreateMayBePending(lost))
        db.recoverTestCalls(node,null)
        assertTrue(db.rtcTerminals(node).any{it.first==active})
    }

    @Test fun versionTenUpgradePreservesDataAndAddsRecoveryTable() {
        val db=GatewayDatabase.get(RuntimeEnvironment.getApplication())
        val sql=db.writableDatabase
        sql.execSQL("DROP TABLE rtc_test_calls")
        try {
            val before=sql.rawQuery("SELECT count(*) FROM call_ledger",null).use{it.moveToFirst();it.getLong(0)}
            db.onUpgrade(sql,10,11)
            assertEquals(before,sql.rawQuery("SELECT count(*) FROM call_ledger",null).use{it.moveToFirst();it.getLong(0)})
            val call=UUID.randomUUID().toString();val node=UUID.randomUUID().toString()
            db.rememberTestCall(call,node)
            assertTrue(db.testCreateMayBePending(call))
            sql.execSQL("UPDATE rtc_test_calls SET created_at=? WHERE call_id=?",arrayOf<Any>(System.currentTimeMillis()-121_000,call))
            assertFalse(db.testCreateMayBePending(call))
            db.recoverTestCalls(node,null)
            assertTrue(db.rtcTerminals(node).any{it.first==call})
        } finally {
            sql.execSQL("CREATE TABLE IF NOT EXISTS rtc_test_calls (call_id TEXT PRIMARY KEY,node_gateway_id TEXT NOT NULL,created_at INTEGER NOT NULL)")
        }
    }

}
