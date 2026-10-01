package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.ConnectionProfile;
import com.pointbluetech.arborj.model.DirectoryType;
import com.pointbluetech.arborj.model.LDAPConnectionConfig;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Connection profile management window.
 * Uses Stage instead of Dialog for full button control.
 * Profiles are displayed in a grouped TreeView.
 */
public class ConnectionDialog {

    private static final String UNGROUPED = "Ungrouped";
    private static final DataFormat PROFILE_DATA_FORMAT =
            new DataFormat("application/x-arborj-profile-id");

    private final MainController controller;
    private final Stage stage;
    private final TreeView<Object> profileTree;
    private final TextField filterField;

    // Form fields
    private final TextField nameField = new TextField();
    private final ComboBox<String> groupCombo = new ComboBox<>();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final CheckBox tlsCheckbox = new CheckBox("Use TLS (LDAPS)");
    private final ComboBox<String> baseDNCombo = new ComboBox<>();
    private final TextField bindDNField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final CheckBox savePasswordCheckbox = new CheckBox("Save Password");
    private final ComboBox<DirectoryType> dirTypeCombo = new ComboBox<>();
    private final CheckBox pagedResultsCheckbox = new CheckBox("Use Paged Results");
    private final CheckBox readOnlyCheckbox = new CheckBox("Read-Only");
    private final CheckBox syncSecEquivCheckbox = new CheckBox("Sync Security Equivalence (eDirectory)");
    private boolean dirty = false; // track unsaved changes
    private boolean loading = false; // suppresses dirty flag during programmatic form updates
    private ConnectionProfile activeProfile = null; // the profile currently being edited

    public ConnectionDialog(MainController controller) {
        this(controller, null);
    }

