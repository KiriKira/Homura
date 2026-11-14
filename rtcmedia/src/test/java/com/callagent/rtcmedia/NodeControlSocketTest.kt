package com.callagent.rtcmedia

import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class NodeControlSocketTest {
    @After fun close(){NodeControlSocket.clearForTests()}
    @Test fun ordinaryNodesAndUnnegotiatedPathsRetainHttpFallback() {
        assertNull(NodeControlSocket.request("https://example.org","opaque","GET","/rtc/control/calls",null))
        NodeControlSocket.observed("https://example.org",setOf("rtc_control_ws_v1"))
        assertNull(NodeControlSocket.request("https://example.org","opaque","POST","/admin/tokens",JSONObject()))
    }
    @Test fun idleSnapshotsAndEmptyCommandPollsReuseSocketAndRefreshRotatesInPlace() {
        MockWebServer().use { server->
            val messages=AtomicInteger();val sockets=AtomicInteger();var remote:WebSocket?=null
            val opened=CountDownLatch(1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onOpen(ws:WebSocket,response:Response){sockets.incrementAndGet();remote=ws;opened.countDown();ws.send("""{"version":1,"event":"snapshot","calls":[]}""")}
                override fun onMessage(ws:WebSocket,text:String){
                    messages.incrementAndGet();val r=JSONObject(text)
                    val body=when(r.getString("path")){
                        "/auth/refresh"->JSONObject().put("access_token","next-token")
                        "/rtc/control/calls"->JSONObject().put("version",1).put("calls",JSONArray())
                        else->JSONObject().put("commands",JSONArray())
                    }
                    ws.send(JSONObject().put("version",1).put("request_id",r.getString("request_id")).put("status",200).put("body",body).toString())
                }
            }))
            val base=server.url("/").toString().trimEnd('/');NodeControlSocket.observed(base,setOf("rtc_control_ws_v1"))
            assertNotNull(NodeControlSocket.request(base,"first-token","GET","/gateways/g/commands?limit=100",null))
            assertTrue(opened.await(2,TimeUnit.SECONDS));NodeControlSocket.request(base,"first-token","GET","/gateways/g/commands?limit=100",null);val before=messages.get()
            repeat(24*60*2){assertNotNull(NodeControlSocket.request(base,"first-token","GET","/gateways/g/commands?limit=100",null))}
            assertEquals(before,messages.get())
            assertNotNull(NodeControlSocket.request(base,"first-token","POST","/auth/refresh",JSONObject().put("refresh_token","opaque"),idempotencyKey="same-retry-key"))
            assertNotNull(NodeControlSocket.request(base,"next-token","GET","/gateways/g/commands?limit=100",null))
            assertEquals(1,sockets.get());assertEquals(1,server.requestCount)
            remote!!.close(1008,"revoked")
            Thread.sleep(100)
            assertNull(NodeControlSocket.request(base,"next-token","GET","/gateways/g/commands?limit=100",null,100))
        }
    }
    @Test fun rateLimitDoesNotTriggerHttpOrReconnectStorm() {
        MockWebServer().use { server->
            val requests=AtomicInteger()
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(ws:WebSocket,text:String){requests.incrementAndGet();val r=JSONObject(text);ws.send(JSONObject().put("version",1).put("request_id",r.getString("request_id")).put("status",429).put("retry_after",60).put("body",JSONObject().put("error",JSONObject().put("code","RATE_LIMITED"))).toString())}
            }))
            val base=server.url("/").toString().trimEnd('/');NodeControlSocket.observed(base,setOf("rtc_control_ws_v1"))
            repeat(5){try{NodeControlSocket.request(base,"token","POST","/rtc/control/capability",JSONObject());fail()}catch(e:MediaApiException){assertEquals(429,e.status)}}
            assertEquals(1,requests.get());assertEquals(1,server.requestCount)
        }
    }
    @Test fun serverSnapshotsNeverSubstituteForBusinessLeaseRenewals() {
        var time=100_000_000_000L;NodeControlSocket.clock={time}
        MockWebServer().use{server->
            val messages=AtomicInteger();var remote:WebSocket?=null
            val calls=JSONArray().put(JSONObject().put("state","connecting"))
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onOpen(ws:WebSocket,response:Response){remote=ws}
                override fun onMessage(ws:WebSocket,text:String){messages.incrementAndGet();val request=JSONObject(text);ws.send(JSONObject().put("version",1).put("request_id",request.getString("request_id")).put("status",200).put("body",JSONObject().put("version",1).put("calls",calls)).toString())}
            }))
            val base=server.url("/").toString().trimEnd('/');NodeControlSocket.observed(base,setOf("rtc_control_ws_v1"))
            assertNotNull(NodeControlSocket.request(base,"token","GET","/rtc/control/calls",null));assertEquals(1,messages.get())
            time+=6_000_000_000L
            val pushed=CountDownLatch(1);NodeControlSocket.listen(base,"lease-test"){pushed.countDown()}
            remote!!.send(JSONObject().put("version",1).put("event","snapshot").put("calls",calls).toString())
            assertTrue(pushed.await(2,TimeUnit.SECONDS))
            assertNotNull(NodeControlSocket.request(base,"token","GET","/rtc/control/calls",null));assertEquals(2,messages.get())
        }
    }

    @Test fun connectionAdmissionLimitAllowsHttpLeaseFallback() {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","30").setBody("""{"error":{"code":"CONTROL_CONNECTION_LIMIT"}}"""))
            val base=server.url("/").toString().trimEnd('/');NodeControlSocket.observed(base,setOf("rtc_control_ws_v1"))
            assertNull(NodeControlSocket.request(base,"token","GET","/rtc/control/calls",null))
            assertFalse(NodeControlSocket.limited(base,"/rtc/control/calls",null))
            assertNull(NodeControlSocket.request(base,"next-token","GET","/rtc/control/calls",null))
            assertEquals(1,server.requestCount)
        }
    }

}
