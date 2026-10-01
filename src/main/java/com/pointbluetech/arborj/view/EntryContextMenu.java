package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.service.AttributeSuggestSettings;
import com.pointbluetech.arborj.model.DirectoryType;
import com.pointbluetech.arborj.model.LDAPAttributeInfo;
import com.pointbluetech.arborj.model.LDAPAttributeSyntax;
import com.pointbluetech.arborj.model.LDAPObjectClassInfo;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the context menu shown on individual entries — used by both the
 * main search results list and the results table. Mirrors the tree's
 * per-entry menu, with the container-only "Export Children / Subtree as
 * LDIF" items omitted (search rows are individual entries, not containers
 * you are browsing into).
 */
public final class EntryContextMenu {

    private EntryContextMenu() {}

    public static ContextMenu build(MainController controller,
                                    String dn,
                                    List<String> objectClasses,
                                    Node owner) {
        ContextMenu menu = new ContextMenu();
        boolean readOnly = controller.readOnlyProperty().get();
        java.util.function.Supplier<Window> windowOf = () ->
                (owner != null && owner.getScene() != null) ? owner.getScene().getWindow() : null;

        MenuItem copyDnItem = new MenuItem("Copy DN");
        copyDnItem.setOnAction(e -> copyToClipboard(dn));
        menu.getItems().addAll(copyDnItem, new SeparatorMenuItem());

        MenuItem renameItem = new MenuItem("Rename Entry");
        renameItem.setOnAction(e -> showRenameDialog(controller, dn));

        MenuItem addAttrItem = new MenuItem("Add Attribute");
        addAttrItem.setOnAction(e -> promptAddAttribute(controller, dn, objectClasses, windowOf.get()));

        MenuItem newEntryItem = new MenuItem("New Entry");
        newEntryItem.setOnAction(e -> new NewEntryDialog(controller, dn, windowOf.get()).showAndWait());

        menu.getItems().addAll(renameItem, addAttrItem, newEntryItem);

        menu.getItems().add(new SeparatorMenuItem());
        MenuItem exportItem = new MenuItem("Export as LDIF");
        exportItem.setOnAction(e -> saveLDIF(
                controller.exportEntryAsLDIF(dn), rdnOf(dn), owner));
        menu.getItems().add(exportItem);

        if (controller.directoryTypeProperty().get() == DirectoryType.EDIRECTORY) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem aclItem = new MenuItem("Edit ACLs");
            aclItem.setOnAction(e ->
                    new com.pointbluetech.arborj.view.edir.ACLEditorView(controller, dn, windowOf.get()).showAndWait());
            MenuItem dirxmlAssocItem = new MenuItem("DirXML-Associations");
            dirxmlAssocItem.setOnAction(e ->
                    new com.pointbluetech.arborj.view.edir.AssociationModifierView(controller, dn, windowOf.get()).showAndWait());
            menu.getItems().addAll(aclItem, dirxmlAssocItem);
        }

