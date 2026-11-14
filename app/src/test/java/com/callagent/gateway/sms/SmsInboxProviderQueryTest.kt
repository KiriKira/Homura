package com.callagent.gateway.sms

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The system SMS provider only implements the legacy query, so the framework
 * turns our query bundle into selection and sort order on its way in. Going
 * through a real provider catches a bundle the framework cannot convert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class SmsInboxProviderQueryTest {
    private lateinit var provider: LegacyInboxProvider

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(LegacyInboxProvider::class.java, Telephony.Sms.Inbox.CONTENT_URI.authority)
    }

    @Test
    fun maxInboxRowIdSortsNewestFirst() {
        val source = SmsProviderRecovery.ContentResolverInboxSource(RuntimeEnvironment.getApplication())
        assertEquals(42L, source.maxInboxRowId())
        assertEquals("_id DESC", provider.lastSortOrder)
        assertEquals("type=?", provider.lastSelection)
    }

    @Test
    fun pageSortsOldestFirstAndStopsAtTheLimit() {
        val source = SmsProviderRecovery.ContentResolverInboxSource(RuntimeEnvironment.getApplication())
        val rows = source.page(afterRowId = 0, minimumProviderId = 0, scanStartAt = 0, limit = 2)
        assertEquals(listOf(40L, 41L), rows.map { it.rowId })
        assertEquals("_id ASC", provider.lastSortOrder)
    }

    class LegacyInboxProvider : ContentProvider() {
        var lastSortOrder: String? = null
        var lastSelection: String? = null

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            lastSortOrder = sortOrder
            lastSelection = selection
            val columns = projection?.map { it }?.toTypedArray() ?: arrayOf(Telephony.Sms._ID)
            val ids = if (sortOrder == "_id DESC") listOf(42L, 41L, 40L) else listOf(40L, 41L, 42L)
            return MatrixCursor(columns).apply {
                ids.forEach { id ->
                    addRow(columns.map { column ->
                        when (column) {
                            Telephony.Sms._ID -> id
                            Telephony.Sms.ADDRESS -> "+15550100"
                            Telephony.Sms.BODY -> "body $id"
                            Telephony.Sms.DATE, "date_sent" -> 1_700_000_000_000L + id
                            "sub_id" -> 1
                            "protocol" -> 0
                            else -> null
                        }
                    })
                }
            }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
