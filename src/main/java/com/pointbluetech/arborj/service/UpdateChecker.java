package com.pointbluetech.arborj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.*;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Checks for application updates on startup.
 * Non-blocking, fails silently, once per session.
 */
public class UpdateChecker {

    private static final String VERSION_URL = "https://arborj-downloads.jcombs.workers.dev/version/arborj";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static boolean checked = false;

    private final StringProperty latestVersion = new SimpleStringProperty();
    private final StringProperty downloadUrl = new SimpleStringProperty();
    private final BooleanProperty updateAvailable = new SimpleBooleanProperty(false);
    private final BooleanProperty dismissed = new SimpleBooleanProperty(false);
    private final BooleanProperty checkFailed = new SimpleBooleanProperty(false);
    private final BooleanProperty checking = new SimpleBooleanProperty(false);

    public StringProperty latestVersionProperty() { return latestVersion; }
    public StringProperty downloadUrlProperty() { return downloadUrl; }
    public BooleanProperty updateAvailableProperty() { return updateAvailable; }
    public BooleanProperty dismissedProperty() { return dismissed; }
    public BooleanProperty checkFailedProperty() { return checkFailed; }
    /** True while a check is in flight; flips to false on success or failure. */
    public BooleanProperty checkingProperty() { return checking; }

    public void dismiss() { dismissed.set(true); }

    /** Reset the once-per-session guard (for manual check). */
    public static void resetSession() { checked = false; }

    /**
     * Check for updates asynchronously. Only runs once per session.
     * Fails silently on any error.
     */
    public void checkAsync(String currentVersion) {
        if (checked) return;
        checked = true;
        checkFailed.set(false);
        checking.set(true);

        Thread.startVirtualThread(() -> {
            try {
                String url = VERSION_URL + "?v=" + currentVersion;
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .proxy(ProxySelector.getDefault())
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    Platform.runLater(() -> {
                        checkFailed.set(true);
                        checking.set(false);
                    });
                    return;
                }

                ObjectMapper mapper = new ObjectMapper();
                JsonNode json = mapper.readTree(response.body());

                boolean hasUpdate = json.has("update_available") && json.get("update_available").asBoolean();
                if (!hasUpdate) {
                    Platform.runLater(() -> checking.set(false));
                    return;
                }

                String latest = json.has("latest") ? json.get("latest").asText() : "";
                String dlUrl = getPlatformDownloadUrl(json);

                Platform.runLater(() -> {
                    latestVersion.set(latest);
                    downloadUrl.set(dlUrl);
                    updateAvailable.set(true);
                    checking.set(false);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    checkFailed.set(true);
                    checking.set(false);
                });
            }
        });
    }

    private String getPlatformDownloadUrl(JsonNode json) {
        if (!json.has("downloads")) return "";
        JsonNode downloads = json.get("downloads");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return downloads.has("mac") ? downloads.get("mac").asText() : "";
        if (os.contains("win")) return downloads.has("windows") ? downloads.get("windows").asText() : "";
        return downloads.has("linux") ? downloads.get("linux").asText() : "";
    }
}
