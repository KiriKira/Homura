package com.callagent.gateway.service

import android.net.ConnectivityManager
import android.os.SystemClock
import com.callagent.gateway.data.CredentialStore
import com.callagent.gateway.net.WakeSessionIdentity
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.Provider
import java.security.Security
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class GatewayWakeRecoveryTest {
    private val app=RuntimeEnvironment.getApplication()
    private fun field(name:String)=GatewayService::class.java.getDeclaredField(name).apply{isAccessible=true}
    private fun method(name:String,vararg types:Class<*>)=GatewayService::class.java.getDeclaredMethod(name,*types).apply{isAccessible=true}
    private var previous:Provider?=null
    private fun session(base:String,token:String="old-token")=CredentialStore.Session("00000000-0000-4000-8000-000000000001",base,token,"2099-01-01T00:00:00Z","refresh-token","2099-02-01T00:00:00Z")
    private fun seed(value:CredentialStore.Session)=CredentialStore.save(app,value)
    @Before fun setup(){
        previous=Security.getProvider("AndroidKeyStore");Security.removeProvider("AndroidKeyStore")
        TestAndroidKeyStoreBacking.keys.clear();Security.insertProviderAt(RobolectricAndroidKeyStoreProvider(),1)
        app.getSharedPreferences("gateway",0).edit().clear().putBoolean("autoconnect",true).putBoolean("background_opted_in",true).putLong("background_last_explicit_enable_at",System.currentTimeMillis()).commit()
    }
    @After fun cleanup(){
        app.getSharedPreferences("gateway",0).edit().clear().commit();CredentialStore.clear(app)
        Security.removeProvider("AndroidKeyStore");previous?.let{Security.insertProviderAt(it,1)}
    }
    private fun ready(service:GatewayService){
        field("foregroundStarted").setBoolean(service,true)
        // Hold the independent HTTP control loop outside this wake-channel test.
        (field("controlLoopActive").get(service) as AtomicBoolean).set(true)
    }
    private fun reconcile(service:GatewayService,value:CredentialStore.Session)=method("reconcileWakeConnection",CredentialStore.Session::class.java,Boolean::class.javaPrimitiveType!!).invoke(service,value,false)

    @Test fun networkAvailableConnectsImmediatelyDespiteMinutesOfScheduledRetry() {
        MockWebServer().use{server->
            val opened=CountDownLatch(1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onOpen(ws:WebSocket,response:Response){opened.countDown()}}))
            val current=session(server.url("/").toString().trimEnd('/'));seed(current)
            val controller=Robolectric.buildService(GatewayService::class.java).create();val service=controller.get()
            try{
                ready(service);assertEquals(current,CredentialStore.load(service));assertEquals(true,method("wakeRuntimeAllowed").invoke(service));field("wakeIdentity").set(service,WakeSessionIdentity.from(current));field("wakeRetryMs").setLong(service,300_000L)
                method("scheduleWakeRetryLocked",Long::class.javaPrimitiveType!!,WakeSessionIdentity::class.java).invoke(service,field("wakeGeneration").getLong(service),WakeSessionIdentity.from(current))
                val pending=field("wakeRetryFuture").get(service) as ScheduledFuture<*>
                assertTrue(pending.getDelay(TimeUnit.SECONDS)>120)
                (field("networkCallback").get(service) as ConnectivityManager.NetworkCallback).onAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(903))
                assertTrue("network recovery must cancel the ordinary retry",pending.isCancelled)
                assertTrue("network recovery must issue the wake handshake now; requests="+server.requestCount,opened.await(2,TimeUnit.SECONDS))
                assertTrue(pending.isCancelled)
                assertEquals(300_000L,field("wakeRetryMs").getLong(service))
            }finally{controller.destroy()}
        }
    }

    @Test fun refreshedIdentityReconnectsImmediatelyButRejectedTokenRemainsBlocked() {
        MockWebServer().use{server->
            val opened=CountDownLatch(1)
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onOpen(ws:WebSocket,response:Response){opened.countDown()}}))
            val old=session(server.url("/").toString().trimEnd('/'));seed(old)
            val controller=Robolectric.buildService(GatewayService::class.java).create();val service=controller.get()
            try{
                ready(service);reconcile(service,old)
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(!field("wakeAuthBlocked").getBoolean(service)&&System.nanoTime()<deadline)Thread.sleep(10)
                assertTrue("401 must block the rejected token until refresh",field("wakeAuthBlocked").getBoolean(service))
                field("wakeRetryMs").setLong(service,300_000L)
                reconcile(service,old);assertNull(field("wakeConnection").get(service));assertEquals(1,server.requestCount)
                val rotated=old.copy(accessToken="new-token");seed(rotated);reconcile(service,rotated)
                assertTrue(opened.await(2,TimeUnit.SECONDS))
                assertEquals("Bearer old-token",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
                assertEquals("Bearer new-token",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
                assertFalse(field("wakeAuthBlocked").getBoolean(service))
            }finally{controller.destroy()}
        }
    }

    @Test fun networkAndIdentityRecoveryRespectServerRetryAfter() {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","60"))
            val current=session(server.url("/").toString().trimEnd('/'));seed(current)
            val controller=Robolectric.buildService(GatewayService::class.java).create();val service=controller.get()
            try{
                ready(service);reconcile(service,current)
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(field("wakeRetryAt").getLong(service)==0L&&System.nanoTime()<deadline)Thread.sleep(10)
                assertTrue(field("wakeRetryAt").getLong(service)-SystemClock.elapsedRealtime()>50_000)
                (field("networkCallback").get(service) as ConnectivityManager.NetworkCallback).onAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(904))
                val rotated=current.copy(accessToken="new-token");seed(rotated);reconcile(service,rotated)
                Thread.sleep(300)
                assertEquals(1,server.requestCount)
            }finally{controller.destroy()}
        }
    }
}
