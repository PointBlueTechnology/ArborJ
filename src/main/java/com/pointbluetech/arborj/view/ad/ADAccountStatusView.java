package com.pointbluetech.arborj.view.ad;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.util.ADHelpers;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Dialog displaying Active Directory account lockout and status information
 * for a specific user DN. Provides actions to unlock and enable/disable accounts.
 */
public class ADAccountStatusView extends Dialog<Void> {

    /**
     * Windows FILETIME epoch offset: the number of 100-nanosecond intervals
     * between 1601-01-01 00:00:00 UTC and 1970-01-01 00:00:00 UTC.
     */
    private static final long FILETIME_EPOCH_OFFSET = 116_444_736_000_000_000L;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] STATUS_ATTRIBUTES = {
            "lockoutTime",
            "badPwdCount",
            "badPasswordTime",
            "lastLogon",
            "lastLogonTimestamp",
            "pwdLastSet",
            "accountExpires"
    };

    private static final String[] DISPLAY_LABELS = {
            "Lockout Time",
            "Bad Password Count",
            "Bad Password Time",
            "Last Logon",
            "Last Logon (Replicated)",
            "Password Last Set",
            "Account Expires"
    };

    private final MainController controller;
    private final String dn;
    private final Label[] valueLabels;
    private final Label uacStatusLabel;
    private final Button unlockButton;
    private final Button enableDisableButton;

    public ADAccountStatusView(MainController controller, String dn) {
        this(controller, dn, null);
    }

    public ADAccountStatusView(MainController controller, String dn, javafx.stage.Window owner) {
        this.controller = controller;
        this.dn = dn;

        setTitle("Account Status");
        setHeaderText(dn);
        setResizable(true);
        if (owner != null) initOwner(owner);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(180);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        valueCol.setPrefWidth(350);
        grid.getColumnConstraints().addAll(labelCol, valueCol);

        Font mono = Font.font("monospaced", 12);
        valueLabels = new Label[STATUS_ATTRIBUTES.length];

        int row = 0;

        // UAC status summary
        Label uacHeaderLabel = new Label("Account State:");
        uacHeaderLabel.setStyle("-fx-font-weight: bold;");
        uacStatusLabel = new Label("Loading...");
        uacStatusLabel.setFont(mono);
        grid.add(uacHeaderLabel, 0, row);
        grid.add(uacStatusLabel, 1, row);
        row++;

        grid.add(new Separator(), 0, row++, 2, 1);

        // Attribute rows
        for (int i = 0; i < STATUS_ATTRIBUTES.length; i++) {
            Label nameLabel = new Label(DISPLAY_LABELS[i] + ":");
            nameLabel.setStyle("-fx-font-weight: bold;");

            Label valLabel = new Label("Loading...");
            valLabel.setFont(mono);
            valLabel.setWrapText(true);
            valueLabels[i] = valLabel;

            grid.add(nameLabel, 0, row);
            grid.add(valLabel, 1, row);
            row++;
        }

        // UAC flags section
        grid.add(new Separator(), 0, row++, 2, 1);

        // Action buttons
        unlockButton = new Button("Unlock Account");
        unlockButton.setDisable(true);
        unlockButton.setOnAction(e -> unlockAccount());

        enableDisableButton = new Button("Enable/Disable Account");
        enableDisableButton.setDisable(true);
        enableDisableButton.setOnAction(e -> toggleAccountEnabled());

        HBox buttonBox = new HBox(8, unlockButton, enableDisableButton);
        buttonBox.setPadding(new Insets(8, 0, 0, 0));
        grid.add(buttonBox, 0, row, 2, 1);

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefWidth(600);
        scrollPane.setPrefHeight(400);

        getDialogPane().setContent(scrollPane);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        loadData();
    }

    private void loadData() {
        Thread.ofVirtual().start(() -> {
            try {
                Map<String, List<String>> attrs = controller.getLdapService().fetchAttributes(dn, true);
                int uac = controller.getLdapService().fetchADUserAccountControl(dn);

                Platform.runLater(() -> {
                    // UAC summary
                    boolean disabled = ADHelpers.isAccountDisabled(uac);
                    boolean locked = isLockedOut(attrs);
                    StringBuilder stateText = new StringBuilder();
                    stateText.append(disabled ? "DISABLED" : "Enabled");
                    if (locked) stateText.append(", LOCKED OUT");
                    if (ADHelpers.isPasswordNeverExpires(uac)) stateText.append(", Password Never Expires");
                    uacStatusLabel.setText(stateText.toString());
                    uacStatusLabel.setStyle(disabled || locked
                            ? "-fx-text-fill: red; -fx-font-weight: bold;"
                            : "-fx-text-fill: green;");

                    // Attribute values
                    for (int i = 0; i < STATUS_ATTRIBUTES.length; i++) {
                        String attr = STATUS_ATTRIBUTES[i];
                        List<String> values = findAttribute(attrs, attr);
                        valueLabels[i].setText(formatAttributeValue(attr, values));
                    }

                    // Enable action buttons
                    unlockButton.setDisable(!locked);
                    enableDisableButton.setDisable(false);
                    enableDisableButton.setText(disabled ? "Enable Account" : "Disable Account");
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    uacStatusLabel.setText("Error: " + e.getMessage());
                    for (Label label : valueLabels) {
                        label.setText("(unable to load)");
                    }
                });
            }
        });
    }

    private void unlockAccount() {
        unlockButton.setDisable(true);
        Thread.ofVirtual().start(() -> {
            try {
                controller.getLdapService().modifyAttribute(
                        dn, "lockoutTime",
                        com.pointbluetech.arborj.service.LDAPService.LDAPModifyOperation.REPLACE,
                        List.of("0"));

                Platform.runLater(() -> {
                    loadData();
                    showInfo("Account unlocked successfully.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    unlockButton.setDisable(false);
                    showError("Failed to unlock account: " + e.getMessage());
                });
            }
        });
    }

    private void toggleAccountEnabled() {
        enableDisableButton.setDisable(true);
        Thread.ofVirtual().start(() -> {
            try {
                boolean nowEnabled = controller.getLdapService().toggleADAccountEnabled(dn);

                Platform.runLater(() -> {
                    loadData();
                    showInfo("Account is now " + (nowEnabled ? "enabled" : "disabled") + ".");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    enableDisableButton.setDisable(false);
                    showError("Failed to toggle account state: " + e.getMessage());
                });
            }
        });
    }

    private String formatAttributeValue(String attribute, List<String> values) {
        if (values == null || values.isEmpty()) {
            return "(not set)";
        }

        String raw = values.getFirst();

        return switch (attribute) {
            case "badPwdCount" -> raw;
            case "lockoutTime", "badPasswordTime", "lastLogon", "lastLogonTimestamp", "pwdLastSet" ->
                    formatWindowsFileTime(raw);
            case "accountExpires" -> formatAccountExpires(raw);
            default -> raw;
        };
    }

    /**
     * Convert a Windows FILETIME value (100-nanosecond intervals since 1601-01-01)
     * to a human-readable date string.
     */
    private String formatWindowsFileTime(String rawValue) {
        try {
            long fileTime = Long.parseLong(rawValue);
            if (fileTime == 0) {
                return "Never / Not Set";
            }
            long epochMillis = (fileTime - FILETIME_EPOCH_OFFSET) / 10_000;
            Instant instant = Instant.ofEpochMilli(epochMillis);
            LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            return ldt.format(DATE_FORMAT) + "  (raw: " + rawValue + ")";
        } catch (NumberFormatException e) {
            return rawValue;
        }
    }

    /**
     * Format the accountExpires attribute. Special values:
     * 0 or 9223372036854775807 (Long.MAX_VALUE) mean "never expires".
     */
    private String formatAccountExpires(String rawValue) {
        try {
            long val = Long.parseLong(rawValue);
            if (val == 0 || val == Long.MAX_VALUE) {
                return "Never";
            }
            return formatWindowsFileTime(rawValue);
        } catch (NumberFormatException e) {
            return rawValue;
        }
    }

    private boolean isLockedOut(Map<String, List<String>> attrs) {
        List<String> lockout = findAttribute(attrs, "lockoutTime");
        if (lockout == null || lockout.isEmpty()) return false;
        try {
            return Long.parseLong(lockout.getFirst()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Case-insensitive attribute lookup in the attributes map.
     */
    private List<String> findAttribute(Map<String, List<String>> attrs, String name) {
        List<String> values = attrs.get(name);
        if (values != null) return values;
        for (var entry : attrs.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText("Error");
        alert.showAndWait();
    }
}
