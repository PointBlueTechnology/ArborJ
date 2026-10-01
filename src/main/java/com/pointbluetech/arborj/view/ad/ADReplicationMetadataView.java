package com.pointbluetech.arborj.view.ad;

import com.pointbluetech.arborj.controller.MainController;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dialog displaying Active Directory replication metadata for an entry.
 * Fetches and parses the msDS-ReplAttributeMetaData operational attribute,
 * which contains XML-encoded per-attribute replication information.
 */
public class ADReplicationMetadataView extends Dialog<Void> {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Windows FILETIME epoch offset: 100-nanosecond intervals
     * between 1601-01-01 and 1970-01-01.
     */
    private static final long FILETIME_EPOCH_OFFSET = 116_444_736_000_000_000L;

    private final MainController controller;
    private final String dn;
    private final TableView<ReplMetadataEntry> table;
    private final ObservableList<ReplMetadataEntry> entries = FXCollections.observableArrayList();

    public ADReplicationMetadataView(MainController controller, String dn) {
        this(controller, dn, null);
    }

    public ADReplicationMetadataView(MainController controller, String dn, javafx.stage.Window owner) {
        this.controller = controller;
        this.dn = dn;

        setTitle("Replication Metadata");
        setHeaderText(dn);
        setResizable(true);
        if (owner != null) initOwner(owner);

        table = new TableView<>(entries);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<ReplMetadataEntry, String> attrCol = new TableColumn<>("Attribute");
        attrCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().attributeName()));
        attrCol.setPrefWidth(200);

        TableColumn<ReplMetadataEntry, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().version()));
        versionCol.setPrefWidth(70);

        TableColumn<ReplMetadataEntry, String> timeCol = new TableColumn<>("Last Origin Time");
        timeCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().lastOriginTime()));
        timeCol.setPrefWidth(180);

        TableColumn<ReplMetadataEntry, String> serverCol = new TableColumn<>("Origin Server");
        serverCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().originServer()));
        serverCol.setPrefWidth(250);

        table.getColumns().addAll(List.of(attrCol, versionCol, timeCol, serverCol));
        table.setPrefWidth(750);
        table.setPrefHeight(500);

        // Placeholder for empty state
        table.setPlaceholder(new Label("Loading replication metadata..."));

        VBox content = new VBox(8, table);
        content.setPadding(new Insets(12));

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        loadData();
    }

    private void loadData() {
        Thread.ofVirtual().start(() -> {
            try {
                Map<String, List<String>> attrs = controller.getLdapService()
                        .fetchAttributes(dn, true);

                List<String> metaValues = findAttribute(attrs, "msDS-ReplAttributeMetaData");

                if (metaValues == null || metaValues.isEmpty()) {
                    Platform.runLater(() ->
                            table.setPlaceholder(new Label("No replication metadata available for this entry.")));
                    return;
                }

                List<ReplMetadataEntry> parsed = new ArrayList<>();
                for (String xml : metaValues) {
                    ReplMetadataEntry entry = parseMetadataXml(xml);
                    if (entry != null) {
                        parsed.add(entry);
                    }
                }

                // Sort by attribute name
                parsed.sort((a, b) -> a.attributeName().compareToIgnoreCase(b.attributeName()));

                Platform.runLater(() -> {
                    entries.setAll(parsed);
                    if (entries.isEmpty()) {
                        table.setPlaceholder(new Label("No metadata entries could be parsed."));
                    }
                });

            } catch (Exception e) {
                Platform.runLater(() ->
                        table.setPlaceholder(new Label("Error loading metadata: " + e.getMessage())));
            }
        });
    }

    // Patterns for extracting XML element values from AD replication metadata
    private static final Pattern PAT_ATTR_NAME =
            Pattern.compile("<pszAttributeName>(.*?)</pszAttributeName>");
    private static final Pattern PAT_VERSION =
            Pattern.compile("<dwVersion>(\\d+)</dwVersion>");
    private static final Pattern PAT_TIME =
            Pattern.compile("<ftimeLastOriginatingChange>(.*?)</ftimeLastOriginatingChange>");
    private static final Pattern PAT_SERVER =
            Pattern.compile("<pszLastOriginatingDsaDN>(.*?)</pszLastOriginatingDsaDN>");

    /**
     * Parse a single DS_REPL_ATTR_META_DATA XML element.
     * Each value of msDS-ReplAttributeMetaData is an XML fragment like:
     * <pre>
     * &lt;DS_REPL_ATTR_META_DATA&gt;
     *   &lt;pszAttributeName&gt;cn&lt;/pszAttributeName&gt;
     *   &lt;dwVersion&gt;1&lt;/dwVersion&gt;
     *   &lt;ftimeLastOriginatingChange&gt;2024-01-15T10:30:00Z&lt;/ftimeLastOriginatingChange&gt;
     *   &lt;pszLastOriginatingDsaDN&gt;CN=NTDS Settings,...&lt;/pszLastOriginatingDsaDN&gt;
     * &lt;/DS_REPL_ATTR_META_DATA&gt;
     * </pre>
     */
    private ReplMetadataEntry parseMetadataXml(String xml) {
        String attrName = extractElement(PAT_ATTR_NAME, xml);
        String version = extractElement(PAT_VERSION, xml);
        String timeRaw = extractElement(PAT_TIME, xml);
        String serverDN = extractElement(PAT_SERVER, xml);

        if (attrName == null) return null;

        String formattedTime = formatTimestamp(timeRaw);
        String serverDisplay = formatServerDN(serverDN);

        return new ReplMetadataEntry(
                attrName,
                version != null ? version : "?",
                formattedTime,
                serverDisplay
        );
    }

    private String extractElement(Pattern pattern, String xml) {
        Matcher m = pattern.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Format the timestamp from the replication metadata.
     * AD may store this as an ISO 8601 datetime string or as a FILETIME integer.
     */
    private String formatTimestamp(String raw) {
        if (raw == null || raw.isEmpty()) return "(unknown)";

        // Try parsing as a FILETIME (numeric value)
        try {
            long fileTime = Long.parseLong(raw);
            if (fileTime == 0) return "Never";
            long epochMillis = (fileTime - FILETIME_EPOCH_OFFSET) / 10_000;
            Instant instant = Instant.ofEpochMilli(epochMillis);
            LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            return ldt.format(DATE_FORMAT);
        } catch (NumberFormatException ignored) {
            // Not numeric, try as ISO 8601
        }

        // Try parsing as ISO 8601 datetime
        try {
            Instant instant = Instant.parse(raw);
            LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            return ldt.format(DATE_FORMAT);
        } catch (Exception ignored) {
            // Return raw if nothing works
        }

        return raw;
    }

    /**
     * Extract a friendly server name from the full NTDS Settings DN.
     * E.g. "CN=NTDS Settings,CN=DC01,CN=Servers,..." becomes "DC01".
     */
    private String formatServerDN(String serverDN) {
        if (serverDN == null || serverDN.isEmpty()) return "(unknown)";

        // Try to extract the server name (second CN component)
        String[] parts = serverDN.split(",");
        if (parts.length >= 2) {
            String serverPart = parts[1].trim();
            if (serverPart.toUpperCase().startsWith("CN=")) {
                return serverPart.substring(3) + "  [" + serverDN + "]";
            }
        }

        return serverDN;
    }

    /**
     * Case-insensitive attribute lookup.
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

    /**
     * Represents one parsed replication metadata entry for the table.
     */
    record ReplMetadataEntry(String attributeName, String version,
                             String lastOriginTime, String originServer) {}
}
