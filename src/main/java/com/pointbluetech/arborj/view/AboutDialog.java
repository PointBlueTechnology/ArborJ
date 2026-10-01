package com.pointbluetech.arborj.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * About dialog showing app info, copyright, license, and third-party acknowledgments.
 */
public class AboutDialog {

    private final Stage stage;

    public AboutDialog() {
        this(null);
    }

    public AboutDialog(javafx.stage.Window owner) {
        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("About ArborJ");
        stage.setResizable(false);

        VBox root = new VBox(12);
        root.setPadding(new Insets(24));
        root.setAlignment(Pos.CENTER);

        // Icon
        ImageView icon = new ImageView();
        var iconUrl = getClass().getResource("/com/pointbluetech/arborj/icons/icon-128.png");
        if (iconUrl != null) {
            icon.setImage(new Image(iconUrl.toExternalForm()));
            icon.setFitWidth(96);
            icon.setFitHeight(96);
        }

        // App name and version
        Label appName = new Label("ArborJ");
        appName.setFont(Font.font("System", FontWeight.BOLD, 24));

        Label version = new Label("Version " + com.pointbluetech.arborj.ArborJApp.APP_VERSION);
        version.setStyle("-fx-text-fill: -color-fg-muted;");

        Label subtitle = new Label("Cross-Platform LDAP Browser");
        subtitle.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13;");

        // Copyright
        Label copyright = new Label("\u00A9 2026 Pointblue Technology");
        copyright.setStyle("-fx-font-size: 12;");

        Label license = new Label("Open source under the MIT License");
        license.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");

        // Buttons
        Hyperlink licenseLink = new Hyperlink("View License");
        licenseLink.setOnAction(e -> showTextFile("LICENSE.txt", "License"));

        Hyperlink thirdPartyLink = new Hyperlink("Open Source Licenses");
        thirdPartyLink.setOnAction(e -> showTextFile("THIRD-PARTY-LICENSES.txt",
                "Third-Party Software Licenses"));

        Hyperlink supportLink = new Hyperlink("Support & Product Page");
        supportLink.setOnAction(e -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://www.pointbluetech.com/arborj/")); }
            catch (Exception ignored) {}
        });

        HBox links = new HBox(16, licenseLink, thirdPartyLink);
        links.setAlignment(Pos.CENTER);

        Button closeBtn = new Button("Close");
        closeBtn.setDefaultButton(true);
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());

        root.getChildren().addAll(icon, appName, version, subtitle,
                new Separator(), copyright, license, supportLink, links,
                new Separator(), closeBtn);

        Scene scene = new Scene(root, 400, 450);
        stage.setScene(scene);

        // Set icon on the dialog stage too
        if (iconUrl != null) {
            stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }
    }

    public void showAndWait() {
        stage.showAndWait();
    }

    private void showTextFile(String filename, String title) {
        String content = loadResourceOrFile(filename);
        if (content == null) {
            new Alert(Alert.AlertType.ERROR, "Could not load " + filename).showAndWait();
            return;
        }

        Stage textStage = new Stage();
        textStage.initModality(Modality.APPLICATION_MODAL);
        textStage.initOwner(stage);
        textStage.setTitle(title);

        TextArea textArea = new TextArea(content);
        textArea.setEditable(false);
        textArea.setFont(Font.font("monospaced", 12));
        textArea.setWrapText(true);

        Button closeBtn = new Button("Close");
        closeBtn.setDefaultButton(true);
        closeBtn.setOnAction(e -> textStage.close());

        HBox buttons = new HBox(closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8));

        VBox root = new VBox(textArea, buttons);
        VBox.setVgrow(textArea, Priority.ALWAYS);

        Scene scene = new Scene(root, 600, 500);
        textStage.setScene(scene);
        textStage.showAndWait();
    }

    private String loadResourceOrFile(String filename) {
        // Try classpath first
        InputStream is = getClass().getResourceAsStream("/com/pointbluetech/arborj/" + filename);
        if (is == null) {
            // Try project root (for development)
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(filename));
            } catch (Exception e) {
                return null;
            }
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return null;
        }
    }
}
