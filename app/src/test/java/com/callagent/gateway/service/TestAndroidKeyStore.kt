package com.callagent.gateway.service

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.*
import javax.crypto.spec.SecretKeySpec

internal object TestAndroidKeyStoreBacking {
    val keys = ConcurrentHashMap<String, Key>()
}

internal class RobolectricAndroidKeyStoreProvider : Provider(
    "AndroidKeyStore", 1.0, "In-memory AndroidKeyStore replacement for Robolectric tests"
) {
    init {
        put("KeyStore.AndroidKeyStore", RobolectricAndroidKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", RobolectricAndroidAesKeyGenerator::class.java.name)
    }
}

public class RobolectricAndroidKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = TestAndroidKeyStoreBacking.keys[alias]
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCreationDate(alias: String): Date? = null
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
        TestAndroidKeyStoreBacking.keys[alias] = key
    }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
        throw UnsupportedOperationException("byte-array key import is not used in this test")
    }
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
    override fun engineDeleteEntry(alias: String) { TestAndroidKeyStoreBacking.keys.remove(alias) }
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(TestAndroidKeyStoreBacking.keys.keys)
    override fun engineContainsAlias(alias: String): Boolean = TestAndroidKeyStoreBacking.keys.containsKey(alias)
    override fun engineSize(): Int = TestAndroidKeyStoreBacking.keys.size
    override fun engineIsKeyEntry(alias: String): Boolean = TestAndroidKeyStoreBacking.keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}

public class RobolectricAndroidAesKeyGenerator : KeyGeneratorSpi() {
    private var random: SecureRandom = SecureRandom()
    private var alias: String? = null

    override fun engineInit(random: SecureRandom?) {
        if (random != null) this.random = random
    }

    override fun engineInit(params: java.security.spec.AlgorithmParameterSpec, random: SecureRandom?) {
        alias = (params as? KeyGenParameterSpec)?.keystoreAlias
        if (random != null) this.random = random
    }

    override fun engineInit(keysize: Int, random: SecureRandom?) {
        if (random != null) this.random = random
    }

    override fun engineGenerateKey(): SecretKey {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return SecretKeySpec(bytes, "AES").also { key -> alias?.let { TestAndroidKeyStoreBacking.keys[it] = key } }
    }
}
