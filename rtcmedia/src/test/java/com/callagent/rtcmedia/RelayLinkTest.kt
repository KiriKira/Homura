package com.callagent.rtcmedia
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
class RelayLinkTest {
 private fun link(base:String="https://relay.example:8443/base",token:String="v1.opaque-secret")="gsm2sip://relay?url="+URLEncoder.encode(base,"UTF-8")+"&token="+URLEncoder.encode(token,"UTF-8")
 @Test fun preservesOpaqueCredentialsAndCustomHttpsBase(){val source=RelayRepository.parseLink(link());assertEquals("v1.opaque-secret",source.getString("token"));assertEquals("https://relay.example:8443/base",source.getString("url"));assertTrue(source.getBoolean("enabled"))}
 @Test fun rejectsAmbiguousOrUnsafeImports(){for(value in listOf(link()+"&token=second",link()+"&unknown=1",link()+"#secret",link("http://relay.example"),link("https://user:pass@relay.example"),link("https://relay.example?token=1"),link("https://relay.example#secret"),link(token="line\nsecret"),link().replace("://relay?","://relay:80?"))){try{RelayRepository.parseLink(value);fail("accepted malformed link")}catch(_:IllegalArgumentException){}}}
}
