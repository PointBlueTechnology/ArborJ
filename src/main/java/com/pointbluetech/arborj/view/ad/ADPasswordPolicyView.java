package com.pointbluetech.arborj.view.ad;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.LDAPEntry;
import com.pointbluetech.arborj.model.SearchScope;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.text.Font;

import java.util.List;
import java.util.Map;

/**
 * Dialog displaying the Active Directory domain password policy
 * read from the defaultNamingContext.
 */
public class ADPasswordPolicyView extends Dialog<Void> {

    private static final String[] POLICY_ATTRIBUTES = {
            "minPwdLength",
            "maxPwdAge",
            "minPwdAge",
            "pwdHistoryLength",
            "pwdProperties",
            "lockoutThreshold",
            "lockoutDuration",
            "lockoutObservationWindow"
    };

    private static final String[] DISPLAY_LABELS = {
            "Minimum Password Length",
            "Maximum Password Age",
            "Minimum Password Age",
            "Password History Length",
            "Password Properties",
            "Lockout Threshold",
            "Lockout Duration",
            "Lockout Observation Window"
    };

    // pwdProperties bitmask values
    private static final int PWD_PROP_COMPLEX = 1;
    private static final int PWD_PROP_NO_ANON_CHANGE = 2;
    private static final int PWD_PROP_NO_CLEAR_TEXT = 4;
    private static final int PWD_PROP_LOCKOUT_ADMINS = 8;
    private static final int PWD_PROP_STORE_CLEARTEXT = 16;

    private final MainController controller;
    private final GridPane grid;
    private final Label[] valueLabels;

    public ADPasswordPolicyView(MainController controller) {
        this(controller, null);
    }

    public ADPasswordPolicyView(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;

        setTitle("Domain Password Policy");
        setResizable(true);
        if (owner != null) initOwner(owner);

        grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(200);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        valueCol.setPrefWidth(400);
        grid.getColumnConstraints().addAll(labelCol, valueCol);

        Font mono = Font.font("monospaced", 12);
        valueLabels = new Label[POLICY_ATTRIBUTES.length];

        for (int i = 0; i < POLICY_ATTRIBUTES.length; i++) {
            Label nameLabel = new Label(DISPLAY_LABELS[i] + ":");
            nameLabel.setStyle("-fx-font-weight: bold;");

            Label valLabel = new Label("Loading...");
            valLabel.setFont(mono);
            valLabel.setWrapText(true);
            valueLabels[i] = valLabel;

            grid.add(nameLabel, 0, i);
            grid.add(valLabel, 1, i);
        }

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefWidth(650);
        scrollPane.setPrefHeight(350);

        getDialogPane().setContent(scrollPane);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        loadData();
    }

    private void loadData() {
        Thread.ofVirtual().start(() -> {
            try {
                // Get the defaultNamingContext from the RootDSE
                Map<String, List<String>> rootDSE = controller.getLdapService().fetchRootDSE();
                List<String> ncValues = rootDSE.get("defaultNamingContext");
                if (ncValues == null || ncValues.isEmpty()) {
                    Platform.runLater(() -> {
                        for (Label label : valueLabels) {
                            label.setText("(defaultNamingContext not found)");
                        }
                    });
                    return;
                }

                String baseDN = ncValues.getFirst();

                // Search the domain root for password policy attributes
                List<LDAPEntry> results = controller.getLdapService().search(
                        baseDN, SearchScope.BASE, "(objectClass=*)", POLICY_ATTRIBUTES);

                Platform.runLater(() -> {
                    if (results.isEmpty()) {
                        for (Label label : valueLabels) {
                            label.setText("(no results)");
                        }
                        return;
                    }

                    LDAPEntry entry = results.getFirst();
                    for (int i = 0; i < POLICY_ATTRIBUTES.length; i++) {
                        String attr = POLICY_ATTRIBUTES[i];
                        String raw = entry.getFirstValue(attr);
                        valueLabels[i].setText(formatPolicyValue(attr, raw));
                    }
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    for (Label label : valueLabels) {
                        label.setText("Error: " + e.getMessage());
                    }
                });
            }
        });
    }

    private String formatPolicyValue(String attribute, String rawValue) {
        if (rawValue == null) {
            return "(not set)";
        }

        return switch (attribute) {
            case "minPwdLength" -> rawValue + " characters";
            case "pwdHistoryLength" -> rawValue + " passwords remembered";
            case "lockoutThreshold" -> {
                int val = parseIntSafe(rawValue);
                yield val == 0 ? "0 (lockout disabled)" : rawValue + " invalid attempts";
            }
            case "maxPwdAge", "minPwdAge", "lockoutDuration", "lockoutObservationWindow" ->
                    formatADTimeInterval(rawValue);
            case "pwdProperties" -> formatPwdProperties(rawValue);
            default -> rawValue;
        };
    }

    /**
     * Convert an Active Directory time interval to a human-readable duration.
     * AD stores these as negative values in 100-nanosecond intervals.
     * For example, -8640000000000 = 10 days.
     * A value of 0 for lockoutDuration means "until admin unlocks".
     * A value of -9223372036854775808 (Long.MIN_VALUE) means "never".
     */
    private String formatADTimeInterval(String rawValue) {
        try {
            long val = Long.parseLong(rawValue);

            if (val == 0) {
                return "None / Forever (raw: 0)";
            }

            if (val == Long.MIN_VALUE) {
                return "Never (raw: " + rawValue + ")";
            }

            // Convert negative 100ns ticks to positive minutes
            long ticks = Math.abs(val);
            long totalSeconds = ticks / 10_000_000;
            long days = totalSeconds / 86_400;
            long hours = (totalSeconds % 86_400) / 3_600;
            long minutes = (totalSeconds % 3_600) / 60;

            StringBuilder sb = new StringBuilder();
            if (days > 0) sb.append(days).append(days == 1 ? " day" : " days");
            if (hours > 0) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(hours).append(hours == 1 ? " hour" : " hours");
            }
            if (minutes > 0) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(minutes).append(minutes == 1 ? " minute" : " minutes");
            }

            if (sb.isEmpty()) {
                sb.append(totalSeconds).append(totalSeconds == 1 ? " second" : " seconds");
            }

            sb.append("  (raw: ").append(rawValue).append(")");
            return sb.toString();

        } catch (NumberFormatException e) {
            return rawValue;
        }
    }

    /**
     * Decode the pwdProperties bitmask into a human-readable string.
     */
    private String formatPwdProperties(String rawValue) {
        int val = parseIntSafe(rawValue);
        StringBuilder sb = new StringBuilder();
        sb.append(rawValue).append(" — ");

        boolean first = true;
        if ((val & PWD_PROP_COMPLEX) != 0) {
            sb.append("Complexity Required");
            first = false;
        }
        if ((val & PWD_PROP_NO_ANON_CHANGE) != 0) {
            if (!first) sb.append(", ");
            sb.append("No Anonymous Change");
            first = false;
        }
        if ((val & PWD_PROP_NO_CLEAR_TEXT) != 0) {
            if (!first) sb.append(", ");
            sb.append("No Clear Text");
            first = false;
        }
        if ((val & PWD_PROP_LOCKOUT_ADMINS) != 0) {
            if (!first) sb.append(", ");
            sb.append("Lockout Admins");
            first = false;
        }
        if ((val & PWD_PROP_STORE_CLEARTEXT) != 0) {
            if (!first) sb.append(", ");
            sb.append("Store Clear Text");
            first = false;
        }
        if (first) {
            sb.append("(none)");
        }

        return sb.toString();
    }

    private int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
