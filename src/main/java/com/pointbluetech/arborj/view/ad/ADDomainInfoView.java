package com.pointbluetech.arborj.view.ad;

import com.pointbluetech.arborj.controller.MainController;
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
 * Dialog displaying Active Directory forest and domain information
 * retrieved from the RootDSE.
 */
public class ADDomainInfoView extends Dialog<Void> {

    private static final String[] DSE_ATTRIBUTES = {
            "forestFunctionality",
            "domainFunctionality",
            "domainControllerFunctionality",
            "defaultNamingContext",
            "rootDomainNamingContext",
            "schemaNamingContext",
            "configurationNamingContext",
            "dnsHostName",
            "serverName"
    };

    private static final String[] DISPLAY_LABELS = {
            "Forest Functionality",
            "Domain Functionality",
            "DC Functionality",
            "Default Naming Context",
            "Root Domain Naming Context",
            "Schema Naming Context",
            "Configuration Naming Context",
            "DNS Host Name",
            "Server Name"
    };

    private static final Map<String, Map<String, String>> FUNCTIONALITY_LEVELS = Map.of(
            "forestFunctionality", Map.of(
                    "0", "Windows 2000",
                    "1", "Windows Server 2003 Interim",
                    "2", "Windows Server 2003",
                    "3", "Windows Server 2008",
                    "4", "Windows Server 2008 R2",
                    "5", "Windows Server 2012",
                    "6", "Windows Server 2012 R2",
                    "7", "Windows Server 2016"
            ),
            "domainFunctionality", Map.of(
                    "0", "Windows 2000 Mixed",
                    "1", "Windows Server 2003 Interim",
                    "2", "Windows Server 2003",
                    "3", "Windows Server 2008",
                    "4", "Windows Server 2008 R2",
                    "5", "Windows Server 2012",
                    "6", "Windows Server 2012 R2",
                    "7", "Windows Server 2016"
            ),
            "domainControllerFunctionality", Map.of(
                    "0", "Windows 2000",
                    "1", "Windows Server 2003 Interim",
                    "2", "Windows Server 2003",
                    "3", "Windows Server 2008",
                    "4", "Windows Server 2008 R2",
                    "5", "Windows Server 2012",
                    "6", "Windows Server 2012 R2",
                    "7", "Windows Server 2016"
            )
    );

    private final MainController controller;
    private final GridPane grid;
    private final Label[] valueLabels;

    public ADDomainInfoView(MainController controller) {
        this(controller, null);
    }

    public ADDomainInfoView(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;

        setTitle("Active Directory Domain Information");
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
        valueLabels = new Label[DSE_ATTRIBUTES.length];

        for (int i = 0; i < DSE_ATTRIBUTES.length; i++) {
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
                Map<String, List<String>> rootDSE = controller.getLdapService().fetchRootDSE();

                Platform.runLater(() -> {
                    for (int i = 0; i < DSE_ATTRIBUTES.length; i++) {
                        String attr = DSE_ATTRIBUTES[i];
                        List<String> values = rootDSE.get(attr);
                        String display;

                        if (values == null || values.isEmpty()) {
                            display = "(not available)";
                        } else {
                            String raw = values.getFirst();
                            display = formatValue(attr, raw);
                        }

                        valueLabels[i].setText(display);
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

    private String formatValue(String attribute, String rawValue) {
        Map<String, String> levels = FUNCTIONALITY_LEVELS.get(attribute);
        if (levels != null) {
            String friendly = levels.get(rawValue);
            if (friendly != null) {
                return rawValue + " (" + friendly + ")";
            }
        }
        return rawValue;
    }
}
