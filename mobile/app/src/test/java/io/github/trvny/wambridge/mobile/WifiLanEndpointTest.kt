package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiLanEndpointTest {
    private val bound = WifiLan.Endpoint(11L, "10.0.0.117")

    @Test
    fun sameAndroidNetworkAndAddressNeedsNoRebind() {
        assertEquals(WifiLan.EndpointChange.STABLE, WifiLan.endpointChange(bound, setOf(bound)))
    }

    @Test
    fun newWifiNetworkRebindsEvenWhenDhcpAddressMatches() {
        val newBand = WifiLan.Endpoint(22L, "10.0.0.117")
        assertEquals(WifiLan.EndpointChange.MOVED, WifiLan.endpointChange(bound, setOf(newBand)))
    }

    @Test
    fun changedLocalAddressRequiresEndpointRebind() {
        val moved = WifiLan.Endpoint(11L, "10.0.0.121")
        assertEquals(WifiLan.EndpointChange.MOVED, WifiLan.endpointChange(bound, setOf(moved)))
    }

    @Test
    fun missingWifiMarksBoundEndpointAsLost() {
        assertEquals(WifiLan.EndpointChange.LOST, WifiLan.endpointChange(bound, emptySet()))
    }

    @Test
    fun serviceWithoutEndpointIsStillUnbound() {
        assertEquals(WifiLan.EndpointChange.UNBOUND, WifiLan.endpointChange(null, setOf(bound)))
    }
}
