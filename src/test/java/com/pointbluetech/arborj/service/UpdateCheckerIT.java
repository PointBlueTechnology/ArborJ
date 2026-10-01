package com.pointbluetech.arborj.service;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hits the real Cloudflare Worker that backs the in-app update check.
 * Runs during the Failsafe `integration-test` phase (`mvn verify`), not
 * during the normal `test` phase, so offline builds don't fail.
 */
class UpdateCheckerIT {

    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit already initialized by another test — fine.
        }
    }

    @Test
    @DisplayName("older version -> update_available=true, latestVersion populated")
    void detectsUpdateForOldVersion() throws InterruptedException {
        UpdateChecker.resetSession();
        UpdateChecker checker = new UpdateChecker();

        CountDownLatch latch = new CountDownLatch(1);
        checker.updateAvailableProperty().addListener((o, was, now) -> {
            if (Boolean.TRUE.equals(now)) latch.countDown();
        });

        checker.checkAsync("1.0.0");

        assertTrue(latch.await(15, TimeUnit.SECONDS),
                "expected updateAvailable to flip true within 15s");
        assertFalse(checker.checkFailedProperty().get(), "checkFailed should stay false on success");
        assertNotNull(checker.latestVersionProperty().get());
        assertFalse(checker.latestVersionProperty().get().isBlank(),
                "latestVersion should be populated");
        assertNotNull(checker.downloadUrlProperty().get(),
                "platform-specific download URL should be populated");
    }

    @Test
    @DisplayName("current version -> no update, check did not fail")
    void currentVersionReportsNoUpdate() throws InterruptedException {
        UpdateChecker.resetSession();
        UpdateChecker checker = new UpdateChecker();

        // We're asking about the currently-released version, so the server should say
        // update_available=false. Give the background request enough time to complete,
        // then verify neither the "update available" nor the "failed" flag got set.
        String currentVersion = com.pointbluetech.arborj.ArborJApp.APP_VERSION;
        checker.checkAsync(currentVersion);

        Thread.sleep(8_000);

        assertFalse(checker.updateAvailableProperty().get(),
                "same-version query should not flag updateAvailable");
        assertFalse(checker.checkFailedProperty().get(),
                "checkFailed should be false when the server replies 200");
    }
}
