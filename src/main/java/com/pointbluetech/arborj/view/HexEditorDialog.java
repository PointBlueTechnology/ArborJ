package com.pointbluetech.arborj.view;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hex viewer for binary attribute values.
 *
 * <p>The hex view itself is a virtualized <em>read-only</em> display.
 * Earlier versions used an editable {@code TextArea} that you could type
 * hex into directly, but that broke on large stream-syntax values
 * (DirXML configs, certificates) — the underlying JavaFX text control
 * has rendering limits that made big dumps look truncated. We swapped
 * to a virtualized {@code ListView} so display cost no longer scales
 * with value size. The trade-off is: the visible bytes can't be edited
 * inline. Three paths are offered for modifying the bytes when the
 * dialog is not in read-only mode:
 * <ul>
 *   <li><b>Edit Hex…</b> — opens a paste-style sub-dialog where the user
 *       enters a new hex string. Tolerates whitespace, commas, colons, and
 *       the dialog's own dump format (offset + bytes + ASCII gutter).</li>
 *   <li><b>Load from File…</b> — replaces the bytes with the contents of a
 *       file picked from disk.</li>
 *   <li><b>View as Text</b> — toggles to a UTF-8 text editor for values that
 *       are textual (XML configs, Java options, etc.).</li>
 * </ul>
 * "Save to File…" is always offered (including in read-only mode) so users
 * can extract certificates, raw configuration blobs, etc. to disk.
 */
public class HexEditorDialog {

    private static final Font MONO = Font.font("monospaced", 13);
    private static final int BYTES_PER_ROW = 16;
    /**
     * Match an offset prefix at the start of a hex-dump line: at least 3 hex
     * digits followed by a colon and at least one horizontal whitespace
     * character. The dialog itself emits 4-digit offsets followed by 5
     * spaces, and all hex-dump tools I've seen use ≥ 3-digit offsets and at
     * least one trailing space — but {@code "01:02:03"} (byte-colon-byte)
     * is NOT a dump line and must not be stripped.
     */
    private static final Pattern OFFSET_PREFIX = Pattern.compile("^[0-9A-Fa-f]{3,}:[ \\t]+");

    /**
     * Width of the bytes-region in the dialog's own dump format: 16 hex pairs
     * × 3 chars per pair (XX + trailing space) + 1 mid-row gap after byte 7.
     * When parseHexInput detects an offset-prefixed line we trim to this
     * width so the ASCII gutter that follows is discarded — using
     * {@code indexOf("  ")} fails because the mid-row gap is also two spaces.
     */
    private static final int DUMP_BYTES_REGION_WIDTH = BYTES_PER_ROW * 3 + 1;

    private final Stage stage;
    private final String attributeName;
    private final boolean readOnly;
    private final Consumer<byte[]> onSave;

    /** Mutable so Edit Hex… and Load from File… can replace the bytes in place. */
    private byte[] data;
    private final ListView<String> hexView;
    private final TextArea textView;
    private final Label byteCount;
    private final CheckBox viewAsText;

    /**
     * @param attributeName Attribute name for the header
     * @param hexString     Hex-encoded value string
     * @param readOnly      If true, no Save / Edit Hex / Load buttons
     * @param onSave        Called with new byte[] when Save clicked (null if read-only)
     */
    public HexEditorDialog(String attributeName, String hexString, boolean readOnly,
                            Consumer<byte[]> onSave) {
        this(attributeName, hexString, readOnly, onSave, null);
    }

