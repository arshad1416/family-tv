package app.familytv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormFactorTest {
    @Test fun tvWinsOverScreenSize() {
        assertEquals(FormFactor.Tv, FormFactor.of(isTv = true, hasTouch = true, smallestWidthDp = 360))
        assertEquals(FormFactor.Tv, FormFactor.of(isTv = true, hasTouch = false, smallestWidthDp = 540))
    }

    @Test fun noTouchscreenIsTv() {
        // Boxes that don't declare leanback still have no touchscreen; keep them on the D-pad layout.
        assertEquals(FormFactor.Tv, FormFactor.of(isTv = false, hasTouch = false, smallestWidthDp = 960))
    }

    @Test fun phoneAndTabletSplitOnSmallestWidth() {
        assertEquals(FormFactor.Phone, FormFactor.of(isTv = false, hasTouch = true, smallestWidthDp = 411))
        assertEquals(FormFactor.Phone, FormFactor.of(isTv = false, hasTouch = true, smallestWidthDp = 599))
        assertEquals(FormFactor.Tablet, FormFactor.of(isTv = false, hasTouch = true, smallestWidthDp = 600))
        assertEquals(FormFactor.Tablet, FormFactor.of(isTv = false, hasTouch = true, smallestWidthDp = 800))
    }

    @Test fun onlyTvIsNonTouch() {
        assertEquals(false, FormFactor.Tv.touch)
        assertEquals(true, FormFactor.Phone.touch)
        assertEquals(true, FormFactor.Tablet.touch)
    }
}
