package com.pointbluetech.arborj.view.edir;

import com.pointbluetech.arborj.controller.MainController;
import com.unboundid.asn1.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Partition Status — shows all replica holders for a partition
 * by decoding the multi-valued Replica attribute from the partition root.
 */
public class PartitionSyncView extends Dialog<Void> {

    private final MainController controller;
    private final ComboBox<String> partitionCombo = new ComboBox<>();
    private final VBox cardList = new VBox(10);
    private final Button queryButton = new Button("Query");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label statusLabel = new Label();

    public PartitionSyncView(MainController controller) {
        this(controller, null);
    }

    public PartitionSyncView(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;
        setTitle("Partition Status");
        setResizable(true);
        if (owner != null) initOwner(owner);

        Label title = new Label("Partition Status");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16;");
        Label subtitle = new Label("All replica holders for the selected partition");
        subtitle.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");

        Label partLabel = new Label("Partition:");
        partitionCombo.setPrefWidth(300);
        queryButton.setOnAction(e -> queryPartition());
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);
        HBox queryRow = new HBox(8, partLabel, partitionCombo, queryButton, spinner);
        queryRow.setAlignment(Pos.CENTER_LEFT);

        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12;");
        statusLabel.setPadding(new Insets(4, 0, 4, 0));

        ScrollPane scrollPane = new ScrollPane(cardList);
        scrollPane.setFitToWidth(true);
        cardList.setPadding(new Insets(8));
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox content = new VBox(8, title, subtitle, new Separator(), queryRow, statusLabel, scrollPane);
        content.setPadding(new Insets(12));
        content.setPrefWidth(700);
        content.setPrefHeight(550);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));

        loadPartitions();
    }

    private void loadPartitions() {
        Thread.startVirtualThread(() -> {
            try {
                var ldap = controller.getLdapService();
                List<String> serverDNs = ldap.findServerDNsForReplicas();
                if (serverDNs.isEmpty()) return;
                List<String> result = ldap.listReplicas(serverDNs);
                if (result.size() <= 1) return;
                List<String> partitions = result.subList(1, result.size());

                Platform.runLater(() -> {
                    for (String p : partitions) {
                        partitionCombo.getItems().add(p.isEmpty() ? "[Root]" : p);
                    }
                    if (!partitionCombo.getItems().isEmpty()) {
                        partitionCombo.getSelectionModel().selectFirst();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Error loading partitions: " + e.getMessage()));
            }
        });
    }

    private void queryPartition() {
        String selected = partitionCombo.getValue();
        if (selected == null) return;
        String partitionDN = "[Root]".equals(selected) ? "" : selected;

        queryButton.setDisable(true);
        spinner.setVisible(true);
        cardList.getChildren().clear();
        statusLabel.setText("Querying...");

        Thread.startVirtualThread(() -> {
            try {
                var ldap = controller.getLdapService();
                var rawConn = ldap.getRawConnection();
                if (rawConn == null) throw new Exception("Not connected");

                // Fetch the Replica attribute from the partition root.
                // For the root partition (DN ""), use the Tree object (T=TreeName).
                final String[] searchDNHolder = {partitionDN};
                if (partitionDN.isEmpty()) {
                    String treeName = ldap.fetchTreeName();
                    if (treeName != null && !treeName.isEmpty()) {
                        searchDNHolder[0] = "T=" + treeName;
                        System.out.println("[ArborJ] Partition Status: root → Tree object " + searchDNHolder[0]);
                    }
                }

                var entry = rawConn.getEntry(searchDNHolder[0], "Replica");
                if (entry == null || entry.getAttribute("Replica") == null) {
                    Platform.runLater(() -> {
                        statusLabel.setText("No Replica attribute found on " +
                                (partitionDN.isEmpty() ? "[Root] (T=" + searchDNHolder[0] + ")" : partitionDN));
                        queryButton.setDisable(false);
                        spinner.setVisible(false);
                    });
                    return;
                }

                // Step 1: Parse the Replica attribute purely for the server DN
                // and address hints. We do NOT trust the integer fields here —
                // their order is inconsistent across partitions. Authoritative
                // type / state / number come from getReplicaInfo below.
                byte[][] replicaValues = entry.getAttribute("Replica").getValueByteArrays();
                List<ReplicaHolder> holders = new ArrayList<>();
                for (byte[] val : replicaValues) {
                    ReplicaHolder holder = parseReplicaPointer(val);
                    if (holder != null) holders.add(holder);
                }

                // Step 2: For each holder, ask the connected server's NDS for
                // the authoritative replica info (type, state, number). The
                // connected server keeps this for every replica of partitions
                // it holds.
                for (int i = 0; i < holders.size(); i++) {
                    ReplicaHolder h = holders.get(i);
                    try {
                        byte[] data = ldap.getReplicaInfo(h.serverDN(), partitionDN);
                        if (data != null) {
                            int[] info = ldap.parseReplicaInfoResponse(data);
                            // info = [replicaState, modTime, replicaNumber, replicaType]
                            holders.set(i, new ReplicaHolder(h.serverDN(),
                                    info[3], info[0], info[2], h.addresses()));
                        }
                    } catch (Exception ignored) {
                        // Leave the holder with -1 sentinels; UI will show "Unknown".
                    }
                }

                Platform.runLater(() -> {
                    statusLabel.setText(holders.size() + " replica holder"
                            + (holders.size() != 1 ? "s" : "") + " found");
                    for (ReplicaHolder h : holders) {
                        cardList.getChildren().add(buildHolderCard(h));
                    }
                    queryButton.setDisable(false);
                    spinner.setVisible(false);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error: " + e.getMessage());
                    queryButton.setDisable(false);
                    spinner.setVisible(false);
                });
            }
        });
    }

    // --- BER Replica Pointer Parsing ---

    /**
     * Extracts the server DN and address hints from one Replica attribute
     * value. Returns a holder with -1 sentinels for type / state / number —
     * those are populated separately via getReplicaInfo. We deliberately don't
     * decode the integer fields in the Replica BER: their order varies across
     * partitions (root partitions use one layout, sub-partitions another), and
     * misreading them shows replicas as the wrong type/state.
     */
    private ReplicaHolder parseReplicaPointer(byte[] data) {
        try {
            ASN1Element root = ASN1Element.decode(data);
            if (root.getType() != 0x30) return null;
            ASN1Element[] elements = ASN1Sequence.decodeAsSequence(root).elements();

            String serverDN = "";
            List<String> addresses = new ArrayList<>();
            for (ASN1Element elem : elements) {
                if (elem.getType() == 0x04 && serverDN.isEmpty()) {
                    serverDN = elem.decodeAsOctetString().stringValue();
                } else if (elem.getType() == 0x30) {
                    addresses.addAll(parseAddressSequence(elem));
                }
            }
            return new ReplicaHolder(serverDN, -1, -1, -1, addresses);
        } catch (Exception e) {
            System.out.println("[ArborJ] parseReplicaPointer failed: " + e.getMessage());
            return null;
        }
    }

    private List<String> parseAddressSequence(ASN1Element seqElem) {
        List<String> addresses = new ArrayList<>();
        try {
            ASN1Element[] children = ASN1Sequence.decodeAsSequence(seqElem).elements();
            for (ASN1Element child : children) {
                if (child.getType() == 0x30) {
                    // Each address entry: SEQUENCE { INTEGER(type), OCTET STRING(address) }
                    ASN1Element[] addrParts = ASN1Sequence.decodeAsSequence(child).elements();
                    if (addrParts.length >= 2) {
                        int addrType = -1;
                        String addrValue = "";
                        for (ASN1Element part : addrParts) {
                            if (part.getType() == 0x02) {
                                addrType = ASN1Integer.decodeAsInteger(part).intValue();
                            } else if (part.getType() == 0x04) {
                                byte[] addrBytes = part.getValue();
                                // Type 13 = URL (UTF-16LE encoded)
                                if (addrType == 13 || addrType == 0x0D) {
                                    addrValue = decodeUtf16LE(addrBytes);
                                } else if (addrType == 9 || addrType == 8) {
                                    // TCP/UDP: 2-byte port + 4-byte IP
                                    if (addrBytes.length >= 6) {
                                        int port = ((addrBytes[0] & 0xFF) << 8) | (addrBytes[1] & 0xFF);
                                        String ip = (addrBytes[2] & 0xFF) + "." + (addrBytes[3] & 0xFF)
                                                + "." + (addrBytes[4] & 0xFF) + "." + (addrBytes[5] & 0xFF);
                                        addrValue = ip + ":" + port;
                                    }
                                } else {
                                    addrValue = bytesToHex(addrBytes);
                                }
                            }
                        }
                        if (!addrValue.isEmpty()) {
                            String typeName = switch (addrType) {
                                case 8 -> "UDP";
                                case 9 -> "TCP";
                                case 10 -> "UDP6";
                                case 11 -> "TCP6";
                                case 13 -> "URL";
                                default -> "Type " + addrType;
                            };
                            addresses.add(typeName + ": " + addrValue);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return addresses;
    }

    private static String decodeUtf16LE(byte[] bytes) {
        // Strip trailing null bytes
        int len = bytes.length;
        while (len >= 2 && bytes[len - 1] == 0 && bytes[len - 2] == 0) len -= 2;
        if (len <= 0) return "";
        return new String(bytes, 0, len, StandardCharsets.UTF_16LE);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    // --- Card UI ---

    private javafx.scene.Node buildHolderCard(ReplicaHolder h) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(10));
        card.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 6; "
                + "-fx-border-color: -color-border-default; -fx-border-radius: 6;");

        // Header: status dot keyed off replica state (from getReplicaInfo).
        Label dot = new Label("\u25CF");
        dot.setTextFill(h.isOK() ? Color.GREEN : Color.ORANGE);

        String serverName = h.serverDN;
        if (serverName.contains(",")) {
            String first = serverName.substring(0, serverName.indexOf(','));
            int eq = first.indexOf('=');
            if (eq > 0) serverName = first.substring(eq + 1);
        }
        Label nameLabel = new Label(serverName);
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label typeBadge = new Label(h.typeLabel());
        typeBadge.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");
        typeBadge.setTextFill(switch (h.replicaType) {
            case 0 -> Color.web("#2266cc"); // Master
            case 1 -> Color.GREEN;          // R/W
            case 2 -> Color.ORANGE;         // R/O
            default -> Color.GRAY;
        });

        HBox header = new HBox(6, dot, nameLabel, spacer, typeBadge);
        header.setAlignment(Pos.CENTER_LEFT);

        // Details
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(3);
        grid.setPadding(new Insets(0, 0, 0, 20));

        int row = 0;
        addRow(grid, row++, "Replica Type", h.typeLabel());
        addRow(grid, row++, "Replica State", h.stateLabel());
        if (h.replicaNumber() >= 0) {
            addRow(grid, row++, "Replica Number", String.valueOf(h.replicaNumber()));
        }

        // Network addresses — show first few
        if (!h.addresses.isEmpty()) {
            addRow(grid, row++, "Addresses", "");
            for (String addr : h.addresses) {
                // Filter to show useful ones (LDAP, HTTPS, TCP)
                if (addr.startsWith("URL:") || addr.startsWith("TCP:") || addr.startsWith("UDP:")) {
                    Label addrLabel = new Label("  " + addr);
                    addrLabel.setStyle("-fx-font-family: monospaced; -fx-font-size: 11; -fx-text-fill: -color-fg-muted;");
                    grid.add(addrLabel, 1, row++);
                }
            }
        }

        // Server DN
        Label dnLabel = new Label(h.serverDN);
        dnLabel.setStyle("-fx-font-family: monospaced; -fx-font-size: 10; -fx-text-fill: -color-fg-muted;");

        card.getChildren().addAll(header, grid, dnLabel);
        return card;
    }

    private void addRow(GridPane grid, int row, String label, String value) {
        Label l = new Label(label);
        l.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12;");
        l.setMinWidth(100);
        Label v = new Label(value);
        v.setStyle("-fx-font-family: monospaced; -fx-font-size: 12;");
        grid.add(l, 0, row);
        grid.add(v, 1, row);
    }

    // --- Data Model ---

    private record ReplicaHolder(String serverDN, int replicaType, int replicaState,
                                  int replicaNumber, List<String> addresses) {
        String typeLabel() {
            return switch (replicaType) {
                case 0 -> "Master";
                case 1 -> "Read/Write";
                case 2 -> "Read-Only";
                case 3 -> "Subordinate Ref";
                case 5 -> "Filtered Read/Write";
                case 6 -> "Filtered Read-Only";
                case -1 -> "Unknown";
                default -> "Unknown (" + replicaType + ")";
            };
        }

        /** Mirrors ReplicationStatusView's state mapping. */
        String stateLabel() {
            if (replicaState < 0) return "Unknown";
            int core = replicaState & 0x7FFF;
            boolean syncing = (replicaState & 0x8000) != 0;
            String label = switch (core) {
                case 0 -> "On";
                case 1 -> "New Replica";
                case 2 -> "Dying";
                case 3 -> "Locked";
                case 6 -> "Transition On";
                case 0x0D -> "Federated";
                default -> syncing ? "On (Syncing)" : "On";
            };
            if (syncing && core == 0) label = "On (Syncing)";
            return label;
        }

        boolean isOK() {
            int core = replicaState & 0x7FFF;
            return core == 0 || core == 0x0D;
        }
    }
}