    public HexEditorDialog(String attributeName, String hexString, boolean readOnly,
                            Consumer<byte[]> onSave, javafx.stage.Window owner) {
        this.attributeName = attributeName;
        this.data = hexToBytes(hexString);
        this.readOnly = readOnly;
        this.onSave = onSave;

        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Binary Value");
        stage.setResizable(true);

        VBox root = new VBox(8);
        root.setPadding(new Insets(12));

        // Header
        Label titleLabel = new Label(readOnly ? "View Value" : "Edit Value");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16;");
        Label attrLabel = new Label(attributeName);
        attrLabel.setStyle("-fx-text-fill: -color-fg-muted;");

        // Mode label and toggle
        Label modeLabel = new Label("Hex view");
        modeLabel.setStyle("-fx-text-fill: -color-fg-muted;");

        viewAsText = new CheckBox("View as Text");
        Region modeSpacer = new Region();
        HBox.setHgrow(modeSpacer, Priority.ALWAYS);
        HBox modeRow = new HBox(8, modeLabel, modeSpacer, viewAsText);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        // Hex view — a virtualized ListView with one item per 16-byte row.
        // We deliberately don't use a TextArea here: a non-virtualized text
        // control loaded with the full hex dump (~78 chars × bytes/16 rows)
        // can hit JavaFX's text-rendering limits on large stream-syntax
        // attributes (DirXML config, certificates, stream values) and look
        // like truncation. ListView renders only the visible cells, but in
        // exchange the bytes shown can't be edited inline — Edit Hex… and
        // Load from File… cover that.
        hexView = new ListView<>();
        hexView.setItems(FXCollections.observableArrayList(buildHexLines(data)));
        hexView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                setText(empty || line == null ? null : line);
                setFont(MONO);
                setStyle("-fx-padding: 0 8 0 8;");
            }
        });
        VBox.setVgrow(hexView, Priority.ALWAYS);

        // Text view (hidden by default)
        textView = new TextArea();
        textView.setFont(MONO);
        textView.setWrapText(true);
        textView.setEditable(!readOnly);
        textView.setVisible(false);
        textView.setManaged(false);
        VBox.setVgrow(textView, Priority.ALWAYS);
        textView.setText(new String(data, java.nio.charset.StandardCharsets.UTF_8));

        // Byte count
        byteCount = new Label(formatBytesTotal(data.length));
        byteCount.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12;");

        // Toggle between hex and text view
        viewAsText.setOnAction(e -> {
            boolean showText = viewAsText.isSelected();
            hexView.setVisible(!showText);
            hexView.setManaged(!showText);
            textView.setVisible(showText);
            textView.setManaged(showText);
        });

        // ---------------- Buttons ----------------
        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_LEFT);

        // File operations: Load (write only), Save (always available)
        if (!readOnly) {
            Button loadBtn = new Button("Load from File…");
            loadBtn.setOnAction(e -> loadFromFile());
            buttons.getChildren().add(loadBtn);
        }
        Button saveFileBtn = new Button("Save to File…");
        saveFileBtn.setOnAction(e -> saveToFile());
        buttons.getChildren().add(saveFileBtn);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttons.getChildren().add(spacer);

        // Edit Hex (write only) — opens a paste sub-dialog because the main
        // hex view itself is virtualized and read-only.
        if (!readOnly && onSave != null) {
            Button editHexBtn = new Button("Edit Hex…");
            editHexBtn.setOnAction(e -> promptEditHex());
            buttons.getChildren().add(editHexBtn);
        }

        Button cancelBtn = new Button(readOnly ? "Close" : "Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> stage.close());
        buttons.getChildren().add(cancelBtn);

        if (!readOnly && onSave != null) {
            Button saveBtn = new Button("Save");
            saveBtn.setDefaultButton(true);
            saveBtn.setOnAction(e -> {
                // Hex view bytes are mutated via Edit Hex / Load from File,
                // so when not in text mode just hand back the current data.
                byte[] newData = viewAsText.isSelected()
                        ? textView.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : data;
                onSave.accept(newData);
                stage.close();
            });
            buttons.getChildren().add(saveBtn);
        }

        root.getChildren().addAll(titleLabel, attrLabel, new Separator());

        // Decoded net address header (if applicable)
        var netAddr = com.pointbluetech.arborj.util.NDSNetAddress.decode(data);
        if (netAddr != null) {
            Label netIcon = new Label("🌐");
            netIcon.setStyle("-fx-font-size: 18;");
            Label addrDisplay = new Label(netAddr.getDisplayString());
            addrDisplay.setFont(Font.font("monospaced", 16));
            Label typeInfo = new Label("Type: " + netAddr.getType().getDisplayName()
                    + " (" + netAddr.getType().getValue() + ")  •  "
                    + netAddr.getAddressData().length + " bytes");
            typeInfo.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12;");

            Button copyAddr = new Button("Copy");
            copyAddr.setStyle("-fx-font-size: 11;");
            copyAddr.setOnAction(ev -> {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(netAddr.getDisplayString());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            });

            Region addrSpacer = new Region();
            HBox.setHgrow(addrSpacer, Priority.ALWAYS);
            HBox addrRow = new HBox(8, netIcon, new VBox(2, addrDisplay, typeInfo), addrSpacer, copyAddr);
            addrRow.setAlignment(Pos.CENTER_LEFT);
            addrRow.setPadding(new Insets(8));
            addrRow.setStyle("-fx-background-color: -color-bg-subtle;");
            root.getChildren().addAll(addrRow, new Separator());
        }

        root.getChildren().addAll(modeRow, buildColumnHeader(), hexView, textView, byteCount,
                new Separator(), buttons);

        Scene scene = new Scene(root, 760, 480);
        stage.setScene(scene);
    }

    public void showAndWait() {
        stage.showAndWait();
    }

    /** Replace the underlying bytes and refresh every dependent view. */
    private void setData(byte[] newData) {
        this.data = newData;
        hexView.setItems(FXCollections.observableArrayList(buildHexLines(data)));
        textView.setText(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        byteCount.setText(formatBytesTotal(data.length));
    }

    // ---------------- Edit Hex sub-dialog ----------------

    private void promptEditHex() {
        Dialog<byte[]> dlg = new Dialog<>();
        dlg.setTitle("Edit Hex");
        dlg.setHeaderText("Replace the value with the hex you enter below.");
        dlg.initOwner(stage);

        TextArea input = new TextArea(currentHexString());
        input.setFont(MONO);
        input.setWrapText(true);
        input.setPrefRowCount(10);
        input.setPrefColumnCount(54);

        Label hint = new Label("Whitespace, commas, and colons are ignored. Lines starting "
                + "with an offset (e.g. \"0010:\") and a trailing ASCII column (separated "
                + "by two spaces) are also tolerated, so you can copy-paste from the main "
                + "hex view, edit, and OK.");
        hint.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        hint.setWrapText(true);
        hint.setMaxWidth(520);

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: -color-danger-fg; -fx-font-size: 11;");
        statusLabel.setWrapText(true);

        VBox box = new VBox(8, input, hint, statusLabel);
        box.setPadding(new Insets(12));
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Validate parse before letting the dialog close on OK.
        Button okBtn = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(ActionEvent.ACTION, e -> {
            try {
                byte[] parsed = parseHexInput(input.getText());
                dlg.setResult(parsed);
            } catch (IllegalArgumentException ex) {
                e.consume();
                statusLabel.setText("Invalid hex: " + ex.getMessage());
            }
        });
        dlg.setResultConverter(bt -> bt == ButtonType.OK ? dlg.getResult() : null);

        dlg.showAndWait().ifPresent(this::setData);
    }

    /** Format the current bytes as a paste-friendly hex string with line breaks every 16 bytes. */
    private String currentHexString() {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(i % BYTES_PER_ROW == 0 ? '\n' : ' ');
            sb.append(String.format("%02X", data[i]));
        }
        return sb.toString();
    }

    /**
     * Parse a free-form hex blob into bytes. Strips per-line offset prefixes
     * ({@code "OOOO: "} or similar) and trailing ASCII gutters
     * (whatever follows two consecutive spaces) so users can paste output
     * directly from the dialog's own hex view. Visible to package for tests.
     */
    static byte[] parseHexInput(String text) {
        if (text == null || text.isBlank()) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine;
            if (line.isBlank()) continue;

            // Drop a leading "<hex>:<spaces>" offset prefix if present. The
            // presence of an offset is also our signal that this line is in
            // the dialog's own dump format — in which case the bytes region
            // is fixed-width and the ASCII gutter follows. We can't use
            // indexOf("  ") to find the gutter, because the dump format also
            // emits two spaces in the mid-row gap after byte 7.
            Matcher offsetMatch = OFFSET_PREFIX.matcher(line);
            if (offsetMatch.find()) {
                line = line.substring(offsetMatch.end());
                if (line.length() > DUMP_BYTES_REGION_WIDTH) {
                    line = line.substring(0, DUMP_BYTES_REGION_WIDTH);
                }
            }

            // Strip remaining separators.
            String cleaned = line.replaceAll("[\\s,:]", "");
            if (cleaned.isEmpty()) continue;
            if (cleaned.length() % 2 != 0) {
                throw new IllegalArgumentException(
                        "line has an odd number of hex digits: \"" + rawLine.trim() + "\"");
            }
            for (int i = 0; i < cleaned.length(); i += 2) {
                int hi = Character.digit(cleaned.charAt(i), 16);
                int lo = Character.digit(cleaned.charAt(i + 1), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException(
                            "non-hex character on line: \"" + rawLine.trim() + "\"");
                }
                out.write((hi << 4) | lo);
            }
        }
        return out.toByteArray();
    }

    // ---------------- File load / save ----------------

    private void loadFromFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Binary Value from File");
        File f = chooser.showOpenDialog(stage);
        if (f == null) return;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            setData(bytes);
        } catch (IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Could not read file: " + ex.getMessage(), ButtonType.OK);
            alert.setHeaderText(null);
            alert.initOwner(stage);
            alert.showAndWait();
        }
    }

    private void saveToFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Binary Value to File");
        chooser.setInitialFileName(suggestFilename());
        File f = chooser.showSaveDialog(stage);
        if (f == null) return;
        try {
            Files.write(f.toPath(), data);
        } catch (IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Could not write file: " + ex.getMessage(), ButtonType.OK);
            alert.setHeaderText(null);
            alert.initOwner(stage);
            alert.showAndWait();
        }
    }

    /** Best-effort suggested filename: sanitized attribute name + ".bin". */
    private String suggestFilename() {
        String safe = (attributeName == null ? "value" : attributeName)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) safe = "value";
        return safe + ".bin";
    }

    // ---------------- Hex display ----------------

    private HBox buildColumnHeader() {
        // Offset   00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F   ASCII
        StringBuilder header = new StringBuilder();
        header.append(String.format("%-10s", "Offset"));
        for (int i = 0; i < BYTES_PER_ROW; i++) {
            header.append(String.format("%02X ", i));
            if (i == 7) header.append(" ");
        }
        header.append("  ASCII");

        Label headerLabel = new Label(header.toString());
        headerLabel.setFont(MONO);
        headerLabel.setStyle("-fx-text-fill: -color-fg-muted;");

        HBox row = new HBox(headerLabel);
        row.setPadding(new Insets(4, 0, 0, 0));
        row.setStyle("-fx-background-color: -color-bg-subtle; "
                + "-fx-border-color: -color-border-default; -fx-border-width: 0 0 1 0;");
        return row;
    }

    /**
     * Build one display line per 16-byte row: offset, hex bytes (with a gap
     * after byte 7), and the ASCII gutter. Used to populate the virtualized
     * ListView in the hex view.
     */
    private static java.util.List<String> buildHexLines(byte[] data) {
        java.util.List<String> lines = new java.util.ArrayList<>(data.length / BYTES_PER_ROW + 1);
        if (data.length == 0) return lines;
        StringBuilder line = new StringBuilder(80);
        StringBuilder ascii = new StringBuilder(BYTES_PER_ROW);
        for (int offset = 0; offset < data.length; offset += BYTES_PER_ROW) {
            line.setLength(0);
            ascii.setLength(0);
            line.append(String.format("%04X:     ", offset));
            for (int i = 0; i < BYTES_PER_ROW; i++) {
                if (offset + i < data.length) {
                    byte b = data[offset + i];
                    line.append(String.format("%02X ", b));
                    ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                } else {
                    line.append("   ");
                    ascii.append(' ');
                }
                if (i == 7) line.append(' ');
            }
            line.append("  ").append(ascii);
            lines.add(line.toString());
        }
        return lines;
    }

    /** Format a byte count with a thousands-separator group for readability. */
    private static String formatBytesTotal(int bytes) {
        return String.format("%,d bytes total", bytes);
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