    public ConnectionDialog(MainController controller, javafx.stage.Window owner) {
        this.controller = controller;
        this.stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("LDAP Connections");
        stage.setResizable(true);

        // Filter field
        filterField = new TextField();
        filterField.setPromptText("Filter profiles...");
        filterField.textProperty().addListener((obs, oldVal, newVal) -> rebuildTree());

        // TreeView setup
        TreeItem<Object> hiddenRoot = new TreeItem<>(null);
        hiddenRoot.setExpanded(true);
        profileTree = new TreeView<>(hiddenRoot);
        profileTree.setShowRoot(false);
        profileTree.setPrefWidth(210);

        // Cell factory with drag-and-drop and context menus
        profileTree.setCellFactory(tv -> {
            TreeCell<Object> cell = new TreeCell<>() {
                @Override
                protected void updateItem(Object item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                        setStyle("");
                        setContextMenu(null);
                    } else if (item instanceof String groupName) {
                        // Group node: folder emoji + bold name + count
                        int count = getTreeItem().getChildren().size();
                        Text folderIcon = new Text("\uD83D\uDCC1 ");
                        Text nameText = new Text(groupName);
                        nameText.setFont(Font.font(Font.getDefault().getFamily(),
                                FontWeight.BOLD, Font.getDefault().getSize()));
                        Text countText = new Text(" (" + count + ")");
                        countText.setStyle("-fx-fill: -color-fg-muted;");
                        setGraphic(new TextFlow(folderIcon, nameText, countText));
                        setText(null);
                        setStyle("");
                        setContextMenu(buildGroupContextMenu(groupName));
                    } else if (item instanceof ConnectionProfile profile) {
                        setText(profile.getName());
                        setGraphic(null);
                        setStyle("");
                        setContextMenu(buildProfileContextMenu(profile));
                    }
                }
            };

            // Drag detected
            cell.setOnDragDetected(event -> {
                if (cell.getItem() instanceof ConnectionProfile profile) {
                    Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.put(PROFILE_DATA_FORMAT, profile.getId());
                    db.setContent(content);
                    db.setDragView(cell.snapshot(null, null));
                    event.consume();
                }
            });

            // Drag over
            cell.setOnDragOver(event -> {
                if (event.getGestureSource() != cell
                        && event.getDragboard().hasContent(PROFILE_DATA_FORMAT)) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });

            // Drag dropped
            cell.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasContent(PROFILE_DATA_FORMAT)) {
                    String draggedId = (String) db.getContent(PROFILE_DATA_FORMAT);
                    ConnectionProfile draggedProfile = findProfileById(draggedId);
                    if (draggedProfile != null) {
                        String targetGroup;
                        int targetIndex;

                        Object cellItem = cell.getItem();
                        if (cellItem instanceof String groupName) {
                            // Dropped on a group header: move to end of that group
                            targetGroup = UNGROUPED.equals(groupName) ? "" : groupName;
                            targetIndex = getProfilesInGroup(targetGroup).size();
                        } else if (cellItem instanceof ConnectionProfile targetProfile) {
                            // Dropped on a profile: insert at that position
                            targetGroup = targetProfile.getGroup();
                            targetIndex = targetProfile.getSortOrder();
                        } else {
                            // Dropped on empty area
                            targetGroup = "";
                            targetIndex = getProfilesInGroup("").size();
                        }

                        String oldGroup = draggedProfile.getGroup();

                        // Update the dragged profile
                        draggedProfile.setGroup(targetGroup);
                        draggedProfile.setSortOrder(targetIndex);

                        // Renumber sort orders for affected groups, then persist
                        // both groups (and the dragged profile) in a single write.
                        List<ConnectionProfile> dirty = new ArrayList<>();
                        renumber(getProfilesInGroup(targetGroup));
                        dirty.addAll(getProfilesInGroup(targetGroup));
                        if (!oldGroup.equals(targetGroup)) {
                            renumber(getProfilesInGroup(oldGroup));
                            dirty.addAll(getProfilesInGroup(oldGroup));
                        }
                        if (!dirty.contains(draggedProfile)) dirty.add(draggedProfile);
                        controller.getProfileStore().saveAll(dirty);

                        rebuildTree();
                        selectProfileInTree(draggedProfile);
                        success = true;
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });

            return cell;
        });

        // Selection handling
        profileTree.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null && newVal.getValue() instanceof ConnectionProfile profile) {
                        loadProfile(profile);
                    } else if (newVal != null && newVal.getValue() instanceof String) {
                        // Group selected - clear form
                    }
                });

        // Double-click to connect
        profileTree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && activeProfile != null) {
                TreeItem<Object> selected = profileTree.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() instanceof ConnectionProfile) {
                    applyFormToProfile(activeProfile);
                    controller.getProfileStore().save(activeProfile);
                    dirty = false;
                    controller.connect(activeProfile, passwordField.getText());
                    stage.close();
                }
            }
        });

        // Buttons below tree
        Button newBtn = new Button("New");
        newBtn.setOnAction(e -> {
            String group = getSelectedGroup();
            ConnectionProfile p = new ConnectionProfile("New Connection",
                    new LDAPConnectionConfig());
            p.setGroup(UNGROUPED.equals(group) ? "" : group);
            p.setSortOrder(getProfilesInGroup(p.getGroup()).size());
            controller.getProfileStore().save(p);
            rebuildTree();
            selectProfileInTree(p);
        });

        Button duplicateBtn = new Button("Duplicate");
        duplicateBtn.setOnAction(e -> {
            if (activeProfile != null) {
                ConnectionProfile copy = controller.getProfileStore().duplicate(activeProfile);
                // Copy saved password to the duplicate
                if (activeProfile.isSavePassword()) {
                    String pwd = controller.getCredentialStore()
                            .loadPassword(activeProfile.getId());
                    if (pwd != null) {
                        controller.getCredentialStore().savePassword(copy.getId(), pwd);
                    }
                }
                rebuildTree();
                selectProfileInTree(copy);
            }
        });

        Button deleteBtn = new Button("Delete");
        deleteBtn.setOnAction(e -> {
            if (activeProfile != null) {
                controller.getProfileStore().delete(activeProfile);
                controller.getCredentialStore().deletePassword(activeProfile.getId());
                activeProfile = null;
                rebuildTree();
            }
        });

        Button exportBtn = new Button("Export");
        exportBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Export Profiles");
            fc.setInitialFileName("arborj-profiles.json");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("JSON Files", "*.json"));
            File file = fc.showSaveDialog(stage);
            if (file != null) {
                try {
                    List<ConnectionProfile> toExport = (activeProfile != null)
                            ? List.of(activeProfile)
                            : List.copyOf(controller.getProfileStore().getProfiles());
                    String json = controller.getProfileStore().exportProfiles(toExport);
                    Files.writeString(file.toPath(), json);
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR,
                            "Export failed: " + ex.getMessage()).showAndWait();
                }
            }
        });

        MenuButton importBtn = new MenuButton("Import");
        MenuItem importJsonItem = new MenuItem("Import JSON...");
        importJsonItem.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Import Profiles");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("JSON Files", "*.json"));
            File file = fc.showOpenDialog(stage);
            if (file != null) {
                try {
                    String json = Files.readString(file.toPath());
                    List<ConnectionProfile> imported =
                            controller.getProfileStore().importProfiles(json);
                    new Alert(Alert.AlertType.INFORMATION,
                            "Imported " + imported.size() + " profile(s).").showAndWait();
                    rebuildTree();
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR,
                            "Import failed: " + ex.getMessage()).showAndWait();
                }
            }
        });

        MenuItem importADSItem = new MenuItem("Import from Directory Studio...");
        importADSItem.setOnAction(e -> importFromADS());

        importBtn.getItems().addAll(importJsonItem, importADSItem);

        Button newGroupBtn = new Button("New Group");
        newGroupBtn.setOnAction(e -> {
            TextInputDialog dlg = new TextInputDialog();
            dlg.setTitle("New Group");
            dlg.setHeaderText(null);
            dlg.setContentText("Group name:");
            dlg.showAndWait().ifPresent(name -> {
                if (!name.isBlank()) {
                    // Create a group by adding it to the combo and rebuilding
                    // (groups exist implicitly when a profile references them,
                    //  but we can create an empty placeholder by just rebuilding with the name)
                    // Create a default profile in the new group so it appears
                    ConnectionProfile p = new ConnectionProfile("New Connection",
                            new LDAPConnectionConfig());
                    p.setGroup(name.trim());
                    p.setSortOrder(0);
                    controller.getProfileStore().save(p);
                    rebuildTree();
                    selectProfileInTree(p);
                }
            });
        });

        HBox listRow1 = new HBox(4, newBtn, newGroupBtn, duplicateBtn, deleteBtn);
        HBox listRow2 = new HBox(4, exportBtn, importBtn);
        VBox listButtons = new VBox(4, listRow1, listRow2);
        VBox leftPane = new VBox(6, filterField, profileTree, listButtons);
        VBox.setVgrow(profileTree, Priority.ALWAYS);

        // Form on right
        GridPane form = buildForm();
        ScrollPane formScroll = new ScrollPane(form);
        formScroll.setFitToWidth(true);
        formScroll.setPrefWidth(400);

        // Bottom buttons
        Button connectBtn = new Button("Connect");
        connectBtn.setDefaultButton(true);
        connectBtn.setOnAction(e -> {
            System.out.println("[ArborJ] Connect button clicked");

            // Use the actively-editing profile (not tree selection, which may shift)
            ConnectionProfile profile = activeProfile;
            if (profile == null) {
                TreeItem<Object> sel = profileTree.getSelectionModel().getSelectedItem();
                if (sel != null && sel.getValue() instanceof ConnectionProfile p) {
                    profile = p;
                }
            }
            // Quick Connect: no profile is selected/edited. Build a transient one
            // and DON'T persist it to disk — the user didn't ask to save it.
            boolean quickConnect = (profile == null);
            if (quickConnect) {
                profile = new ConnectionProfile(
                        nameField.getText().isEmpty() ? "Quick Connect" : nameField.getText(),
                        new LDAPConnectionConfig());
            }

            // Always apply form values to the profile before connecting
            applyFormToProfile(profile);
            if (!quickConnect) {
                controller.getProfileStore().save(profile);
            }

            System.out.println("[ArborJ] Profile: " + profile.getName()
                    + " host=" + profile.getConnection().getHost()
                    + " port=" + profile.getConnection().getPort()
                    + (quickConnect ? " (quick-connect, not persisted)" : ""));

            String password = passwordField.getText();
            dirty = false;
            controller.connect(profile, password);
            stage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> closeWithGuard());

        HBox bottomButtons = new HBox(8, cancelBtn, connectBtn);
        bottomButtons.setAlignment(Pos.CENTER_RIGHT);
        bottomButtons.setPadding(new Insets(8, 12, 8, 12));

        // Layout
        HBox topContent = new HBox(12, leftPane, formScroll);
        topContent.setPadding(new Insets(12));
        HBox.setHgrow(formScroll, Priority.ALWAYS);

        VBox root = new VBox(topContent, new Separator(), bottomButtons);
        VBox.setVgrow(topContent, Priority.ALWAYS);

        Scene scene = new Scene(root, 750, 650);
        stage.setScene(scene);

        // Close guard on window X button
        stage.setOnCloseRequest(e -> {
            if (dirty) {
                e.consume();
                closeWithGuard();
            }
        });

        // Track dirty state on form field edits (ignored while programmatically loading a profile)
        javafx.beans.InvalidationListener dirtyListener = obs -> {
            if (!loading) dirty = true;
        };
        nameField.textProperty().addListener(dirtyListener);
        groupCombo.valueProperty().addListener(dirtyListener);
        hostField.textProperty().addListener(dirtyListener);
        portField.textProperty().addListener(dirtyListener);
        baseDNCombo.valueProperty().addListener(dirtyListener);
        baseDNCombo.getEditor().textProperty().addListener(dirtyListener);
        bindDNField.textProperty().addListener(dirtyListener);
        passwordField.textProperty().addListener(dirtyListener);
        tlsCheckbox.selectedProperty().addListener(dirtyListener);
        savePasswordCheckbox.selectedProperty().addListener(dirtyListener);
        dirTypeCombo.valueProperty().addListener(dirtyListener);
        pagedResultsCheckbox.selectedProperty().addListener(dirtyListener);
        readOnlyCheckbox.selectedProperty().addListener(dirtyListener);
        syncSecEquivCheckbox.selectedProperty().addListener(dirtyListener);

        // Build tree and select first profile
        rebuildTree();
        if (!controller.getProfileStore().getProfiles().isEmpty()) {
            // Select the first profile in the first group
            TreeItem<Object> root2 = profileTree.getRoot();
            for (TreeItem<Object> groupItem : root2.getChildren()) {
                if (!groupItem.getChildren().isEmpty()) {
                    profileTree.getSelectionModel().select(groupItem.getChildren().get(0));
                    break;
                }
            }
        }
    }

    /**
     * Rebuilds the TreeView from the current profiles list, applying the filter.
     */
    private void rebuildTree() {
        TreeItem<Object> root = profileTree.getRoot();
        root.getChildren().clear();

        String filterText = filterField.getText();
        String lower = (filterText != null) ? filterText.toLowerCase() : "";

        ObservableList<ConnectionProfile> profiles = controller.getProfileStore().getProfiles();

        // Group profiles
        Map<String, List<ConnectionProfile>> grouped = new LinkedHashMap<>();
        for (ConnectionProfile p : profiles) {
            // Apply filter
            if (!lower.isEmpty() && !p.getName().toLowerCase().contains(lower)) {
                continue;
            }
            String groupName = (p.getGroup() == null || p.getGroup().isEmpty())
                    ? UNGROUPED : p.getGroup();
            grouped.computeIfAbsent(groupName, k -> new ArrayList<>()).add(p);
        }

        // Sort groups alphabetically, Ungrouped last
        List<String> sortedGroups = grouped.keySet().stream()
                .sorted((a, b) -> {
                    if (UNGROUPED.equals(a) && UNGROUPED.equals(b)) return 0;
                    if (UNGROUPED.equals(a)) return 1;
                    if (UNGROUPED.equals(b)) return -1;
                    return a.compareToIgnoreCase(b);
                })
                .collect(Collectors.toList());

        for (String groupName : sortedGroups) {
            TreeItem<Object> groupItem = new TreeItem<>(groupName);
            groupItem.setExpanded(true);

            // Sort profiles within group by sortOrder, then by name
            List<ConnectionProfile> groupProfiles = grouped.get(groupName);
            groupProfiles.sort(Comparator.comparingInt(ConnectionProfile::getSortOrder)
                    .thenComparing(ConnectionProfile::getName,
                            String.CASE_INSENSITIVE_ORDER));

            for (ConnectionProfile p : groupProfiles) {
                groupItem.getChildren().add(new TreeItem<>(p));
            }

            root.getChildren().add(groupItem);
        }
    }

    /**
     * Returns the group name currently selected (from tree selection).
     */
    private String getSelectedGroup() {
        TreeItem<Object> selected = profileTree.getSelectionModel().getSelectedItem();
        if (selected == null) return UNGROUPED;
        if (selected.getValue() instanceof String groupName) {
            return groupName;
        }
        if (selected.getValue() instanceof ConnectionProfile profile) {
            String g = profile.getGroup();
            return (g == null || g.isEmpty()) ? UNGROUPED : g;
        }
        return UNGROUPED;
    }

    /**
     * Selects a profile in the tree by reference.
     */
    private void selectProfileInTree(ConnectionProfile profile) {
        TreeItem<Object> root = profileTree.getRoot();
        for (TreeItem<Object> groupItem : root.getChildren()) {
            for (TreeItem<Object> child : groupItem.getChildren()) {
                if (child.getValue() instanceof ConnectionProfile p
                        && p.getId().equals(profile.getId())) {
                    profileTree.getSelectionModel().select(child);
                    int idx = profileTree.getRow(child);
                    if (idx >= 0) {
                        profileTree.scrollTo(idx);
                    }
                    return;
                }
            }
        }
    }

    /**
     * Find a profile by its ID from the store.
     */
    private ConnectionProfile findProfileById(String id) {
        for (ConnectionProfile p : controller.getProfileStore().getProfiles()) {
            if (p.getId().equals(id)) return p;
        }
        return null;
    }

    /**
     * Returns profiles in a given group (empty string = ungrouped).
     */
    private List<ConnectionProfile> getProfilesInGroup(String group) {
        String normalized = (group == null) ? "" : group;
        return controller.getProfileStore().getProfiles().stream()
                .filter(p -> {
                    String pg = (p.getGroup() == null) ? "" : p.getGroup();
                    return pg.equals(normalized);
                })
                .sorted(Comparator.comparingInt(ConnectionProfile::getSortOrder)
                        .thenComparing(ConnectionProfile::getName,
                                String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    /**
     * Recalculates sortOrder for all profiles in a group (0, 1, 2, ...).
     */
    private void recalcSortOrders(String group) {
        List<ConnectionProfile> inGroup = getProfilesInGroup(group);
        renumber(inGroup);
        controller.getProfileStore().saveAll(inGroup);
    }

    private static void renumber(List<ConnectionProfile> profiles) {
        for (int i = 0; i < profiles.size(); i++) {
            profiles.get(i).setSortOrder(i);
        }
    }

    /**
     * Returns all unique non-empty group names from existing profiles.
     */
    private List<String> getAllGroupNames() {
        return controller.getProfileStore().getProfiles().stream()
                .map(ConnectionProfile::getGroup)
                .filter(g -> g != null && !g.isEmpty())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    /**
     * Refreshes the group combo box items.
     */
    private void refreshGroupCombo() {
        String currentValue = groupCombo.getValue();
        groupCombo.getItems().setAll(getAllGroupNames());
        groupCombo.setValue(currentValue);
    }

    /**
     * Builds context menu for group nodes.
     */
    private ContextMenu buildGroupContextMenu(String groupName) {
        ContextMenu menu = new ContextMenu();

        MenuItem newConn = new MenuItem("New Connection");
        newConn.setOnAction(e -> {
            ConnectionProfile p = new ConnectionProfile("New Connection",
                    new LDAPConnectionConfig());
            p.setGroup(UNGROUPED.equals(groupName) ? "" : groupName);
            p.setSortOrder(getProfilesInGroup(p.getGroup()).size());
            controller.getProfileStore().save(p);
            rebuildTree();
            selectProfileInTree(p);
        });
        menu.getItems().add(newConn);

        if (!UNGROUPED.equals(groupName)) {
            MenuItem renameGroup = new MenuItem("Rename Group");
            renameGroup.setOnAction(e -> {
                TextInputDialog dialog = new TextInputDialog(groupName);
                dialog.setTitle("Rename Group");
                dialog.setHeaderText(null);
                dialog.setContentText("New group name:");
                dialog.showAndWait().ifPresent(newName -> {
                    if (newName != null && !newName.trim().isEmpty()
                            && !newName.equals(groupName)) {
                        List<ConnectionProfile> inGroup = getProfilesInGroup(groupName);
                        for (ConnectionProfile p : inGroup) {
                            p.setGroup(newName.trim());
                        }
                        controller.getProfileStore().saveAll(inGroup);
                        rebuildTree();
                        refreshGroupCombo();
                    }
                });
            });
            menu.getItems().add(renameGroup);

            MenuItem deleteGroup = new MenuItem("Delete Group");
            deleteGroup.setOnAction(e -> {
                // Move all profiles in this group to Ungrouped
                List<ConnectionProfile> moved = getProfilesInGroup(groupName);
                int nextOrder = getProfilesInGroup("").size();
                for (ConnectionProfile p : moved) {
                    p.setGroup("");
                    p.setSortOrder(nextOrder++);
                }
                controller.getProfileStore().saveAll(moved);
                recalcSortOrders("");
                rebuildTree();
                refreshGroupCombo();
            });
            menu.getItems().add(deleteGroup);
        }

        return menu;
    }

    /**
     * Builds context menu for profile nodes.
     */
    private ContextMenu buildProfileContextMenu(ConnectionProfile profile) {
        ContextMenu menu = new ContextMenu();

        Menu moveToGroup = new Menu("Move to Group");

        // Add all existing groups
        for (String group : getAllGroupNames()) {
            String profileGroup = (profile.getGroup() == null || profile.getGroup().isEmpty())
                    ? UNGROUPED : profile.getGroup();
            if (group.equals(profileGroup)) continue; // skip current group
            MenuItem item = new MenuItem(group);
            item.setOnAction(e -> {
                profile.setGroup(group);
                profile.setSortOrder(getProfilesInGroup(group).size());
                controller.getProfileStore().save(profile);
                recalcSortOrders(group);
                rebuildTree();
                selectProfileInTree(profile);
                refreshGroupCombo();
            });
            moveToGroup.getItems().add(item);
        }

        // Add Ungrouped option if profile is in a group
        if (profile.getGroup() != null && !profile.getGroup().isEmpty()) {
            MenuItem ungroupedItem = new MenuItem(UNGROUPED);
            ungroupedItem.setOnAction(e -> {
                String oldGroup = profile.getGroup();
                profile.setGroup("");
                profile.setSortOrder(getProfilesInGroup("").size());
                controller.getProfileStore().save(profile);
                recalcSortOrders(oldGroup);
                recalcSortOrders("");
                rebuildTree();
                selectProfileInTree(profile);
                refreshGroupCombo();
            });
            if (!moveToGroup.getItems().isEmpty()) {
                moveToGroup.getItems().add(new SeparatorMenuItem());
            }
            moveToGroup.getItems().add(ungroupedItem);
        }

        menu.getItems().add(moveToGroup);
        return menu;
    }

    private void closeWithGuard() {
        if (dirty) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "You have unsaved changes. Save before closing?",
                    ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText(null);
            alert.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    if (activeProfile != null) {
                        applyFormToProfile(activeProfile);
                        controller.getProfileStore().save(activeProfile);
                    }
                    stage.close();
                } else if (btn == ButtonType.NO) {
                    stage.close();
                }
                // CANCEL — do nothing, stay open
            });
        } else {
            stage.close();
        }
    }

    public void showAndWait() {
        stage.showAndWait();
    }

    private void fetchBaseDNs() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Enter a host first.").showAndWait();
            return;
        }
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { port = tlsCheckbox.isSelected() ? 636 : 389; }
        boolean useTLS = tlsCheckbox.isSelected();
        // Reuse the form's bind credentials so directories that disallow anonymous
        // Root DSE reads still return namingContexts.
        String bindDN = bindDNField.getText().trim();
        String password = passwordField.getText();

        // Temporarily connect to fetch naming contexts
        final int finalPort = port;
        Thread.startVirtualThread(() -> {
            var tempService = new com.pointbluetech.arborj.service.LDAPService();
            try {
                var config = new com.pointbluetech.arborj.model.LDAPConnectionConfig(
                        host, finalPort, useTLS, "", bindDN);

                // Load approved cert if available
                byte[] approvedCert = controller.getCredentialStore().loadApprovedCert(host, finalPort);
                tempService.connect(config, password, approvedCert);

                List<String> contexts = tempService.fetchNamingContexts();

                javafx.application.Platform.runLater(() -> {
                    baseDNCombo.getItems().clear();
                    baseDNCombo.getItems().add("(None)");
                    for (String ctx : contexts) {
                        if (!ctx.isEmpty()) {
                            baseDNCombo.getItems().add(ctx);
                        }
                    }
                    if (!baseDNCombo.getItems().isEmpty()) {
                        baseDNCombo.show();
                    }
                });
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() ->
                        new Alert(Alert.AlertType.ERROR,
                                "Failed to fetch base DNs: " + ex.getMessage()).showAndWait());
            } finally {
                try { tempService.disconnect(); } catch (Exception ignored) {}
            }
        });
    }

    private void importFromADS() {
        // Try to auto-detect the ADS connections file
        java.nio.file.Path adsFile = com.pointbluetech.arborj.service.ADSImporter.findConnectionsFile();

        if (adsFile == null) {
            // Not found — offer manual selection
            Alert notFound = new Alert(Alert.AlertType.INFORMATION,
                    "Apache Directory Studio connections file not found.\n\n"
                    + "Would you like to select it manually?",
                    ButtonType.YES, ButtonType.NO);
            notFound.setTitle("Import from Directory Studio");
            notFound.setHeaderText(null);
            var result = notFound.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.YES) return;

            FileChooser fc = new FileChooser();
            fc.setTitle("Select ADS connections.xml");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("XML Files", "*.xml"));
            File file = fc.showOpenDialog(stage);
            if (file == null) return;
            adsFile = file.toPath();
        }

        try {
            var importResult = com.pointbluetech.arborj.service.ADSImporter.importConnections(
                    adsFile, List.copyOf(controller.getProfileStore().getProfiles()));

            if (importResult.profiles().isEmpty()) {
                new Alert(Alert.AlertType.INFORMATION,
                        "No new connections to import."
                        + (importResult.skippedDuplicates() > 0
                                ? "\n" + importResult.skippedDuplicates() + " duplicate(s) skipped."
                                : "")).showAndWait();
                return;
            }

            // Save imported profiles
            for (ConnectionProfile p : importResult.profiles()) {
                controller.getProfileStore().save(p);
            }

            // Save passwords
            for (var pwd : importResult.passwords()) {
                controller.getCredentialStore().savePassword(pwd.profileId(), pwd.password());
            }

            rebuildTree();

            new Alert(Alert.AlertType.INFORMATION,
                    "Imported " + importResult.profiles().size() + " connection(s)"
                    + (importResult.skippedDuplicates() > 0
                            ? ", " + importResult.skippedDuplicates() + " duplicate(s) skipped"
                            : "")
                    + ".\n\nConnections are in the \"Imported from ADS\" group.").showAndWait();

        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR,
                    "Import failed: " + ex.getMessage()).showAndWait();
        }
    }

    private GridPane buildForm() {
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.setPadding(new Insets(8));

        int row = 0;
        form.add(new Label("Name:"), 0, row);
        form.add(nameField, 1, row++);

        form.add(new Label("Group:"), 0, row);
        groupCombo.setEditable(true);
        groupCombo.setPromptText("(none)");
        groupCombo.setMaxWidth(Double.MAX_VALUE);
        refreshGroupCombo();
        form.add(groupCombo, 1, row++);

        form.add(new Separator(), 0, row++, 2, 1);

        form.add(new Label("Host:"), 0, row);
        form.add(hostField, 1, row++);

        form.add(new Label("Port:"), 0, row);
        portField.setPrefWidth(80);
        form.add(portField, 1, row++);

        form.add(tlsCheckbox, 1, row++);
        tlsCheckbox.setOnAction(e -> {
            if (tlsCheckbox.isSelected() && "389".equals(portField.getText())) {
                portField.setText("636");
            } else if (!tlsCheckbox.isSelected() && "636".equals(portField.getText())) {
                portField.setText("389");
            }
        });

        form.add(new Separator(), 0, row++, 2, 1);

        form.add(new Label("Base DN:"), 0, row);
        baseDNCombo.setEditable(true);
        baseDNCombo.setMaxWidth(Double.MAX_VALUE);
        Button fetchBaseDNsBtn = new Button("Fetch");
        fetchBaseDNsBtn.setTooltip(new Tooltip("Fetch base DNs from Root DSE"));
        fetchBaseDNsBtn.setOnAction(ev -> fetchBaseDNs());
        HBox baseDNRow = new HBox(4, baseDNCombo, fetchBaseDNsBtn);
        baseDNRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(baseDNCombo, Priority.ALWAYS);
        form.add(baseDNRow, 1, row++);

        form.add(new Label("Bind DN:"), 0, row);
        form.add(bindDNField, 1, row++);

        Label passwordLabel = new Label("Password:");
        form.add(passwordLabel, 0, row);
        form.add(passwordField, 1, row++);

        Label anonLabel = new Label("(Anonymous bind — no password needed)");
        anonLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        form.add(anonLabel, 1, row++);

        form.add(savePasswordCheckbox, 1, row++);

        // Show/hide password fields based on bind DN
        Runnable updatePasswordVisibility = () -> {
            boolean hasBindDN = !bindDNField.getText().isBlank();
            passwordLabel.setVisible(hasBindDN);
            passwordLabel.setManaged(hasBindDN);
            passwordField.setVisible(hasBindDN);
            passwordField.setManaged(hasBindDN);
            savePasswordCheckbox.setVisible(hasBindDN);
            savePasswordCheckbox.setManaged(hasBindDN);
            anonLabel.setVisible(!hasBindDN);
            anonLabel.setManaged(!hasBindDN);
        };
        bindDNField.textProperty().addListener((obs, o, n) -> updatePasswordVisibility.run());
        updatePasswordVisibility.run();

        form.add(new Separator(), 0, row++, 2, 1);

        form.add(new Label("Directory Type:"), 0, row);
        dirTypeCombo.getItems().addAll(DirectoryType.values());
        dirTypeCombo.setValue(DirectoryType.AUTO);
        form.add(dirTypeCombo, 1, row++);

        form.add(pagedResultsCheckbox, 1, row++);
        pagedResultsCheckbox.setSelected(true);

        form.add(readOnlyCheckbox, 1, row++);

        syncSecEquivCheckbox.setSelected(true);
        syncSecEquivCheckbox.setTooltip(new Tooltip(
                "When editing group membership, also sync securityEquals\n"
                + "and equivalentToMe. Required over LDAP because eDirectory\n"
                + "does not do this automatically."));
        form.add(syncSecEquivCheckbox, 1, row++);

        // Make fields stretch
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(100);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelCol, fieldCol);

        // Monospaced font for DN/host fields
        Font mono = Font.font("monospaced", 12);
        hostField.setFont(mono);
        baseDNCombo.getEditor().setFont(mono);
        bindDNField.setFont(mono);

        return form;
    }

    private void loadProfile(ConnectionProfile profile) {
        if (profile == null) return;
        activeProfile = profile;
        loading = true;

        nameField.setText(profile.getName());
        groupCombo.setValue(
                (profile.getGroup() == null || profile.getGroup().isEmpty())
                        ? "" : profile.getGroup());
        refreshGroupCombo();
        LDAPConnectionConfig conn = profile.getConnection();
        hostField.setText(conn.getHost());
        portField.setText(String.valueOf(conn.getPort()));
        tlsCheckbox.setSelected(conn.isUseTLS());
        baseDNCombo.setValue(conn.getBaseDN());
        bindDNField.setText(conn.getBindDN());
        savePasswordCheckbox.setSelected(profile.isSavePassword());
        dirTypeCombo.setValue(conn.getDirectoryType());
        pagedResultsCheckbox.setSelected(conn.isUsePagedResults());
        readOnlyCheckbox.setSelected(conn.isReadOnly());
        syncSecEquivCheckbox.setSelected(conn.isSyncSecurityEquivalence());

        // Load the saved password only if the profile's "Save Password" flag
        // says we should. Otherwise leave the field empty even if a stale
        // credential is still sitting in the store — that stale entry will
        // get cleaned up the next time the user connects with the box
        // unchecked (see MainController.connect).
        String savedPassword = profile.isSavePassword()
                ? controller.getCredentialStore().loadPassword(profile.getId())
                : null;
        passwordField.setText(savedPassword != null ? savedPassword : "");

        // ComboBox editor-text updates can fire asynchronously; release after they settle.
        javafx.application.Platform.runLater(() -> {
            loading = false;
            dirty = false;
        });
    }

    private void applyFormToProfile(ConnectionProfile profile) {
        if (!nameField.getText().isEmpty()) {
            profile.setName(nameField.getText());
        }
        String groupValue = groupCombo.getValue();
        profile.setGroup((groupValue == null) ? "" : groupValue.trim());

        LDAPConnectionConfig conn = profile.getConnection();
        conn.setHost(hostField.getText());
        try {
            conn.setPort(Integer.parseInt(portField.getText()));
        } catch (NumberFormatException ignored) {}
        conn.setUseTLS(tlsCheckbox.isSelected());
        String baseDN = baseDNCombo.getEditor().getText();
        if (baseDN == null || "(None)".equals(baseDN.trim())) baseDN = "";
        conn.setBaseDN(baseDN.trim());
        conn.setBindDN(bindDNField.getText());
        conn.setDirectoryType(dirTypeCombo.getValue());
        conn.setUsePagedResults(pagedResultsCheckbox.isSelected());
        conn.setReadOnly(readOnlyCheckbox.isSelected());
        conn.setSyncSecurityEquivalence(syncSecEquivCheckbox.isSelected());
        profile.setSavePassword(savePasswordCheckbox.isSelected());
    }
}
