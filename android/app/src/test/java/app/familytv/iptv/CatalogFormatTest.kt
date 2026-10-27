package app.familytv.iptv

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.familytv.data.AppDb
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogFormatTest {
    @Test
    fun anUpdateThatChangesMatchingLoadsTheListsAgainOnce() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
        val marks = ctx.getSharedPreferences("catalog-test", Context.MODE_PRIVATE)
        val catalog = Catalog(db, marks)
        // A finished load from a build before FORMAT (round 3: the Kitchen kept its old lists).
        marks.edit().putBoolean("complete", true).commit()
        assertFalse(catalog.complete)
        marks.edit().putInt("format", Catalog.FORMAT).commit()
        assertTrue(catalog.complete)
        db.close()
    }
}
