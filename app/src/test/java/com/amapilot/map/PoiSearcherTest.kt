package com.amapilot.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PoiSearcherTest {

    @Test
    fun `normal province city district snippet`() {
        assertEquals(
            "河北省廊坊市广阳区解放道街道新华路43号",
            PoiSearcher.fullAddress("河北省", "廊坊市", "广阳区", "解放道街道新华路43号")
        )
    }

    @Test
    fun `municipality city equals province`() {
        assertEquals(
            "北京市东城区王府井大街301号",
            PoiSearcher.fullAddress("北京市", "北京市", "东城区", "王府井大街301号")
        )
    }

    @Test
    fun `snippet already contains full prefix`() {
        assertEquals(
            "北京市东城区王府井大街301号",
            PoiSearcher.fullAddress("北京市", "北京市", "东城区", "北京市东城区王府井大街301号")
        )
    }

    @Test
    fun `snippet starts with district`() {
        assertEquals(
            "北京市东城区王府井大街301号",
            PoiSearcher.fullAddress("北京市", "北京市", "东城区", "东城区王府井大街301号")
        )
    }

    @Test
    fun `blank snippet yields prefix only`() {
        assertEquals(
            "河北省廊坊市",
            PoiSearcher.fullAddress("河北省", "廊坊市", "", "")
        )
    }

    @Test
    fun `blank parts are skipped`() {
        assertEquals(
            "廊坊市解放道",
            PoiSearcher.fullAddress("", "廊坊市", "", "解放道")
        )
    }
}
