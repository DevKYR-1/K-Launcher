package net.kdt.pojavlaunch.value;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The account rules the launcher builds on: what a Local account is, what a Demo account is,
 * and when the Minecraft session of a Microsoft account has to be renewed.
 */
public class MinecraftAccountTest {
    private static final long NOW = 1_700_000_000_000L;

    private static MinecraftAccount microsoft(String name, long expiresAt) {
        MinecraftAccount account = new MinecraftAccount();
        account.username = name;
        account.accessToken = "a-real-looking-token";
        account.isMicrosoft = true;
        account.expiresAt = expiresAt;
        return account;
    }

    private static MinecraftAccount local(String name) {
        MinecraftAccount account = new MinecraftAccount();
        account.username = name;
        return account; // an access token of "0" and not Microsoft, like the Local login creates it
    }

    @Test
    public void aLocalAccount_isNotMicrosoft_isNotDemo_andHasNoSessionToRenew() {
        MinecraftAccount account = local("Steve_1");
        assertTrue(account.isLocal());
        assertFalse(account.isDemo());
        assertFalse(account.isMicrosoft);
        assertFalse("no Microsoft session, nothing to renew", account.isSessionExpired(NOW));
        assertFalse(account.isSessionExpired(Long.MAX_VALUE));
    }

    @Test
    public void aDemoAccount_isNotLocal_soItCanDownloadIntoTheDemoFolder() {
        MinecraftAccount demo = microsoft("Demo.Player", NOW + 1000);
        assertTrue(demo.isDemo());
        assertFalse(demo.isLocal());
    }

    @Test
    public void aMicrosoftAccountWithGame_isNeitherLocalNorDemo() {
        MinecraftAccount account = microsoft("Notch", NOW + 1000);
        assertFalse(account.isLocal());
        assertFalse(account.isDemo());
    }

    @Test
    public void aMicrosoftSession_expiresAfterItsExpiryTime() {
        MinecraftAccount account = microsoft("Notch", NOW);
        assertFalse("still valid at the very moment", account.isSessionExpired(NOW));
        assertTrue(account.isSessionExpired(NOW + 1));
        assertFalse(account.isSessionExpired(NOW - 60_000));
    }

    @Test
    public void aMicrosoftAccountThatNeverStoredAnExpiryTime_isRenewed() {
        // Accounts saved by old versions have no expiry time at all
        assertTrue(microsoft("Notch", 0).isSessionExpired(NOW));
    }

    @Test
    public void computeSessionExpiry_usesTheLifetimeOfTheServer_minusASafetyMargin() {
        long fiveMinutes = 5 * 60 * 1000L;
        assertEquals(NOW + 86_400_000L - fiveMinutes, MinecraftAccount.computeSessionExpiry(NOW, 86_400));
        assertEquals(NOW + 3_600_000L - fiveMinutes, MinecraftAccount.computeSessionExpiry(NOW, 3_600));
    }

    @Test
    public void computeSessionExpiry_fallsBackToTwentyFourHours_whenTheServerSaysNothing() {
        long expected = MinecraftAccount.computeSessionExpiry(NOW, MinecraftAccount.DEFAULT_SESSION_LIFETIME_SECONDS);
        assertEquals(NOW + 86_400_000L - 5 * 60 * 1000L, expected);
        assertEquals(expected, MinecraftAccount.computeSessionExpiry(NOW, 0));
        assertEquals(expected, MinecraftAccount.computeSessionExpiry(NOW, -1));
    }

    @Test
    public void computeSessionExpiry_neverExpiresBeforeItStarted_forAVeryShortLifetime() {
        // The margin must not eat a short lifetime up: at least half of it stays
        assertEquals(NOW + 30_000L, MinecraftAccount.computeSessionExpiry(NOW, 60));
        assertEquals(NOW + 300_000L, MinecraftAccount.computeSessionExpiry(NOW, 600));
        assertTrue(MinecraftAccount.computeSessionExpiry(NOW, 1) > NOW);
    }

    @Test
    public void aFreshSession_isNotExpired_untilItsLifetimeIsNearlyOver() {
        long expiresAt = MinecraftAccount.computeSessionExpiry(NOW, 86_400);
        MinecraftAccount account = microsoft("Notch", expiresAt);
        assertFalse(account.isSessionExpired(NOW + 60_000));
        assertFalse(account.isSessionExpired(NOW + 23 * 3_600_000L));
        assertTrue(account.isSessionExpired(NOW + 86_400_000L));
    }
}
