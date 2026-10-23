package app.wakeme.safety

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocationSnapshotTest {
    @Test fun denialDoesNotReadLocationOrStartSharing() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        shadowOf(context as Application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertNull(locationSnapshot(context))
        assertNull(LocationShareService.active.value)
    }
    @Test fun anUnknownStaleFutureOrInvalidAccuracyFixCannotBeReportedAsCurrent() {
        val now = 1_000_000L
        val location = Location("gps").apply { time = now; latitude = 51.5; longitude = -0.1 }
        assertNull(freshSnapshot(location, now))
        location.accuracy = 12f
        assertSame(location, freshSnapshot(location, now))
        location.time = now - 120_001; assertNull(freshSnapshot(location, now))
        location.time = now + 1000; assertNull(freshSnapshot(location, now))
        location.time = now; location.accuracy = Float.NaN; assertNull(freshSnapshot(location, now))
        location.accuracy = Float.POSITIVE_INFINITY; assertNull(freshSnapshot(location, now))
    }
}
