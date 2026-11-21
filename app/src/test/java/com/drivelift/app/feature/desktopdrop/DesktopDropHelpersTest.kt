package com.drivelift.app.feature.desktopdrop

import com.drivelift.app.feature.desktopdrop.data.DropPathSanitizer
import com.drivelift.app.feature.desktopdrop.model.DropJson
import com.drivelift.app.feature.desktopdrop.network.LanNetworkMonitor
import com.drivelift.app.feature.desktopdrop.network.LocalHotspotController
import com.drivelift.app.feature.desktopdrop.qr.QrBitmapGenerator
import com.drivelift.app.feature.desktopdrop.server.SessionAuthManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesktopDropHelpersTest {

    @Test
    fun privateLanIpFilterAllowsRfc1918SubnetsAndRejectsPublicAndLoopbackByDefault() {
        assertTrue(SessionAuthManager.isPrivateLanIp("192.168.1.42"))
        assertTrue(SessionAuthManager.isPrivateLanIp("10.0.0.15"))
        assertTrue(SessionAuthManager.isPrivateLanIp("172.16.5.9"))
        assertTrue(SessionAuthManager.isPrivateLanIp("::ffff:192.168.43.1"))
        assertTrue(SessionAuthManager.isPrivateLanIp("/192.168.1.2"))

        assertFalse(SessionAuthManager.isPrivateLanIp("8.8.8.8"))
        assertFalse(SessionAuthManager.isPrivateLanIp("172.32.0.1"))
        assertFalse(SessionAuthManager.isPrivateLanIp("127.0.0.1", allowLoopback = false))
        assertTrue(SessionAuthManager.isPrivateLanIp("127.0.0.1", allowLoopback = true))
    }

    @Test
    fun pathSanitizerBlocksTraversalAndNormalizesWebkitRelativePath() {
        assertEquals("secret.txt", DropPathSanitizer.sanitizeFilename("../../secret.txt"))
        assertEquals("drop_file", DropPathSanitizer.sanitizeFilename("..."))
        assertEquals(
            listOf("Vacation", "2026"),
            DropPathSanitizer.sanitizeRelativeFolderSegments("../Vacation/./2026/IMG_1.jpg", "IMG_1.jpg"),
        )
    }

    @Test
    fun wifiQrPayloadEscapesSpecialCharactersAndQrBitmapGeneratesModules() {
        val payload = LocalHotspotController.buildWifiQrPayload("Pixel;Net", "pass:word\\12")
        assertEquals("""WIFI:T:WPA;S:Pixel\;Net;P:pass\:word\\12;;""", payload)

        val bmp = QrBitmapGenerator.generate("http://192.168.1.55:8080/?token=ABCD2345", sizePx = 256)
        assertNotNull(bmp)
        assertEquals(256, bmp!!.width)
        assertEquals(256, bmp.height)
    }

    @Test
    fun excludedNetworkInterfacesCoverVpnAndCellularAndDropJsonParsesEscapes() {
        assertTrue(LanNetworkMonitor.isExcludedInterface("tun0"))
        assertTrue(LanNetworkMonitor.isExcludedInterface("wg0"))
        assertTrue(LanNetworkMonitor.isExcludedInterface("rmnet_data0"))
        assertFalse(LanNetworkMonitor.isExcludedInterface("wlan0"))
        assertFalse(LanNetworkMonitor.isExcludedInterface("swlan0"))

        val parsed = DropJson.parseObject("""{"filename":"clip \"1\".mp4","totalSize":1048576,"resumed":true}""")
        assertEquals("""clip "1".mp4""", parsed["filename"])
        assertEquals(1048576L, parsed["totalSize"])
        assertEquals(true, parsed["resumed"])
    }
}
