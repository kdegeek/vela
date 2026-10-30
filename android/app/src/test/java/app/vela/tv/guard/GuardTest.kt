package app.vela.tv.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardTest {
    @Test
    fun `money words and prices are purchases`() {
        for (t in listOf("Rent HD \$3.99", "Buy", "Start free trial", "Subscribe", "Upgrade now", "€4,99", "Add to cart")) {
            assertEquals(t, Risk.PURCHASE, Guard.press(t, "com.x")?.risk)
        }
    }

    @Test
    fun `everyday buttons pass`() {
        for (t in listOf("Play", "Resume", "Episode 4", "Search", "Settings", "Subtitles", "Watch trailer", "Buyers Club", "Next")) {
            assertNull(t, Guard.press(t, "com.x"))
        }
    }

    @Test
    fun `accounts, installs and resets`() {
        assertEquals(Risk.ACCOUNT, Guard.press("Sign out", "com.x")?.risk)
        assertEquals(Risk.ACCOUNT, Guard.press("Change PIN", "com.x")?.risk)
        assertEquals(Risk.INSTALL, Guard.press("Install", "com.x")?.risk)
        assertEquals(Risk.INSTALL, Guard.press("Get", "com.amazon.venezia")?.risk)
        assertNull(Guard.press("Get", "com.plexapp.android"))
        assertEquals(Risk.INSTALL, Guard.press(null, "com.android.packageinstaller")?.risk)
        assertEquals(Risk.RESET, Guard.press("Factory data reset", "com.android.tv.settings")?.risk)
    }

    @Test
    fun `security devices need an OK, lights do not`() {
        assertEquals(Risk.SECURITY, Guard.homeService("lock", "unlock", listOf("lock.front"))?.risk)
        assertEquals(Risk.SECURITY, Guard.homeService("cover", "open_cover", listOf("cover.garage_door"))?.risk)
        assertEquals(Risk.SECURITY, Guard.homeService("alarm_control_panel", "alarm_disarm", emptyList())?.risk)
        assertNull(Guard.homeService("light", "turn_off", listOf("light.living_room")))
        assertNull(Guard.homeService("media_player", "volume_set", listOf("media_player.soundbar")))
        assertNull(Guard.homeService("scene", "turn_on", listOf("scene.movie_mode")))
        assertEquals(Risk.SECURITY, Guard.homeService("script", "turn_on", listOf("script.unlock_front_door"))?.risk)
        assertEquals(Risk.SECURITY, Guard.homeService("cover", "open_cover", emptyList())?.risk)
        assertEquals(Risk.SECURITY, Guard.homeService("shell_command", "run", emptyList())?.risk)
        assertEquals(Risk.RESET, Guard.homeService("homeassistant", "restart", emptyList())?.risk)
    }

    @Test
    fun `store and payment links`() {
        assertEquals(Risk.INSTALL, Guard.uri("market://details?id=com.x")?.risk)
        assertEquals(Risk.PURCHASE, Guard.uri("https://www.paypal.com/checkoutnow?token=1")?.risk)
        assertEquals(Risk.INSTALL, Guard.uri("MARKET://details?id=com.x")?.risk)
        assertEquals(Risk.INSTALL, Guard.uri("intent://x#Intent;package=com.android.vending;end")?.risk)
        assertEquals(Risk.INSTALL, Guard.uri("https://play.google.com/store/apps/details?id=com.x")?.risk)
        assertEquals(Risk.INSTALL, Guard.uri("https://example.com/app.apk")?.risk)
        assertEquals(Risk.INSTALL, Guard.uri("https://short.link/x", handler = "com.amazon.venezia")?.risk)
        assertNull(Guard.uri("https://www.youtube.com/watch?v=abc"))
        assertNull(Guard.uri("plex://play/?metadataKey=1", handler = "com.plexapp.android"))
    }
}
