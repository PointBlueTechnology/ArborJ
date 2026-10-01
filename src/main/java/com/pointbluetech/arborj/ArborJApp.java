package com.pointbluetech.arborj;

import atlantafx.base.theme.*;
import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.view.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.List;
import java.util.prefs.Preferences;

public class ArborJApp extends Application {

    public static final String APP_VERSION = "2.0.8";

    private static final String PREF_THEME = "theme";
    private static final Preferences prefs = Preferences.userNodeForPackage(ArborJApp.class);

    /** Available themes: name -> Theme instance */
    public static final List<ThemeOption> THEMES = List.of(
            new ThemeOption("Nord Light", new NordLight()),
            new ThemeOption("Nord Dark", new NordDark()),
            new ThemeOption("Primer Light", new PrimerLight()),
            new ThemeOption("Primer Dark", new PrimerDark()),
            new ThemeOption("Cupertino Light", new CupertinoLight()),
            new ThemeOption("Cupertino Dark", new CupertinoDark()),
            new ThemeOption("Dracula", new Dracula())
    );

    private static final java.util.List<MainController> activeControllers =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static int windowCount = 0;

    @Override
    public void start(Stage primaryStage) {
        // Apply saved theme (default to Cupertino Dark for good contrast)
        String savedTheme = prefs.get(PREF_THEME, "Cupertino Dark");
        applyTheme(savedTheme);

        // Don't exit when a window closes if others are still open
        javafx.application.Platform.setImplicitExit(false);

        MainController controller = openNewWindow(primaryStage);

        // Show "What's New" on first launch of a new version
        com.pointbluetech.arborj.view.WhatsNewDialog.showIfNew(primaryStage);

        // On macOS, suggest the native App Store version
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            showMacSplash();
        }

        // Auto-open connection dialog after any startup dialogs
        final javafx.stage.Stage ownerStage = primaryStage;
        javafx.application.Platform.runLater(() ->
                new com.pointbluetech.arborj.view.ConnectionDialog(controller, ownerStage).showAndWait());
    }

    /**
     * Open a new independent browser window with its own connection.
     */
    public static void openNewWindow() {
        openNewWindow(new Stage(), true);
    }

    private static MainController openNewWindow(Stage stage) {
        return openNewWindow(stage, false);
    }

    private static MainController openNewWindow(Stage stage, boolean showConnectionDialog) {
        MainController controller = new MainController();
        activeControllers.add(controller);

        MainView mainView = new MainView(controller);

        Scene scene = new Scene(mainView.getRoot(), 1200, 800);

        var stylesheet = ArborJApp.class.getResource("/com/pointbluetech/arborj/styles.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        stage.setTitle("ArborJ \u2014 LDAP Browser");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(500);

        // Cascade new windows by offsetting from the most recent window
        if (windowCount > 0) {
            javafx.stage.Stage existing = javafx.stage.Stage.getWindows().stream()
                    .filter(w -> w instanceof javafx.stage.Stage && w.isShowing() && w != stage)
                    .map(w -> (javafx.stage.Stage) w)
                    .reduce((first, second) -> second) // last opened
                    .orElse(null);
            if (existing != null) {
                stage.setX(existing.getX() + 30);
                stage.setY(existing.getY() + 30);
            }
        }
        windowCount++;

        var iconUrl = ArborJApp.class.getResource("/com/pointbluetech/arborj/icons/icon-256.png");
        if (iconUrl != null) {
            stage.getIcons().add(new javafx.scene.image.Image(iconUrl.toExternalForm()));
        }

        stage.setOnCloseRequest(e -> {
            controller.shutdown();
            activeControllers.remove(controller);
            // Exit app when all windows are closed
            if (activeControllers.isEmpty()) {
                javafx.application.Platform.exit();
            }
        });

        // Update title when connected
        controller.connectedProfileNameProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                stage.setTitle(newVal + " \u2014 ArborJ");
            } else {
                stage.setTitle("ArborJ \u2014 LDAP Browser");
            }
        });

        stage.show();

        if (showConnectionDialog) {
            final javafx.stage.Stage ownerStage = stage;
            javafx.application.Platform.runLater(() ->
                    new com.pointbluetech.arborj.view.ConnectionDialog(controller, ownerStage).showAndWait());
        }
        return controller;
    }

    public static void applyTheme(String themeName) {
        for (ThemeOption opt : THEMES) {
            if (opt.name().equals(themeName)) {
                Application.setUserAgentStylesheet(opt.theme().getUserAgentStylesheet());
                prefs.put(PREF_THEME, themeName);
                return;
            }
        }
        // Fallback
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
    }

    public static String getCurrentTheme() {
        return prefs.get(PREF_THEME, "Cupertino Dark");
    }

    private static final String APP_STORE_URL =
            "https://apps.apple.com/us/app/arbor-ldap-browser/id6759270047?mt=12";

    private void showMacSplash() {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("Arbor for macOS");
        alert.setHeaderText("A native macOS version is available!");
        alert.setContentText(
                "Arbor is available as a native macOS app with full SwiftUI integration, "
                + "iCloud sync, and Keychain support.\n\n"
                + "Get it from the Mac App Store for the best experience on macOS.");

        javafx.scene.control.ButtonType appStoreBtn =
                new javafx.scene.control.ButtonType("Open App Store");
        javafx.scene.control.ButtonType continueBtn =
                new javafx.scene.control.ButtonType("Continue",
                        javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);

        alert.getButtonTypes().setAll(appStoreBtn, continueBtn);

        alert.showAndWait().ifPresent(btn -> {
            if (btn == appStoreBtn) {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(APP_STORE_URL));
                } catch (Exception ignored) {}
            }
        });
    }

    @Override
    public void stop() {
        for (MainController c : activeControllers) {
            c.shutdown();
        }
        activeControllers.clear();
    }

    public static void main(String[] args) {
        launch(args);
    }

    public record ThemeOption(String name, Theme theme) {
        @Override
        public String toString() { return name; }
    }
}
