package com.local.autobookkeeping.recognition.accessibility

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityWindowInfo
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualCaptureWindowGuardTest {
    private val guard = ManualCaptureWindowGuard(
        hostPackage = HOST_PACKAGE,
        transientPackages = setOf(SYSTEM_UI_PACKAGE),
    )

    @Test
    fun `pre launch reservation binds the unique external application window proof`() {
        val external = window(id = 17, packageName = WECHAT_PACKAGE, layer = 2)
        val preLaunch = snapshot(
            activePackage = SYSTEM_UI_PACKAGE,
            windows = listOf(
                external,
                window(
                    id = 99,
                    packageName = SYSTEM_UI_PACKAGE,
                    type = AccessibilityWindowInfo.TYPE_SYSTEM,
                    layer = 9,
                    active = true,
                    focused = true,
                ),
            ),
        )

        val reservation = guard.reserve(preLaunch)

        assertEquals(WECHAT_PACKAGE, reservation?.foregroundPackage)
        assertEquals(external, reservation?.externalWindow)
    }

    @Test
    fun `same package multiple application windows cannot be collapsed into platform attribution`() {
        val reservation = guard.reserve(
            snapshot(
                activePackage = SYSTEM_UI_PACKAGE,
                windows = listOf(
                    window(id = 17, packageName = WECHAT_PACKAGE, layer = 1),
                    window(id = 18, packageName = WECHAT_PACKAGE, layer = 2),
                ),
            ),
        )

        assertNull(reservation)
    }

    @Test
    fun `missing or unreadable external application root cannot reserve`() {
        assertNull(guard.reserve(snapshot(activePackage = SYSTEM_UI_PACKAGE, windows = emptyList())))
        assertNull(
            guard.reserve(
                snapshot(
                    activePackage = SYSTEM_UI_PACKAGE,
                    windows = listOf(
                        window(
                            id = 17,
                            packageName = null,
                            rootReadable = false,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `transparent activity can capture only the identical unique pre launch window`() {
        val external = window(id = 17, packageName = "com.example.bank", layer = 2)
        val reservation = requireNotNull(
            guard.reserve(snapshot(SYSTEM_UI_PACKAGE, listOf(external))),
        )
        val current = snapshot(
            activePackage = HOST_PACKAGE,
            windows = listOf(
                external,
                window(
                    id = 44,
                    packageName = HOST_PACKAGE,
                    layer = 8,
                    active = true,
                    focused = true,
                ),
            ),
        )

        assertTrue(guard.isCaptureWindowCurrent(reservation, current))
    }

    @Test
    fun `system UI still active or external window identity change fails closed`() {
        val external = window(id = 17, packageName = WECHAT_PACKAGE, layer = 2)
        val reservation = requireNotNull(
            guard.reserve(snapshot(SYSTEM_UI_PACKAGE, listOf(external))),
        )
        assertFalse(
            guard.isCaptureWindowCurrent(
                reservation,
                snapshot(SYSTEM_UI_PACKAGE, listOf(external)),
            ),
        )
        assertFalse(
            guard.isCaptureWindowCurrent(
                reservation,
                snapshot(
                    HOST_PACKAGE,
                    listOf(
                        external,
                        window(44, HOST_PACKAGE, layer = 8, active = true),
                        window(
                            99,
                            SYSTEM_UI_PACKAGE,
                            type = AccessibilityWindowInfo.TYPE_SYSTEM,
                            layer = 9,
                            focused = true,
                        ),
                    ),
                ),
            ),
        )
        val changes = listOf(
            external.copy(id = 18),
            external.copy(packageName = "com.example.bank"),
            external.copy(displayId = 1),
            external.copy(layer = 3),
            external.copy(bounds = ManualWindowBounds(0, 0, 900, 1900)),
            external.copy(focused = true),
        )
        changes.forEach { changed ->
            assertFalse(
                guard.isCaptureWindowCurrent(
                    reservation,
                    snapshot(
                        HOST_PACKAGE,
                        listOf(
                            changed,
                            window(44, HOST_PACKAGE, layer = 8, active = true, focused = true),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `delivery proof change recycles software bitmap`() {
        val external = window(id = 17, packageName = WECHAT_PACKAGE, layer = 2)
        val reservation = requireNotNull(
            guard.reserve(snapshot(SYSTEM_UI_PACKAGE, listOf(external))),
        )
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        val result = guard.validateDelivery(
            captured = CaptureResult.Success(bitmap, WECHAT_PACKAGE),
            reservation = reservation,
            current = snapshot(
                HOST_PACKAGE,
                listOf(
                    external.copy(bounds = ManualWindowBounds(0, 0, 900, 1900)),
                    window(44, HOST_PACKAGE, layer = 8, active = true, focused = true),
                ),
            ),
        )

        assertEquals(CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY), result)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `same delivery proof returns software bitmap without recycling`() {
        val external = window(id = 17, packageName = WECHAT_PACKAGE, layer = 2)
        val reservation = requireNotNull(
            guard.reserve(snapshot(SYSTEM_UI_PACKAGE, listOf(external))),
        )
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val captured = CaptureResult.Success(bitmap, WECHAT_PACKAGE)

        val result = guard.validateDelivery(
            captured = captured,
            reservation = reservation,
            current = snapshot(
                HOST_PACKAGE,
                listOf(
                    external,
                    window(44, HOST_PACKAGE, layer = 8, active = true, focused = true),
                ),
            ),
        )

        assertEquals(captured, result)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    private companion object {
        const val HOST_PACKAGE = "com.local.autobookkeeping"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val WECHAT_PACKAGE = "com.tencent.mm"

        fun snapshot(
            activePackage: String?,
            windows: List<ManualWindowProof>,
        ) = ManualWindowSnapshot(activePackage, windows)

        fun window(
            id: Int,
            packageName: String?,
            type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
            displayId: Int? = 0,
            layer: Int = 0,
            bounds: ManualWindowBounds = ManualWindowBounds(0, 0, 1080, 2400),
            active: Boolean = false,
            focused: Boolean = false,
            rootReadable: Boolean = true,
        ) = ManualWindowProof(
            id = id,
            packageName = packageName,
            type = type,
            displayId = displayId,
            layer = layer,
            bounds = bounds,
            active = active,
            focused = focused,
            rootReadable = rootReadable,
        )
    }
}