        if (isUserObject(objectClasses) && supportsPasswordChange(controller)) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem pwdItem = new MenuItem("Change Password");
            pwdItem.setOnAction(e ->
                    new ChangePasswordDialog(controller, dn, windowOf.get()).showAndWait());
            menu.getItems().add(pwdItem);
        }

        menu.getItems().add(new SeparatorMenuItem());
        MenuItem deleteItem = new MenuItem("Delete Entry");
        deleteItem.setStyle("-fx-text-fill: #cc3333;");
        deleteItem.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + dn + "?", ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) controller.deleteEntry(dn);
            });
        });
        menu.getItems().add(deleteItem);

        if (readOnly) {
            renameItem.setDisable(true);
            addAttrItem.setDisable(true);
            newEntryItem.setDisable(true);
            deleteItem.setDisable(true);
        }

        return menu;
    }

    private static boolean isUserObject(List<String> objectClasses) {
        if (objectClasses == null) return false;
        for (String oc : objectClasses) {
            String lower = oc.toLowerCase();
            if (lower.contains("person") || lower.contains("user")
                    || lower.contains("account") || lower.contains("inetorgperson")) {
                return true;
            }
        }
        return false;
    }

    /**
     * AD changes the password via direct unicodePwd modify, so it doesn't need the
     * RFC 3062 extension. Other directories rely on Password Modify; if the server
     * doesn't advertise it, the dialog can't succeed and we hide the menu item.
     */
    private static boolean supportsPasswordChange(MainController controller) {
        return controller.directoryTypeProperty().get() == DirectoryType.ACTIVE_DIRECTORY
                || controller.passwordModifySupportedProperty().get();
    }

    private static String rdnOf(String dn) {
        if (dn == null) return "entry";
        int comma = dn.indexOf(',');
        return comma > 0 ? dn.substring(0, comma) : dn;
    }

    public static void promptAddAttribute(MainController controller, String dn,
                                           List<String> objectClasses) {
        promptAddAttribute(controller, dn, objectClasses, null);
    }

    public static void promptAddAttribute(MainController controller, String dn,
                                           List<String> objectClasses,
                                           Window owner) {
        // Union of MUST + MAY attributes across the entry's objectClasses, lowercased.
        // Null when the schema or objectClasses are unknown — we fall back to validating
        // only against the schema's full attribute map in that case.
        Set<String> allowedLower = computeAllowedAttributes(controller, objectClasses);

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Add Attribute");
        stage.setResizable(false);

        TextField nameField = new TextField();
        nameField.setPromptText("Attribute name");
        nameField.setPrefColumnCount(32);
        attachSchemaAutocomplete(nameField, controller, allowedLower);

        TextField valueField = new TextField();
        valueField.setPromptText("Value");
        valueField.setPrefColumnCount(32);
        HBox.setHgrow(valueField, Priority.ALWAYS);

        // DN picker button — shown only when the typed attribute has DN syntax.
        Button browseBtn = new Button("Browse…");
        browseBtn.setVisible(false);
        browseBtn.setManaged(false);
        browseBtn.setOnAction(e -> {
            DNPickerDialog picker = new DNPickerDialog(controller, valueField.getText(), stage);
            picker.showAndWait();
            String picked = picker.getSelectedDN();
            if (picked != null && !picked.isEmpty()) valueField.setText(picked);
        });
        HBox valueRow = new HBox(6, valueField, browseBtn);

        Runnable refreshBrowseVisibility = () -> {
            boolean dnSyntax = isDnSyntax(controller, nameField.getText());
            browseBtn.setVisible(dnSyntax);
            browseBtn.setManaged(dnSyntax);
        };
        nameField.textProperty().addListener((o, a, b) -> refreshBrowseVisibility.run());

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #cc3333;");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setMaxWidth(Double.MAX_VALUE);

        // Clear the error banner whenever the user edits either field.
        nameField.textProperty().addListener((o, a, b) -> {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
        });

        Button okBtn = new Button("Add");
        okBtn.setDefaultButton(true);
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> stage.close());

        okBtn.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String value = valueField.getText() == null ? "" : valueField.getText();
            if (name.isEmpty() || value.isEmpty()) return;

            // Reject attribute names not defined in the schema or not allowed on this
            // entry's objectClasses. Catching it here avoids eDirectory's confusing
            // "invalid DN syntax (-334)" error for schema violations.
            Map<String, LDAPAttributeInfo> attrMap = controller.getSchemaService().getAttributeMap();
            String canonicalName = name;
            if (!attrMap.isEmpty()) {
                LDAPAttributeInfo info = attrMap.get(name.toLowerCase());
                if (info == null) {
                    errorLabel.setText("'" + name + "' is not a known attribute in the schema. "
                            + "Check the spelling or pick one from the suggestions.");
                    errorLabel.setVisible(true);
                    errorLabel.setManaged(true);
                    nameField.requestFocus();
                    nameField.selectAll();
                    return;
                }
                canonicalName = info.getName();
            }
            if (allowedLower != null && !allowedLower.contains(canonicalName.toLowerCase())) {
                errorLabel.setText("'" + canonicalName
                        + "' is not allowed on this entry's object classes.");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                nameField.requestFocus();
                nameField.selectAll();
                return;
            }

            controller.saveAttributeValue(dn, canonicalName, null, value);
            stage.close();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancelBtn, okBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Label header = new Label("Add attribute to: " + dn);
        header.setStyle("-fx-font-weight: bold;");

        VBox root = new VBox(8,
                header,
                new Label("Attribute name:"), nameField,
                new Label("Value:"), valueRow,
                errorLabel,
                buttons);
        root.setPadding(new Insets(14));
        root.setPrefWidth(420);

        stage.setScene(new Scene(root));
        nameField.requestFocus();
        stage.showAndWait();
    }

    /** Places {@code text} on the system clipboard. JavaFX handles cross-platform plumbing. */
    public static void copyToClipboard(String text) {
        if (text == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * True when the named attribute exists in the schema and its syntax is DN —
     * callers use this to decide whether to show a DN picker for the value.
     */
    public static boolean isDnSyntax(MainController controller, String attributeName) {
        if (attributeName == null || attributeName.isBlank()) return false;
        LDAPAttributeInfo info = controller.getSchemaService()
                .getAttributeMap().get(attributeName.trim().toLowerCase());
        return info != null && info.getSyntax() == LDAPAttributeSyntax.DN;
    }

    /**
     * Union of MUST + MAY attribute names (lowercased) across the given objectClasses,
     * resolved with inheritance. Returns null when we can't determine the set (schema
     * not loaded, no objectClasses supplied, or none of them matched the schema).
     * Callers treat null as "don't filter — allow any attribute in the schema."
     */
    private static Set<String> computeAllowedAttributes(MainController controller,
                                                         List<String> objectClasses) {
        if (objectClasses == null || objectClasses.isEmpty()) return null;
        Map<String, LDAPObjectClassInfo> ocMap = controller.getSchemaService().getObjectClassMap();
        if (ocMap.isEmpty()) return null;
        Set<String> allowed = new HashSet<>();
        for (String oc : objectClasses) {
            LDAPObjectClassInfo info = ocMap.get(oc.toLowerCase());
            if (info == null) continue;
            for (String a : info.getMustAttributes()) allowed.add(a.toLowerCase());
            for (String a : info.getMayAttributes())  allowed.add(a.toLowerCase());
        }
        return allowed.isEmpty() ? null : allowed;
    }

    /**
     * Wires a prefix-match autocomplete popup onto {@code field}, using the
     * schema's attribute names as suggestions. When {@code allowedLower} is
     * non-null, only attributes allowed on the entry's object classes are
     * suggested.
     */
    private static void attachSchemaAutocomplete(TextField field, MainController controller,
                                                  Set<String> allowedLower) {
        Map<String, LDAPAttributeInfo> attrMap = controller.getSchemaService().getAttributeMap();
        if (attrMap == null || attrMap.isEmpty()) return;

        ListView<String> suggestions = new ListView<>();
        suggestions.setMaxHeight(200);
        suggestions.setPrefHeight(150);
        suggestions.setStyle("-fx-font-family: monospaced; -fx-font-size: 12;");

        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.getContent().add(suggestions);

        Runnable hide = popup::hide;
        Runnable show = () -> {
            if (suggestions.getItems().isEmpty() || field.getScene() == null) return;
            var bounds = field.localToScreen(field.getBoundsInLocal());
            if (bounds != null) {
                suggestions.setPrefWidth(field.getWidth());
                popup.show(field, bounds.getMinX(), bounds.getMaxY());
            }
        };

        Runnable update = () -> {
            if (!AttributeSuggestSettings.getInstance().isEnabled()) {
                hide.run();
                return;
            }
            String text = field.getText();
            String lower = text == null ? "" : text.toLowerCase().trim();
            if (lower.length() < 1) { hide.run(); return; }
            List<String> matches = attrMap.keySet().stream()
                    .filter(n -> n.startsWith(lower))
                    .filter(n -> allowedLower == null || allowedLower.contains(n))
                    .sorted()
                    .limit(12)
                    .toList();
            // Don't show the popup when the only match equals what the user typed.
            if (matches.isEmpty() || (matches.size() == 1 && matches.getFirst().equalsIgnoreCase(lower))) {
                hide.run();
                return;
            }
            suggestions.getItems().setAll(matches);
            suggestions.getSelectionModel().clearSelection();
            show.run();
        };

        Runnable apply = () -> {
            String selected = suggestions.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            field.setText(selected);
            field.positionCaret(selected.length());
            hide.run();
        };

        field.textProperty().addListener((obs, o, n) -> update.run());

        suggestions.setOnMouseClicked(ev -> apply.run());

        field.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (!popup.isShowing()) return;
            switch (ev.getCode()) {
                case DOWN -> {
                    int idx = suggestions.getSelectionModel().getSelectedIndex();
                    suggestions.getSelectionModel().select(Math.min(idx + 1, suggestions.getItems().size() - 1));
                    suggestions.scrollTo(suggestions.getSelectionModel().getSelectedIndex());
                    ev.consume();
                }
                case UP -> {
                    int idx = suggestions.getSelectionModel().getSelectedIndex();
                    suggestions.getSelectionModel().select(Math.max(idx - 1, 0));
                    suggestions.scrollTo(suggestions.getSelectionModel().getSelectedIndex());
                    ev.consume();
                }
                case ENTER, TAB -> {
                    if (suggestions.getSelectionModel().getSelectedIndex() >= 0) {
                        apply.run();
                        ev.consume();
                    }
                }
                case ESCAPE -> {
                    hide.run();
                    ev.consume();
                }
                default -> {}
            }
        });

        field.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) {
                PauseTransition pause = new PauseTransition(Duration.millis(200));
                pause.setOnFinished(ev -> { if (!field.isFocused()) hide.run(); });
                pause.play();
            }
        });
    }

    private static void showRenameDialog(MainController controller, String dn) {
        String currentRdn = rdnOf(dn);
        TextInputDialog dialog = new TextInputDialog(currentRdn);
        dialog.setTitle("Rename Entry");
        dialog.setHeaderText("Enter new RDN for: " + dn);
        dialog.setContentText("New RDN:");
        dialog.showAndWait().ifPresent(newRDN -> {
            if (!newRDN.isEmpty() && !newRDN.equals(currentRdn)) {
                controller.renameEntry(dn, newRDN, true);
            }
        });
    }

    private static void saveLDIF(String ldif, String suggestedName, Node owner) {
        if (ldif == null || ldif.isEmpty()) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Save LDIF");
        fc.setInitialFileName(suggestedName.replaceAll("[^a-zA-Z0-9._-]", "_") + ".ldif");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("LDIF Files", "*.ldif"));
        Window window = owner != null && owner.getScene() != null ? owner.getScene().getWindow() : null;
        File file = fc.showSaveDialog(window);
        if (file != null) {
            try {
                Files.writeString(file.toPath(), ldif);
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to save: " + ex.getMessage()).showAndWait();
            }
        }
    }
}
