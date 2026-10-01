package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.DirectoryType;
import com.pointbluetech.arborj.model.LDAPNode;
import javafx.collections.ListChangeListener;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;

import java.util.List;

/**
 * LDAP directory tree with lazy child loading and context menus.
 */
public class LDAPTreeView {

    private final MainController controller;
    private final TreeView<LDAPNode> treeView;
    private final TreeItem<LDAPNode> rootItem;

    public LDAPTreeView(MainController controller) {
        this.controller = controller;

        rootItem = new TreeItem<>();
        rootItem.setExpanded(true);

        treeView = new TreeView<>(rootItem);
        treeView.setShowRoot(false);
        treeView.setCellFactory(tv -> new LDAPTreeCell());

        // Bind root nodes from controller
        controller.getRootNodes().addListener((ListChangeListener<LDAPNode>) c -> {
            rootItem.getChildren().clear();
            for (LDAPNode node : controller.getRootNodes()) {
                TreeItem<LDAPNode> item = createTreeItem(node);
                rootItem.getChildren().add(item);
            }
        });

        // Selection listener
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                controller.selectNode(newVal.getValue());
            }
        });

        // Listen for "navigate to DN" requests from the controller. The
        // attribute table fires these when the user picks "Locate DN in DIT"
        // on a DN-syntax value.
        controller.navigationTargetDNProperty().addListener((obs, oldDn, newDn) -> {
            if (newDn != null && !newDn.isBlank()) {
                navigateToDN(newDn);
            }
        });
    }

    public TreeView<LDAPNode> getTreeView() {
        return treeView;
    }

    private TreeItem<LDAPNode> createTreeItem(LDAPNode node) {
        TreeItem<LDAPNode> item = new TreeItem<>(node);

        if (node.isChildrenLoaded() && !node.getChildren().isEmpty()) {
            // Children already loaded (e.g. root node) — populate immediately
            for (LDAPNode child : node.getChildren()) {
                item.getChildren().add(createTreeItem(child));
            }
        } else if (!node.isLeaf()) {
            item.getChildren().add(new TreeItem<>()); // dummy → expansion arrow
        }

        // Registered whatever the current leaf state is: Refresh can turn a
        // demoted leaf back into a container, and it needs this listener to
        // load children when the user opens it again.
        item.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
            if (isExpanded && !node.isChildrenLoaded()) {
                controller.loadChildren(node);
            }
        });

        // Leaf state can change after the item is built: a container the server
        // would not vouch for is drawn as expandable and turns into a leaf once
        // its one-level search comes back empty, and Refresh turns it back. Add
        // or drop the placeholder child to match, so the expansion arrow is
        // never left pointing at nothing — or missing from something that opens.
        node.leafProperty().addListener((obs, wasLeaf, isLeaf) -> {
            if (node.getChildren().isEmpty()) {
                if (isLeaf) {
                    item.setExpanded(false);
                    item.getChildren().clear();
                } else if (item.getChildren().isEmpty()) {
                    item.getChildren().add(new TreeItem<>()); // dummy
                }
            }
            // The cell reads leaf state directly, so repaint it.
            treeView.refresh();
        });

        // The cell prints the advertised child count beside the name. When the
        // real one-level search corrects that number — or clears it on a node
        // that turned out to be empty — the row has already been drawn, so
        // nothing would repaint it without this.
        node.totalChildCountProperty().addListener((obs, oldCount, newCount) -> treeView.refresh());

        // Listen for children changes — handle incrementally where possible
        node.getChildren().addListener((ListChangeListener<LDAPNode>) c -> {
            while (c.next()) {
                if (c.wasReplaced() || (c.wasRemoved() && c.wasAdded())) {
                    // setAll() — full replacement (initial load)
                    item.getChildren().clear();
                    for (LDAPNode child : node.getChildren()) {
                        item.getChildren().add(createTreeItem(child));
                    }
                } else if (c.wasAdded() && !c.wasRemoved()) {
                    // addAll() — append only (load more)
                    if (!item.getChildren().isEmpty()
                            && item.getChildren().getFirst().getValue() == null) {
                        item.getChildren().clear(); // remove dummy
                    }
                    for (LDAPNode added : c.getAddedSubList()) {
                        item.getChildren().add(createTreeItem(added));
                    }
                } else {
                    // Remove or other — full rebuild
                    item.getChildren().clear();
                    for (LDAPNode child : node.getChildren()) {
                        item.getChildren().add(createTreeItem(child));
                    }
                }
            }
        });

        return item;
    }

    // --- Custom Tree Cell ---

    private class LDAPTreeCell extends TreeCell<LDAPNode> {

        @Override
        protected void updateItem(LDAPNode node, boolean empty) {
            super.updateItem(node, empty);

            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }

            setFont(Font.font("System", FontWeight.NORMAL, 14));

            // Show subordinate count for containers
            if (!node.isLeaf() && node.getTotalChildCount() >= 0) {
                Label nameLabel = new Label(node.getRdn());
                nameLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
                Label countLabel = new Label("(" + node.getTotalChildCount() + ")");
                countLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
                javafx.scene.layout.HBox nameBox = new javafx.scene.layout.HBox(4, nameLabel, countLabel);
                nameBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setText(null);
                setGraphic(buildGraphicWithIcon(node, nameBox));
            } else {
                setText(node.getRdn());
                // Icon based on objectClass
                Label icon = new Label(getNodeIcon(node));
                icon.setTextFill(getNodeColor(node));
                setGraphic(icon);
            }

            // Loading indicator
            if (node.isLoading()) {
                ProgressIndicator spinner = new ProgressIndicator();
                spinner.setPrefSize(14, 14);
                setGraphic(spinner);
            }

            // Context menu
            setContextMenu(buildContextMenu(node));
        }

        private ContextMenu buildContextMenu(LDAPNode node) {
            ContextMenu menu = new ContextMenu();

            MenuItem copyDnItem = new MenuItem("Copy DN");
            copyDnItem.setOnAction(e -> EntryContextMenu.copyToClipboard(node.getDn()));
            menu.getItems().addAll(copyDnItem, new SeparatorMenuItem());

            // Core operations
            MenuItem renameItem = new MenuItem("Rename Entry");
            renameItem.setOnAction(e -> showRenameDialog(node));

            MenuItem addAttrItem = new MenuItem("Add Attribute");
            addAttrItem.setOnAction(e -> EntryContextMenu.promptAddAttribute(
                    controller, node.getDn(), node.getObjectClasses(), windowOf()));
            MenuItem newEntryItem = new MenuItem("New Entry");
            newEntryItem.setOnAction(e -> showNewEntryDialog(node));

            menu.getItems().addAll(renameItem, addAttrItem, newEntryItem);

            // Refresh is offered on leaves too. A container the server reported
            // as empty is demoted to a leaf, and hiding Refresh there left no
            // way to ask again once it had children.
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem refreshItem = new MenuItem("Refresh");
            refreshItem.setOnAction(e -> controller.refreshNode(node));
            menu.getItems().add(refreshItem);

            // Container-specific items
            if (!node.isLeaf()) {
                MenuItem exportChildrenItem = new MenuItem("Export Children as LDIF");
                exportChildrenItem.setOnAction(e -> saveLDIF(
                        controller.exportChildrenAsLDIF(node.getDn()), node.getRdn() + "_children"));
                MenuItem exportSubtreeItem = new MenuItem("Export Subtree as LDIF");
                exportSubtreeItem.setOnAction(e -> saveLDIF(
                        controller.exportSubtreeAsLDIF(node.getDn()), node.getRdn() + "_subtree"));
                menu.getItems().addAll(exportChildrenItem, exportSubtreeItem);
            }

            menu.getItems().add(new SeparatorMenuItem());

            // Export this entry
            MenuItem exportItem = new MenuItem("Export as LDIF");
            exportItem.setOnAction(e -> saveLDIF(
                    controller.exportEntryAsLDIF(node.getDn()), node.getRdn()));
            menu.getItems().add(exportItem);

            // Directory-specific items
            DirectoryType dirType = controller.directoryTypeProperty().get();
            if (dirType == DirectoryType.EDIRECTORY) {
                menu.getItems().add(new SeparatorMenuItem());
                MenuItem aclItem = new MenuItem("Edit ACLs");
                aclItem.setOnAction(e ->
                        new com.pointbluetech.arborj.view.edir.ACLEditorView(controller, node.getDn(), windowOf()).showAndWait());
                MenuItem dirxmlAssocItem = new MenuItem("DirXML-Associations");
                dirxmlAssocItem.setOnAction(e ->
                        new com.pointbluetech.arborj.view.edir.AssociationModifierView(controller, node.getDn(), windowOf()).showAndWait());
                menu.getItems().addAll(aclItem, dirxmlAssocItem);
            }

            // Password change (if user-like object and the server supports it)
            boolean canChangePassword = dirType == DirectoryType.ACTIVE_DIRECTORY
                    || controller.passwordModifySupportedProperty().get();
            if (isUserObject(node) && canChangePassword) {
                menu.getItems().add(new SeparatorMenuItem());
                MenuItem pwdItem = new MenuItem("Change Password");
                pwdItem.setOnAction(e -> {
                    ChangePasswordDialog dialog = new ChangePasswordDialog(controller, node.getDn(), windowOf());
                    dialog.showAndWait();
                });
                menu.getItems().add(pwdItem);
            }

            // Delete
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem deleteItem = new MenuItem("Delete Entry");
            deleteItem.setStyle("-fx-text-fill: #cc3333;");
            deleteItem.setOnAction(e -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Delete " + node.getDn() + "?", ButtonType.YES, ButtonType.NO);
                confirm.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.YES) controller.deleteEntry(node.getDn());
                });
            });
            menu.getItems().add(deleteItem);

            // Disable write operations if read-only
            if (controller.readOnlyProperty().get()) {
                renameItem.setDisable(true);
                addAttrItem.setDisable(true);
                newEntryItem.setDisable(true);
                deleteItem.setDisable(true);
            }

            return menu;
        }

        private boolean isUserObject(LDAPNode node) {
            List<String> ocs = node.getObjectClasses();
            for (String oc : ocs) {
                String lower = oc.toLowerCase();
                if (lower.contains("person") || lower.contains("user") ||
                        lower.contains("account") || lower.contains("inetorgperson")) {
                    return true;
                }
            }
            return false;
        }

        private javafx.scene.Node buildGraphicWithIcon(LDAPNode node, javafx.scene.Node content) {
            Label icon = new Label(getNodeIcon(node));
            icon.setTextFill(getNodeColor(node));
            javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(4, icon, content);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            return box;
        }

        private String getNodeIcon(LDAPNode node) {
            List<String> ocs = node.getObjectClasses();
            for (String oc : ocs) {
                String lower = oc.toLowerCase();
                if (lower.contains("computer")) return "\uD83D\uDDA5"; // desktop
                if (lower.contains("user") || lower.contains("person") ||
                        lower.contains("inetorgperson")) return "\uD83D\uDC64"; // person
                if (lower.contains("group")) return "\uD83D\uDC65"; // people
                if (lower.contains("organizationalunit") || lower.equals("ou")) return "\uD83D\uDCC1"; // folder
                if (lower.contains("organization") || lower.contains("domain")) return "\uD83C\uDF10"; // globe
                if (lower.contains("country")) return "\uD83C\uDFF3"; // flag
                if (lower.contains("printer")) return "\uD83D\uDDA8"; // printer
                if (lower.contains("volume") || lower.contains("filesystem")) return "\uD83D\uDCBE"; // disk
                if (lower.contains("container")) return "\uD83D\uDCE6"; // box
                if (lower.contains("server") || lower.contains("ncp")) return "\uD83D\uDD33"; // server
            }
            if (node.isNamingContext()) return "\uD83C\uDF10"; // globe
            return "\uD83D\uDCC4"; // document
        }

        private Color getNodeColor(LDAPNode node) {
            return Color.GRAY; // Icons are emoji, so color is inherent
        }

        private void saveLDIF(String ldif, String suggestedName) {
            if (ldif == null || ldif.isEmpty()) return;
            FileChooser fc = new FileChooser();
            fc.setTitle("Save LDIF");
            fc.setInitialFileName(suggestedName.replaceAll("[^a-zA-Z0-9._-]", "_") + ".ldif");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("LDIF Files", "*.ldif"));
            java.io.File file = fc.showSaveDialog(treeView.getScene().getWindow());
            if (file != null) {
                try {
                    java.nio.file.Files.writeString(file.toPath(), ldif);
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Failed to save: " + ex.getMessage()).showAndWait();
                }
            }
        }

        private void showRenameDialog(LDAPNode node) {
            TextInputDialog dialog = new TextInputDialog(node.getRdn());
            dialog.setTitle("Rename Entry");
            dialog.setHeaderText("Enter new RDN for: " + node.getDn());
            dialog.setContentText("New RDN:");
            dialog.showAndWait().ifPresent(newRDN -> {
                if (!newRDN.isEmpty() && !newRDN.equals(node.getRdn())) {
                    controller.renameEntry(node.getDn(), newRDN, true);
                }
            });
        }

        private void showNewEntryDialog(LDAPNode node) {
            NewEntryDialog dialog = new NewEntryDialog(controller, node.getDn(), windowOf());
            dialog.showAndWait();
        }

        private javafx.stage.Window windowOf() {
            return treeView.getScene() != null ? treeView.getScene().getWindow() : null;
        }
    }

    // ===========================================================================
    // "Locate DN in DIT" — async walk-down navigation
    // ===========================================================================

    /**
     * Expand the tree to the entry with the given DN and select it. Walks
     * level by level from the matching naming context, triggering child
     * loads as needed. Asynchronous: a single call may take several round
     * trips for deeply nested DNs.
     */
    public void navigateToDN(String targetDn) {
        if (targetDn == null || targetDn.isBlank()) return;
        TreeItem<LDAPNode> start = findStartingContext(targetDn);
        if (start == null) {
            new Alert(Alert.AlertType.WARNING,
                    "No naming context in the tree contains:\n" + targetDn)
                    .showAndWait();
            return;
        }
        walkDownTo(start, targetDn);
    }

    /**
     * Pick the root tree item whose DN is the deepest suffix of
     * {@code targetDn} — i.e. the naming context the target lives under.
     */
    private TreeItem<LDAPNode> findStartingContext(String targetDn) {
        String targetLower = targetDn.toLowerCase();
        TreeItem<LDAPNode> best = null;
        int bestLen = -1;
        for (TreeItem<LDAPNode> child : rootItem.getChildren()) {
            LDAPNode node = child.getValue();
            if (node == null || node.getDn() == null) continue;
            String dnLower = node.getDn().toLowerCase();
            if (dnLower.isEmpty()
                    || targetLower.equals(dnLower)
                    || targetLower.endsWith("," + dnLower)) {
                if (dnLower.length() > bestLen) {
                    best = child;
                    bestLen = dnLower.length();
                }
            }
        }
        return best;
    }

    /**
     * Recursive async walk: load children if needed, find the matching
     * child by DN, recurse. Each level may be sync (already loaded) or
     * async (load → wait for {@code loading} property to flip false).
     */
    private void walkDownTo(TreeItem<LDAPNode> from, String targetDn) {
        LDAPNode node = from.getValue();
        if (node == null) return;
        String nodeDn = node.getDn() == null ? "" : node.getDn();

        if (targetDn.equalsIgnoreCase(nodeDn)) {
            // Reached the target.
            treeView.getSelectionModel().select(from);
            int row = treeView.getRow(from);
            if (row >= 0) treeView.scrollTo(Math.max(0, row - 3));
            treeView.requestFocus();
            return;
        }

        String childDn = nextChildDn(targetDn, nodeDn);
        if (childDn == null) {
            navigateFailed(targetDn, "couldn't compute next-level DN past " + nodeDn);
            return;
        }

        // Expand the current node so the TreeItems for its children get
        // realized when the children list populates.
        from.setExpanded(true);

        if (node.isChildrenLoaded()) {
            findChildAndContinue(from, childDn, targetDn);
            return;
        }
        // Async load — listen for the loading flag to flip false, then
        // continue. Remove ourselves once we've handled the transition.
        node.loadingProperty().addListener(new javafx.beans.value.ChangeListener<Boolean>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Boolean> obs,
                                Boolean was, Boolean now) {
                if (was != null && was && !now) {
                    obs.removeListener(this);
                    javafx.application.Platform.runLater(() ->
                            findChildAndContinue(from, childDn, targetDn));
                }
            }
        });
        if (!node.isLoading()) controller.loadChildren(node);
    }

    private void findChildAndContinue(TreeItem<LDAPNode> from, String childDn, String targetDn) {
        for (TreeItem<LDAPNode> child : from.getChildren()) {
            LDAPNode cn = child.getValue();
            if (cn != null && cn.getDn() != null
                    && childDn.equalsIgnoreCase(cn.getDn())) {
                walkDownTo(child, targetDn);
                return;
            }
        }
        navigateFailed(targetDn, "couldn't find " + childDn + " under "
                + (from.getValue() == null ? "(?)" : from.getValue().getDn()));
    }

    /**
     * Given a target DN and the DN of an ancestor we've already reached,
     * return the DN of the ancestor's direct child that is on the path to
     * the target. Honors backslash-escaped commas in RDNs.
     */
    private static String nextChildDn(String targetDn, String parentDn) {
        if (parentDn == null) return null;
        String pLower = parentDn.toLowerCase();
        String tLower = targetDn.toLowerCase();
        if (parentDn.isEmpty()) {
            // Walking down from the empty root: the entire target is the
            // path remainder.
            int lastComma = lastUnescapedComma(targetDn);
            return lastComma >= 0 ? targetDn.substring(lastComma + 1) : targetDn;
        }
        if (!tLower.endsWith("," + pLower)) return null;
        String relative = targetDn.substring(0, targetDn.length() - parentDn.length() - 1);
        int lastComma = lastUnescapedComma(relative);
        String childRdn = lastComma >= 0 ? relative.substring(lastComma + 1) : relative;
        return childRdn + "," + parentDn;
    }

    /** Last comma in {@code dn} that isn't preceded by a backslash. */
    private static int lastUnescapedComma(String dn) {
        for (int i = dn.length() - 1; i >= 0; i--) {
            if (dn.charAt(i) == ',' && (i == 0 || dn.charAt(i - 1) != '\\')) return i;
        }
        return -1;
    }

    private void navigateFailed(String targetDn, String reason) {
        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    "Couldn't locate " + targetDn + " in the tree.\n\n"
                    + "Reason: " + reason + ".\n\n"
                    + "The entry may not exist, may be in a partition the connected "
                    + "server doesn't hold, or your bound user may lack browse rights "
                    + "on an intermediate container.");
            alert.setHeaderText(null);
            if (treeView.getScene() != null) {
                alert.initOwner(treeView.getScene().getWindow());
            }
            alert.showAndWait();
        });
    }
}
