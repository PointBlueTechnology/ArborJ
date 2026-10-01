package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.ConnectionProfile;
import com.pointbluetech.arborj.model.DirectoryType;
import com.pointbluetech.arborj.view.ad.*;
import com.pointbluetech.arborj.view.edir.*;
import com.pointbluetech.arborj.view.openldap.*;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Main application view: 2-pane split with sidebar tree and detail area.
 */
public class MainView {

    private final MainController controller;
    private final SplitPane root;
    private final SplitPane mainSplit;
    private final LogPaneView logPaneView = new LogPaneView();
    private final LDAPTreeView treeView;
    private final AttributeTableView attributeTableView;
    private final com.pointbluetech.arborj.service.UpdateChecker updateChecker =
            new com.pointbluetech.arborj.service.UpdateChecker();
    private SplitPane contentSplit;
    private VBox searchResultsList;
    private javafx.scene.control.TextField searchFilterField;
    private java.util.List<String> selectedSearchAttributes = java.util.List.of("*");
    private Label tableViewLink;
    private boolean smartInsertEnabled = java.util.prefs.Preferences
            .userNodeForPackage(MainView.class).getBoolean("smartInsert", true);
    /** Search-filter attribute suggestions. A heavyweight Popup, so it can take focus. */
    private javafx.stage.Popup searchSuggestionPopup;
    /** When Esc dismissed suggestions; a follow-up delivery of that key must not clear results. */
    private boolean suggestionEscapeDismissed;
    private long suggestionEscapeDismissedAtNanos;
    private static final long SUGGESTION_ESCAPE_GUARD_NANOS = 250_000_000L;
    private final javafx.beans.property.BooleanProperty logPaneVisible =
            new javafx.beans.property.SimpleBooleanProperty(false);

    public MainView(MainController controller) {
        this.controller = controller;
        this.treeView = new LDAPTreeView(controller);
        this.attributeTableView = new AttributeTableView(controller);

        // Left sidebar
        VBox sidebar = buildSidebar();
        sidebar.setMinWidth(220);
        sidebar.setPrefWidth(280);

        // Right detail pane
        VBox detailPane = buildDetailPane();

        // Inner horizontal split: sidebar | detail.
        mainSplit = new SplitPane(sidebar, detailPane);
        mainSplit.setDividerPositions(0.25);
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        SplitPane.setResizableWithParent(sidebar, false);

        // Outer vertical split: main content / log pane (log pane added on toggle).
        root = new SplitPane(mainSplit);
        root.setOrientation(Orientation.VERTICAL);
        SplitPane.setResizableWithParent(logPaneView.getRoot(), false);

        // Wire visibility: single source of truth in logPaneVisible property.
        // Starts hidden on every launch; user opens it on demand via the menu.
        logPaneVisible.addListener((o, a, b) -> applyLogPaneVisibility(b));
        logPaneView.setOnClose(() -> logPaneVisible.set(false));

        // Keyboard shortcuts (SHORTCUT_DOWN = Cmd on Mac, Ctrl on Win/Linux)
        root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown()) {
                switch (e.getCode()) {
                    case F -> { // Shortcut+F: Focus search
                        if (searchFilterField != null) {
                            searchFilterField.requestFocus();
                            searchFilterField.selectAll();
                        }
                        e.consume();
                    }
                    case N -> {
                        if (e.isShiftDown()) {
                            // Shortcut+Shift+N: New window
                            com.pointbluetech.arborj.ArborJApp.openNewWindow();
                        } else {
                            // Shortcut+N: New connection
                            showConnectionDialog();
                        }
                        e.consume();
                    }
                    case W -> { // Shortcut+W: Close window (matches macOS convention)
                        var w = root.getScene() != null ? root.getScene().getWindow() : null;
                        if (w != null) {
                            w.fireEvent(new javafx.stage.WindowEvent(
                                    w, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
                        }
                        e.consume();
                    }
                    case COMMA -> { // Shortcut+,: Settings
                        showSettingsDialog();
                        e.consume();
                    }
                    case E -> { // Shortcut+E: Export selected entry as LDIF
                        String dn = controller.selectedDNProperty().get();
                        if (dn != null) {
                            String ldif = controller.exportEntryAsLDIF(dn);
                            if (ldif != null) {
                                saveLDIF(ldif, dn.contains(",")
                                        ? dn.substring(0, dn.indexOf(',')) : dn, root);
                            }
                        }
                        e.consume();
                    }
                    case R -> { // Shortcut+R: Refresh selected tree node (Cmd+R / Ctrl+R)
                        refreshSelectedNodeOrTree();
                        e.consume();
                    }
                    default -> {}
                }
            } else if (e.getCode() == javafx.scene.input.KeyCode.F5) {
                // F5: standard refresh — same behavior as Cmd+R / Ctrl+R.
                refreshSelectedNodeOrTree();
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                // Dismiss attribute suggestions first. This filter runs in the
                // capture phase, before the text field, and Esc must not also
                // clear search results — including a second delivery after the
                // popup window hid and focus returned here.
                if (isSearchSuggestionPopupShowing() || suggestionEscapeJustDismissed()) {
                    dismissSearchSuggestions();
                    e.consume();
                } else if (!controller.getSearchResults().isEmpty()) {
                    controller.clearSearch();
                    contentSplit.getItems().remove(searchResultsList);
                    e.consume();
                }
            }
        });

        // Certificate trust dialog listener
        controller.pendingCertDetailsProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                CertTrustDialog dialog = new CertTrustDialog(newVal);
                dialog.showAndWait().ifPresent(trusted -> {
                    if (trusted) {
                        controller.trustCertificateAndReconnect();
                    } else {
                        controller.cancelCertTrust();
                    }
                });
            }
        });
    }

    public SplitPane getRoot() {
        return root;
    }

    private void applyLogPaneVisibility(boolean visible) {
        boolean currently = root.getItems().contains(logPaneView.getRoot());
        if (visible && !currently) {
            root.getItems().add(logPaneView.getRoot());
            root.setDividerPositions(0.72);
        } else if (!visible && currently) {
            root.getItems().remove(logPaneView.getRoot());
        }
    }

    // --- Sidebar ---

    private VBox buildSidebar() {
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");

        // Error banner — shows both tree errors and connection errors
        Label errorBanner = new Label();
        errorBanner.getStyleClass().add("error-banner");
        errorBanner.setWrapText(true);
        errorBanner.setMaxWidth(Double.MAX_VALUE);
        errorBanner.managedProperty().bind(errorBanner.visibleProperty());
        errorBanner.setVisible(false);

        Runnable updateErrorBanner = () -> {
            String treeErr = controller.treeErrorProperty().get();
            String connErr = controller.connectionErrorProperty().get();
            String msg = treeErr != null ? treeErr : connErr;
            errorBanner.setText(msg);
            errorBanner.setVisible(msg != null && !msg.isEmpty());
        };
        controller.treeErrorProperty().addListener((obs, o, n) -> updateErrorBanner.run());
        controller.connectionErrorProperty().addListener((obs, o, n) -> updateErrorBanner.run());

        // Tree or placeholder
        StackPane treeContainer = new StackPane();
        VBox.setVgrow(treeContainer, Priority.ALWAYS);

        // Placeholder for disconnected/connecting state
        VBox placeholder = buildDisconnectedPlaceholder();
        placeholder.visibleProperty().bind(controller.connectedProperty().not());
        placeholder.managedProperty().bind(placeholder.visibleProperty());

        // Connecting spinner overlay
        VBox connectingOverlay = new VBox(12);
        connectingOverlay.setAlignment(Pos.CENTER);
        ProgressIndicator connectSpinner = new ProgressIndicator();
        connectSpinner.setPrefSize(40, 40);
        Label connectingLabel = new Label("Connecting...");
        connectingLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        connectingOverlay.getChildren().addAll(connectSpinner, connectingLabel);
        connectingOverlay.visibleProperty().bind(controller.connectingProperty());
        connectingOverlay.managedProperty().bind(connectingOverlay.visibleProperty());

        // Tree view
        var tree = treeView.getTreeView();
        tree.visibleProperty().bind(controller.connectedProperty());
        tree.managedProperty().bind(tree.visibleProperty());

        treeContainer.getChildren().addAll(tree, placeholder, connectingOverlay);

        sidebar.getChildren().addAll(errorBanner, treeContainer);
        return sidebar;
    }

    private VBox buildDisconnectedPlaceholder() {
        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));

        Label icon = new Label("\uD83C\uDF10"); // Globe emoji
        icon.setStyle("-fx-font-size: 48;");

        Label label = new Label("Not Connected");
        label.setFont(Font.font("System", FontWeight.MEDIUM, 16));
        label.setStyle("-fx-text-fill: -color-fg-muted;");

        Button connectButton = new Button("Connect...");
        connectButton.setOnAction(e -> showConnectionDialog());

        box.getChildren().addAll(icon, label, connectButton);

        // Hide placeholder content while connecting (spinner overlay shows instead)
        controller.connectingProperty().addListener((obs, o, isConnecting) -> {
            icon.setVisible(!isConnecting);
            label.setVisible(!isConnecting);
            connectButton.setVisible(!isConnecting);
        });

        return box;
    }

    // --- Detail Pane ---

    private VBox buildDetailPane() {
        VBox detailPane = new VBox();

        // Update banner (non-intrusive, dismissible)
        HBox updateBanner = buildUpdateBanner();
        detailPane.getChildren().add(updateBanner);

        // Toolbar
        ToolBar toolbar = buildToolbar();

        // Search bar
        VBox searchBar = buildSearchBar();

        // Content area: attribute table is always on the right.
        // In search mode, a results list appears on the left as a split pane.
        // In browse mode, just the attribute table fills the space.
        var attrTable = attributeTableView.getRoot();

        // Search results list (left side, only visible during search)
        searchResultsList = buildSearchResultsList();

        contentSplit = new SplitPane();
        contentSplit.setOrientation(Orientation.HORIZONTAL);
        VBox.setVgrow(contentSplit, Priority.ALWAYS);

        // Start with just attribute table
        contentSplit.getItems().add(attrTable);

        // When search results change, show/hide the results list
        controller.getSearchResults().addListener(
                (javafx.collections.ListChangeListener<? super com.pointbluetech.arborj.model.LDAPEntry>) c -> {
                    boolean hasResults = !controller.getSearchResults().isEmpty();
                    if (hasResults && !contentSplit.getItems().contains(searchResultsList)) {
                        contentSplit.getItems().addFirst(searchResultsList);
                        contentSplit.setDividerPositions(0.35);
                    } else if (!hasResults) {
                        contentSplit.getItems().remove(searchResultsList);
                    }
                });

        // Also show when searching starts
        controller.searchingProperty().addListener((obs, wasSearching, isSearching) -> {
            if (isSearching && !contentSplit.getItems().contains(searchResultsList)) {
                contentSplit.getItems().addFirst(searchResultsList);
                contentSplit.setDividerPositions(0.35);
            }
        });

        // When tree selection changes, remove search results pane
        controller.selectedNodeProperty().addListener((obs, oldNode, newNode) -> {
            if (newNode != null) {
                contentSplit.getItems().remove(searchResultsList);
            }
        });

        detailPane.getChildren().addAll(toolbar, searchBar, contentSplit);
        return detailPane;
    }

    private ToolBar buildToolbar() {
        ToolBar toolbar = new ToolBar();

        // Connection status
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);
        spinner.visibleProperty().bind(controller.connectingProperty().or(controller.reconnectingProperty()));
        spinner.managedProperty().bind(spinner.visibleProperty());

        Label statusLabel = new Label();
        controller.connectedProfileNameProperty().addListener((obs, oldVal, newVal) -> {
            statusLabel.setText(newVal != null ? newVal : "");
        });
        // Show "Reconnecting..." when auto-reconnecting
        controller.reconnectingProperty().addListener((obs, oldVal, isReconnecting) -> {
            if (isReconnecting) {
                statusLabel.setText("Reconnecting...");
            } else {
                String name = controller.connectedProfileNameProperty().get();
                statusLabel.setText(name != null ? name : "");
            }
        });

        // Read-only badge
        Label readOnlyBadge = new Label("Read-Only");
        readOnlyBadge.getStyleClass().add("read-only-badge");
        readOnlyBadge.visibleProperty().bind(controller.readOnlyProperty());
        readOnlyBadge.managedProperty().bind(readOnlyBadge.visibleProperty());

        // Directory type label
        Label dirTypeLabel = new Label();
        controller.directoryTypeProperty().addListener((obs, oldVal, newVal) -> {
            dirTypeLabel.setText(newVal != null && newVal != DirectoryType.AUTO
                    ? newVal.getDisplayName() : "");
        });
        dirTypeLabel.setStyle("-fx-text-fill: -color-fg-muted;");

        // Spacer
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Directory services menu
        MenuButton dirServicesBtn = new MenuButton("Directory Services");
        dirServicesBtn.visibleProperty().bind(controller.connectedProperty());
        dirServicesBtn.managedProperty().bind(dirServicesBtn.visibleProperty());
        // Items added dynamically based on directory type
        controller.directoryTypeProperty().addListener((obs, oldVal, newVal) -> {
            dirServicesBtn.getItems().clear();
            if (newVal == DirectoryType.ACTIVE_DIRECTORY) {
                MenuItem domainInfo = new MenuItem("Domain Info");
                domainInfo.setOnAction(ev -> new ADDomainInfoView(controller, windowOf()).showAndWait());
                MenuItem pwdPolicy = new MenuItem("Password Policy");
                pwdPolicy.setOnAction(ev -> new ADPasswordPolicyView(controller, windowOf()).showAndWait());
                MenuItem acctStatus = new MenuItem("Account Status");
                acctStatus.setOnAction(ev -> {
                    String dn = controller.selectedDNProperty().get();
                    if (dn != null) new ADAccountStatusView(controller, dn, windowOf()).showAndWait();
                });
                MenuItem groupMembership = new MenuItem("Group Membership");
                groupMembership.setOnAction(ev -> {
                    String dn = controller.selectedDNProperty().get();
                    if (dn != null) new ADGroupMembershipView(controller, dn, windowOf()).showAndWait();
                });
                MenuItem replMetadata = new MenuItem("Replication Metadata");
                replMetadata.setOnAction(ev -> {
                    String dn = controller.selectedDNProperty().get();
                    if (dn != null) new ADReplicationMetadataView(controller, dn, windowOf()).showAndWait();
                });
                dirServicesBtn.getItems().addAll(domainInfo, pwdPolicy,
                        new SeparatorMenuItem(), acctStatus, groupMembership, replMetadata);
            } else if (newVal == DirectoryType.EDIRECTORY) {
                MenuItem effRights = new MenuItem("Effective Rights");
                effRights.setOnAction(ev -> new EffectiveRightsView(controller, windowOf()).showAndWait());
                effRights.visibleProperty().bind(controller.effectiveRightsSupportedProperty());
                MenuItem dirxml = new MenuItem("DirXML Command");
                dirxml.setOnAction(ev -> new DirXMLCommandView(controller, windowOf()).showAndWait());
                dirxml.visibleProperty().bind(controller.dirXMLSupportedProperty());
                MenuItem dirxmlAssoc = new MenuItem("DirXML Associations");
                dirxmlAssoc.setOnAction(ev -> new AssociationModifierView(controller, null, windowOf()).showAndWait());
                MenuItem replStatus = new MenuItem("Replication Status");
                replStatus.setOnAction(ev -> new ReplicationStatusView(controller, windowOf()).showAndWait());
                MenuItem partSync = new MenuItem("Partition Status");
                partSync.setOnAction(ev ->
                        new com.pointbluetech.arborj.view.edir.PartitionSyncView(controller, windowOf()).showAndWait());
                dirServicesBtn.getItems().addAll(effRights, dirxml, dirxmlAssoc, replStatus, partSync);
            } else if (newVal == DirectoryType.OPENLDAP) {
                MenuItem serverMon = new MenuItem("Server Monitor");
                serverMon.setOnAction(ev -> new ServerMonitorView(controller, windowOf()).showAndWait());
                MenuItem serverStats = new MenuItem("Server Statistics");
                serverStats.setOnAction(ev -> new ServerStatisticsView(controller, windowOf()).showAndWait());
                dirServicesBtn.getItems().addAll(serverMon, serverStats);
            }
        });

        // LDIF Import
        Button importBtn = new Button("Import LDIF");
        importBtn.visibleProperty().bind(controller.connectedProperty()
                .and(controller.readOnlyProperty().not()));
        importBtn.managedProperty().bind(importBtn.visibleProperty());
        importBtn.setOnAction(e -> {
            LDIFImportDialog dialog = new LDIFImportDialog(controller, windowOf());
            dialog.showAndWait();
        });

        // Connect/Disconnect
        Button connectBtn = new Button("Connect...");
        connectBtn.textProperty().bind(javafx.beans.binding.Bindings.when(controller.connectedProperty())
                .then("Disconnect").otherwise("Connect..."));
        connectBtn.setOnAction(e -> {
            if (controller.connectedProperty().get()) {
                controller.disconnect();
            } else {
                showConnectionDialog();
            }
        });

        // Settings menu
        Label gearLabel = new Label("\u2699");
        gearLabel.setStyle("-fx-font-size: 24;");
        MenuButton settingsBtn = new MenuButton();
        settingsBtn.setGraphic(gearLabel);
        settingsBtn.setTooltip(new Tooltip("Settings"));
        MenuItem newWindowItem = new MenuItem("New Window");
        newWindowItem.setOnAction(e -> com.pointbluetech.arborj.ArborJApp.openNewWindow());
        MenuItem settingsItem = new MenuItem("Settings...");
        settingsItem.setOnAction(e -> showSettingsDialog());
        MenuItem checkUpdateItem = new MenuItem("Check for Updates...");
        checkUpdateItem.setOnAction(e -> {
            updateChecker.dismissedProperty().set(false);
            com.pointbluetech.arborj.service.UpdateChecker.resetSession();

            // Listen for completion of the check before showing any alert. The listener
            // removes itself after firing so subsequent checks don't trigger stale alerts.
            javafx.beans.value.ChangeListener<Boolean>[] holder =
                    new javafx.beans.value.ChangeListener[1];
            holder[0] = (obs, wasChecking, isChecking) -> {
                if (isChecking || wasChecking == null || !wasChecking) return;
                updateChecker.checkingProperty().removeListener(holder[0]);
                if (updateChecker.updateAvailableProperty().get()) return;
                Alert alert;
                javafx.stage.Window owner = root.getScene() != null ? root.getScene().getWindow() : null;
                if (updateChecker.checkFailedProperty().get()) {
                    alert = new Alert(Alert.AlertType.WARNING,
                            "Unable to check for updates. Please check your network connection and try again.");
                } else {
                    alert = new Alert(Alert.AlertType.INFORMATION, "ArborJ is up to date (v"
                            + com.pointbluetech.arborj.ArborJApp.APP_VERSION + ").");
                }
                if (owner != null) alert.initOwner(owner);
                alert.show();
            };
            updateChecker.checkingProperty().addListener(holder[0]);
            updateChecker.checkAsync(com.pointbluetech.arborj.ArborJApp.APP_VERSION);
        });
        MenuItem supportItem = new MenuItem("Support...");
        supportItem.setOnAction(e -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://www.pointbluetech.com/arborj/")); }
            catch (Exception ignored) {}
        });
        MenuItem showLogsItem = new MenuItem();
        showLogsItem.textProperty().bind(
                javafx.beans.binding.Bindings.when(logPaneVisible)
                        .then("Hide Logs Pane").otherwise("Show Logs Pane"));
        showLogsItem.setOnAction(e -> logPaneVisible.set(!logPaneVisible.get()));
        MenuItem whatsNewItem = new MenuItem("What's New");
        whatsNewItem.setOnAction(e -> WhatsNewDialog.show(windowOf()));
        MenuItem aboutItem = new MenuItem("About ArborJ");
        aboutItem.setOnAction(e -> new AboutDialog(windowOf()).showAndWait());
        settingsBtn.getItems().addAll(newWindowItem, new SeparatorMenuItem(),
                settingsItem, showLogsItem, checkUpdateItem, new SeparatorMenuItem(),
                supportItem, whatsNewItem, aboutItem);

        toolbar.getItems().addAll(spinner, statusLabel, dirTypeLabel, readOnlyBadge,
                spacer, dirServicesBtn, importBtn, settingsBtn, connectBtn);
        return toolbar;
    }

    private boolean isSearchSuggestionPopupShowing() {
        return searchSuggestionPopup != null && searchSuggestionPopup.isShowing();
    }

    private boolean suggestionEscapeJustDismissed() {
        return suggestionEscapeDismissed
                && System.nanoTime() - suggestionEscapeDismissedAtNanos < SUGGESTION_ESCAPE_GUARD_NANOS;
    }

    private boolean searchSuggestionPopupHasFocus() {
        if (!isSearchSuggestionPopupShowing()) return false;
        if (searchSuggestionPopup.isFocused()) return true;
        var scene = searchSuggestionPopup.getScene();
        return scene != null && scene.getFocusOwner() != null;
    }

    /**
     * Hide attribute suggestions and return keyboard focus to the search filter.
     * Does not clear search results.
     */
    private void dismissSearchSuggestions() {
        if (searchSuggestionPopup != null) {
            searchSuggestionPopup.hide();
        }
        suggestionEscapeDismissed = true;
        suggestionEscapeDismissedAtNanos = System.nanoTime();
        if (searchFilterField != null) {
            searchFilterField.requestFocus();
        }
    }

    private void handleSearchSuggestionKeys(javafx.scene.input.KeyEvent ev,
                                            ListView<String> list,
                                            Runnable applySuggestion) {
        if (!isSearchSuggestionPopupShowing()) return;
        switch (ev.getCode()) {
            case DOWN -> {
                int last = list.getItems().size() - 1;
                if (last < 0) return;
                int idx = list.getSelectionModel().getSelectedIndex();
                list.getSelectionModel().select(Math.min(idx + 1, last));
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                ev.consume();
            }
            case UP -> {
                if (list.getItems().isEmpty()) return;
                int idx = list.getSelectionModel().getSelectedIndex();
                list.getSelectionModel().select(Math.max(idx - 1, 0));
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                ev.consume();
            }
            case ENTER, TAB -> {
                if (list.getSelectionModel().getSelectedIndex() >= 0) {
                    applySuggestion.run();
                    ev.consume();
                }
            }
            case ESCAPE -> {
                dismissSearchSuggestions();
                ev.consume();
            }
            default -> {}
        }
    }

    /** Keys still arrive when focus is on the popup scene rather than the list. */
    private void installSearchSuggestionPopupKeys(ListView<String> list, Runnable applySuggestion) {
        var scene = list.getScene();
        if (scene == null) return;
        final String marker = "arborjSearchSuggestionKeys";
        if (Boolean.TRUE.equals(scene.getProperties().get(marker))) return;
        scene.getProperties().put(marker, Boolean.TRUE);
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev ->
                handleSearchSuggestionKeys(ev, list, applySuggestion));
    }

    private VBox buildSearchBar() {
        VBox searchBar = new VBox(4);
        searchBar.getStyleClass().add("search-bar");
        searchBar.visibleProperty().bind(controller.connectedProperty());
        searchBar.managedProperty().bind(searchBar.visibleProperty());

        var historyStore = com.pointbluetech.arborj.service.SearchHistoryStore.getInstance();

        searchFilterField = new TextField();
        TextField filterField = searchFilterField;
        filterField.setPromptText("LDAP filter (e.g. (cn=admin*))");
        filterField.setMinWidth(150);
        filterField.setPrefWidth(300);
        setupSmartFilterInsertion(filterField);

        // Autocomplete popup
        ListView<String> autoComplete = new ListView<>();
        autoComplete.setMaxHeight(200);
        autoComplete.setPrefHeight(150);
        autoComplete.setStyle("-fx-font-family: monospaced; -fx-font-size: 12;");

        // Autocomplete as a floating popup (not in layout). On Windows this
        // heavyweight window can take keyboard focus, so keys are also handled
        // on the popup content.
        javafx.stage.Popup autoPopup = new javafx.stage.Popup();
        searchSuggestionPopup = autoPopup;
        autoPopup.setAutoHide(true);
        autoPopup.setHideOnEscape(true);
        autoPopup.getContent().add(autoComplete);

        Runnable showAutoComplete = () -> {
            if (!autoComplete.getItems().isEmpty() && filterField.getScene() != null) {
                var bounds = filterField.localToScreen(filterField.getBoundsInLocal());
                if (bounds != null) {
                    autoComplete.setPrefWidth(filterField.getWidth());
                    autoPopup.show(filterField, bounds.getMinX(), bounds.getMaxY());
                }
            }
        };
        Runnable hideAutoComplete = () -> autoPopup.hide();

        var suggestSettings = com.pointbluetech.arborj.service.AttributeSuggestSettings.getInstance();
        suggestSettings.enabledProperty().addListener((obs, wasEnabled, enabled) -> {
            if (!enabled) hideAutoComplete.run();
        });

        Runnable updateSuggestions = () -> {
            if (!suggestSettings.isEnabled()) {
                hideAutoComplete.run();
                return;
            }
            int cursor = filterField.getCaretPosition();
            String token = com.pointbluetech.arborj.util.LDAPFilterValidator
                    .attributeTokenAt(cursor, filterField.getText());
            if (token == null || token.length() < 2) {
                hideAutoComplete.run();
                return;
            }
            String lower = token.toLowerCase();
            var attrMap = controller.getSchemaService().getAttributeMap();
            java.util.List<String> matches = attrMap.keySet().stream()
                    .filter(name -> name.startsWith(lower))
                    .sorted()
                    .limit(12)
                    .toList();
            if (matches.isEmpty()) {
                hideAutoComplete.run();
                return;
            }
            autoComplete.getItems().setAll(matches);
            autoComplete.getSelectionModel().clearSelection();
            showAutoComplete.run();
        };

        // Apply a suggestion: replace the token in the filter text
        Runnable applySuggestion = () -> {
            String selected = autoComplete.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            int cursor = filterField.getCaretPosition();
            String token = com.pointbluetech.arborj.util.LDAPFilterValidator
                    .attributeTokenAt(cursor, filterField.getText());
            if (token == null) return;
            String text = filterField.getText();
            int tokenStart = text.toLowerCase().lastIndexOf(token.toLowerCase(), cursor - 1);
            if (tokenStart >= 0) {
                String newText = text.substring(0, tokenStart) + selected + text.substring(tokenStart + token.length());
                filterField.setText(newText);
                filterField.positionCaret(tokenStart + selected.length());
            }
            hideAutoComplete.run();
        };

        filterField.textProperty().addListener((obs, o, n) -> updateSuggestions.run());
        filterField.caretPositionProperty().addListener((obs, o, n) -> updateSuggestions.run());

        // Click to apply suggestion
        autoComplete.setOnMouseClicked(ev -> applySuggestion.run());

        // Keys on the text field, and on the popup when it has taken focus.
        filterField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev ->
                handleSearchSuggestionKeys(ev, autoComplete, applySuggestion));
        autoComplete.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev ->
                handleSearchSuggestionKeys(ev, autoComplete, applySuggestion));
        autoPopup.setOnShown(ev -> installSearchSuggestionPopupKeys(autoComplete, applySuggestion));

        // Hide autocomplete when focus leaves the filter, unless the popup
        // itself has focus (a click on the list, or Windows moving focus there).
        filterField.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) {
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(200));
                pause.setOnFinished(ev -> {
                    if (!filterField.isFocused() && !searchSuggestionPopupHasFocus()) {
                        hideAutoComplete.run();
                    }
                });
                pause.play();
            }
        });

        ComboBox<String> scopeCombo = new ComboBox<>();
        scopeCombo.getItems().addAll("Base", "One Level", "Subtree");
        scopeCombo.setValue("Subtree");

        // Size limit
        ComboBox<String> limitCombo = new ComboBox<>();
        limitCombo.getItems().addAll("1,000", "10,000", "20,000", "50,000", "All");
        limitCombo.setValue("20,000");
        limitCombo.setTooltip(new Tooltip("Maximum number of search results"));

        // Attributes picker button (declared early so history/saved search closures can reference it)
        Button attrsBtn = new Button("Attributes (*)");
        attrsBtn.setTooltip(new Tooltip("Select attributes to return"));

        // Helper to update attrsBtn label from selectedSearchAttributes
        Runnable updateAttrsLabel = () -> {
            if (selectedSearchAttributes.size() == 1 && "*".equals(selectedSearchAttributes.getFirst())) {
                attrsBtn.setText("Attributes (*)");
            } else {
                attrsBtn.setText("Attributes (" + selectedSearchAttributes.size() + ")");
            }
        };

        // Helper to map sizeLimit int to limitCombo display value
        java.util.function.IntFunction<String> limitToLabel = limit -> switch (limit) {
            case 1000 -> "1,000";
            case 10000 -> "10,000";
            case 50000 -> "50,000";
            case 0 -> "All";
            default -> "20,000";
        };

        // Helper to restore a full search configuration into the UI
        // params: filter, scope, attrs, sizeLimit, baseDn (nullable), autoExecute
        var savedSearchStore = com.pointbluetech.arborj.service.SavedSearchStore.getInstance();

        // History dropdown
        MenuButton historyBtn = new MenuButton("\u21BB"); // clockwise arrow (history)
        historyBtn.setTooltip(new Tooltip("Search History & Saved Searches"));

        // Shared action for "Save Current Search..."
        Runnable saveCurrentSearch = () -> {
            String currentFilter = filterField.getText().trim();
            if (currentFilter.isEmpty()) {
                new Alert(Alert.AlertType.INFORMATION, "Enter a search filter first.").showAndWait();
                return;
            }
            TextInputDialog nameDialog = new TextInputDialog();
            nameDialog.setTitle("Save Search");
            nameDialog.setHeaderText(null);
            nameDialog.setContentText("Name:");
            nameDialog.showAndWait().ifPresent(name -> {
                if (!name.trim().isEmpty()) {
                    String baseDN = controller.selectedDNProperty().get();
                    if (baseDN == null) {
                        baseDN = controller.getLdapService().getCurrentConfig().getBaseDN();
                    }
                    com.pointbluetech.arborj.model.SearchScope scope = switch (scopeCombo.getValue()) {
                        case "Base" -> com.pointbluetech.arborj.model.SearchScope.BASE;
                        case "One Level" -> com.pointbluetech.arborj.model.SearchScope.ONE_LEVEL;
                        default -> com.pointbluetech.arborj.model.SearchScope.SUBTREE;
                    };
                    int sizeLimit = switch (limitCombo.getValue()) {
                        case "1,000" -> 1000;
                        case "10,000" -> 10000;
                        case "50,000" -> 50000;
                        case "All" -> 0;
                        default -> 20000;
                    };
                    savedSearchStore.add(new com.pointbluetech.arborj.model.SavedSearch(
                            name.trim(), currentFilter, baseDN, scope,
                            new java.util.ArrayList<>(selectedSearchAttributes), sizeLimit));
                }
            });
        };

        Runnable rebuildHistoryMenu = () -> {
            historyBtn.getItems().clear();

            // ── Saved Searches section ──
            var saved = savedSearchStore.getEntries();
            if (!saved.isEmpty()) {
                MenuItem savedHeader = new MenuItem("Saved Searches");
                savedHeader.setDisable(true);
                savedHeader.setStyle("-fx-font-weight: bold;");
                historyBtn.getItems().add(savedHeader);

                for (var ss : saved) {
                    MenuItem item = new MenuItem("\u2605 " + ss.getName());
                    item.setOnAction(ev -> {
                        filterField.setText(ss.getFilter());
                        if (ss.getScope() != null) {
                            scopeCombo.setValue(ss.getScope().getDisplayName());
                        }
                        if (ss.getSelectedAttributes() != null && !ss.getSelectedAttributes().isEmpty()) {
                            selectedSearchAttributes = ss.getSelectedAttributes();
                        } else {
                            selectedSearchAttributes = java.util.List.of("*");
                        }
                        updateAttrsLabel.run();
                        limitCombo.setValue(limitToLabel.apply(ss.getSizeLimit()));
                        // Auto-execute the search
                        javafx.application.Platform.runLater(() -> {
                            String baseDN = ss.getBaseDn();
                            if (baseDN == null || baseDN.isEmpty()) {
                                baseDN = controller.selectedDNProperty().get();
                                if (baseDN == null) {
                                    baseDN = controller.getLdapService().getCurrentConfig().getBaseDN();
                                }
                                if (baseDN == null) baseDN = "";
                            }
                            com.pointbluetech.arborj.model.SearchScope scope =
                                    ss.getScope() != null ? ss.getScope()
                                            : com.pointbluetech.arborj.model.SearchScope.SUBTREE;
                            String[] attrs = selectedSearchAttributes.toArray(new String[0]);
                            controller.performSearch(baseDN, ss.getFilter(), scope, attrs, ss.getSizeLimit());
                        });
                    });
                    historyBtn.getItems().add(item);
                }
            }

            // Save and Manage items (always shown)
            MenuItem saveItem = new MenuItem("Save Current Search...");
            saveItem.setOnAction(ev -> saveCurrentSearch.run());
            historyBtn.getItems().add(saveItem);

            if (!saved.isEmpty()) {
                MenuItem manageItem = new MenuItem("Manage Saved Searches...");
                manageItem.setOnAction(ev -> new SavedSearchManagerDialog(windowOf()).showAndWait());
                historyBtn.getItems().add(manageItem);
            }

            historyBtn.getItems().add(new SeparatorMenuItem());

            // ── Recent History section ──
            if (historyStore.getEntries().isEmpty()) {
                MenuItem emptyItem = new MenuItem("No recent searches");
                emptyItem.setDisable(true);
                historyBtn.getItems().add(emptyItem);
            } else {
                MenuItem recentHeader = new MenuItem("Recent");
                recentHeader.setDisable(true);
                recentHeader.setStyle("-fx-font-weight: bold;");
                historyBtn.getItems().add(recentHeader);

                for (var entry : historyStore.getEntries()) {
                    String label = entry.getFilter();
                    if (label.length() > 50) label = label.substring(0, 47) + "...";
                    String scopeLabel = entry.getScope() != null ? entry.getScope().getDisplayName() : "";
                    // Show attributes if specific ones were selected
                    String attrsLabel = "";
                    if (entry.getSelectedAttributes() != null
                            && !entry.getSelectedAttributes().isEmpty()
                            && !(entry.getSelectedAttributes().size() == 1
                                 && "*".equals(entry.getSelectedAttributes().getFirst()))) {
                        attrsLabel = "  " + String.join(", ", entry.getSelectedAttributes());
                        if (attrsLabel.length() > 30) attrsLabel = attrsLabel.substring(0, 27) + "...";
                    }
                    MenuItem item = new MenuItem(label + "  [" + scopeLabel + "]" + attrsLabel);
                    item.setOnAction(ev -> {
                        filterField.setText(entry.getFilter());
                        if (entry.getScope() != null) {
                            scopeCombo.setValue(entry.getScope().getDisplayName());
                        }
                        // Restore attributes
                        if (entry.getSelectedAttributes() != null && !entry.getSelectedAttributes().isEmpty()) {
                            selectedSearchAttributes = entry.getSelectedAttributes();
                        } else {
                            selectedSearchAttributes = java.util.List.of("*");
                        }
                        updateAttrsLabel.run();
                        // Restore size limit
                        if (entry.getSizeLimit() > 0) {
                            limitCombo.setValue(limitToLabel.apply(entry.getSizeLimit()));
                        }
                    });
                    historyBtn.getItems().add(item);
                }
                historyBtn.getItems().add(new SeparatorMenuItem());
                MenuItem clearHistory = new MenuItem("Clear History");
                clearHistory.setOnAction(ev -> historyStore.clear());
                historyBtn.getItems().add(clearHistory);
            }
        };
        historyStore.getEntries().addListener(
                (javafx.collections.ListChangeListener<? super com.pointbluetech.arborj.model.SearchHistoryEntry>)
                        c -> rebuildHistoryMenu.run());
        savedSearchStore.getEntries().addListener(
                (javafx.collections.ListChangeListener<? super com.pointbluetech.arborj.model.SavedSearch>)
                        c -> rebuildHistoryMenu.run());
        rebuildHistoryMenu.run();

        Button searchBtn = new Button("Search");
        searchBtn.setOnAction(e -> {
            String filter = filterField.getText().trim();
            if (filter.isEmpty()) return;

            // Auto-wrap in parens if missing
            if (!filter.startsWith("(")) {
                filter = "(" + filter + ")";
            }

            // Use selected node's DN, or config base DN, or root ("")
            // Note: empty string is a valid base DN (root DSE / tree root)
            String baseDN = controller.selectedDNProperty().get();
            if (baseDN == null) {
                baseDN = controller.getLdapService().getCurrentConfig().getBaseDN();
            }
            if (baseDN == null) {
                baseDN = "";
            }

            com.pointbluetech.arborj.model.SearchScope scope = switch (scopeCombo.getValue()) {
                case "Base" -> com.pointbluetech.arborj.model.SearchScope.BASE;
                case "One Level" -> com.pointbluetech.arborj.model.SearchScope.ONE_LEVEL;
                default -> com.pointbluetech.arborj.model.SearchScope.SUBTREE;
            };

            int sizeLimit = switch (limitCombo.getValue()) {
                case "1,000" -> 1000;
                case "10,000" -> 10000;
                case "50,000" -> 50000;
                case "All" -> 0;
                default -> 20000;
            };

            // Save to history (with actual sizeLimit)
            historyStore.add(new com.pointbluetech.arborj.model.SearchHistoryEntry(
                    filter, scope, selectedSearchAttributes, false, sizeLimit));

            String[] attrs = selectedSearchAttributes.toArray(new String[0]);
            System.out.println("[ArborJ] Search: base=" + baseDN + " filter=" + filter + " scope=" + scope
                    + " limit=" + sizeLimit + " attrs=" + selectedSearchAttributes);
            controller.performSearch(baseDN, filter, scope, attrs, sizeLimit);
        });

        // Submit on Enter in filter field
        filterField.setOnAction(e -> searchBtn.fire());

        Button clearBtn = new Button("Clear");
        clearBtn.setOnAction(e -> {
            filterField.clear();
            controller.clearSearch();
            contentSplit.getItems().remove(searchResultsList);
        });

        // Attributes picker action (button declared earlier for history closure access)
        attrsBtn.setOnAction(e -> {
            AttributePickerDialog picker = new AttributePickerDialog(controller, selectedSearchAttributes, windowOf());
            picker.showAndWait();
            java.util.List<String> chosen = picker.getSelectedAttributes();
            if (chosen != null) {
                if (chosen.isEmpty()) {
                    // Default to all if nothing selected
                    selectedSearchAttributes = java.util.List.of("*");
                } else {
                    selectedSearchAttributes = chosen;
                }
                // Update button label
                updateAttrsLabel.run();
                // Update table view link visibility
                if (tableViewLink != null) {
                    boolean hasResults = !controller.getSearchResults().isEmpty();
                    tableViewLink.setVisible(hasResults);
                    tableViewLink.setManaged(hasResults);
                }
            }
        });

        // Filter validation — change border color and disable search button
        Tooltip validationTooltip = new Tooltip();
        filterField.setTooltip(validationTooltip);

        javafx.animation.PauseTransition validationDelay = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(150));
        validationDelay.setOnFinished(ev -> {
            String text = filterField.getText();
            if (text == null || text.isBlank()) {
                filterField.setStyle("");
                validationTooltip.setText("");
                searchBtn.setDisable(false);
                return;
            }
            String toValidate = text.trim();
            if (!toValidate.startsWith("(")) toValidate = "(" + toValidate + ")";
            String error = com.pointbluetech.arborj.util.LDAPFilterValidator.validate(toValidate);
            if (error == null) {
                filterField.setStyle("-fx-border-color: -color-success-fg;");
                validationTooltip.setText("");
                searchBtn.setDisable(false);
            } else {
                filterField.setStyle("-fx-border-color: -color-danger-fg;");
                validationTooltip.setText(error);
                searchBtn.setDisable(true);
            }
        });
        filterField.textProperty().addListener((obs2, oldVal, newVal) -> validationDelay.playFromStart());

        // Row 1: Filter label + filter field
        // Autocomplete overlays as a popup, not in the layout
        Label filterLabel = new Label("Filter:");
        filterLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        HBox.setHgrow(filterField, Priority.ALWAYS);
        filterField.setMaxWidth(Double.MAX_VALUE);

        // Smart insert toggle
        ToggleButton smartInsertBtn = new ToggleButton("( )");
        smartInsertBtn.setSelected(smartInsertEnabled);
        smartInsertBtn.setTooltip(new Tooltip("Auto-insert matching parentheses and operators"));
        smartInsertBtn.setStyle("-fx-font-size: 13; -fx-padding: 6 10 6 10;");
        smartInsertBtn.selectedProperty().addListener((obs, oldVal, selected) -> {
            smartInsertEnabled = selected;
            java.util.prefs.Preferences.userNodeForPackage(MainView.class)
                    .putBoolean("smartInsert", selected);
            smartInsertBtn.setStyle(selected
                    ? "-fx-font-size: 13; -fx-padding: 6 10 6 10;"
                    : "-fx-font-size: 13; -fx-padding: 6 10 6 10; -fx-opacity: 0.5;");
        });
        if (!smartInsertEnabled) {
            smartInsertBtn.setStyle("-fx-font-size: 13; -fx-padding: 6 10 6 10; -fx-opacity: 0.5;");
        }

        Button filterBuilderBtn = new Button("\uD83D\uDD27"); // wrench
        filterBuilderBtn.setStyle("-fx-font-size: 13; -fx-padding: 6 10 6 10;");
        filterBuilderBtn.setTooltip(new Tooltip("Visual Filter Builder"));
        filterBuilderBtn.setOnAction(e -> {
            String current = filterField.getText() != null ? filterField.getText().trim() : "";
            FilterBuilderDialog builder = new FilterBuilderDialog(controller, current, windowOf());
            builder.showAndWait();
            String result = builder.getFilter();
            if (result != null) {
                filterField.setText(result);
            }
        });

        HBox filterRow = new HBox(6, filterLabel, filterField, smartInsertBtn, filterBuilderBtn);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        // Row 2: Scope, limit, attrs, history, search, clear
        Label limitLabel = new Label("Limit:");
        limitLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");
        HBox controlsRow = new HBox(8, scopeCombo, limitLabel, limitCombo, attrsBtn, historyBtn, searchBtn, clearBtn);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        searchBar.getChildren().addAll(filterRow, controlsRow);
        return searchBar;
    }

    /**
     * Sets up smart character insertion on the given filter TextField.
     * When the user types certain characters, surrounding LDAP filter syntax is auto-inserted
     * and the cursor is placed at the most useful position.
     */
    private void setupSmartFilterInsertion(TextField filterField) {
        filterField.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, event -> {
            if (!smartInsertEnabled) return;

            String ch = event.getCharacter();
            if (ch == null || ch.length() != 1) return;
            char c = ch.charAt(0);

            if (c != '(' && c != '&' && c != '|' && c != '!') return;

            String text = filterField.getText();
            int caret = filterField.getCaretPosition();

            // Check if inside a value context: cursor is after '=' and before ')' with no intervening '('
            if (isInsideValueContext(text, caret) && (c == '&' || c == '|' || c == '!')) {
                return; // Let normal typing happen
            }

            event.consume();

            String before = text.substring(0, caret);
            String after = text.substring(caret);

            switch (c) {
                case '(' -> {
                    // Insert "()" and place cursor between them
                    String newText = before + "()" + after;
                    filterField.setText(newText);
                    filterField.positionCaret(caret + 1);
                }
                case '&', '|' -> {
                    handleBooleanOperator(filterField, c, before, after, caret);
                }
                case '!' -> {
                    handleNotOperator(filterField, before, after, caret);
                }
            }
        });
    }

    private boolean isInsideValueContext(String text, int caret) {
        // Scan backwards from caret to find if we are between '=' and ')'
        // We are "inside a value" if we see '=' before seeing '(' or ')' going backwards
        for (int i = caret - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == '=') return true;
            if (ch == '(' || ch == ')') return false;
        }
        return false;
    }

    private void handleBooleanOperator(TextField filterField, char op, String before, String after, int caret) {
        boolean precededByOpen = !before.isEmpty() && before.charAt(before.length() - 1) == '(';
        boolean followedByClose = !after.isEmpty() && after.charAt(0) == ')';

        if (precededByOpen && followedByClose) {
            // "(" + op + ")" -> replace with "(&()())" -- cursor after first inner "("
            // The '(' before caret and ')' after caret are already in text; we replace the region
            String newText = before + op + "()()" + after;
            filterField.setText(newText);
            filterField.positionCaret(caret + 2); // after the first inner "("
        } else if (precededByOpen) {
            // "(" already typed, insert op + "()())" -- cursor after first "("
            String newText = before + op + "()()" + ")" + after;
            filterField.setText(newText);
            filterField.positionCaret(caret + 2); // after the first "("
        } else {
            // No surrounding parens: insert "(&()())" -- cursor after first inner "("
            String newText = before + "(" + op + "()()" + ")" + after;
            filterField.setText(newText);
            filterField.positionCaret(caret + 3); // after first inner "("
        }
    }

    private void handleNotOperator(TextField filterField, String before, String after, int caret) {
        boolean precededByOpen = !before.isEmpty() && before.charAt(before.length() - 1) == '(';
        boolean followedByClose = !after.isEmpty() && after.charAt(0) == ')';

        if (precededByOpen && followedByClose) {
            // "(!)" -> replace with "(!())" -- cursor after inner "("
            String newText = before + "!" + "()" + after;
            filterField.setText(newText);
            filterField.positionCaret(caret + 2); // after inner "("
        } else if (precededByOpen) {
            // "(" already typed, insert "!())" -- cursor after "("
            String newText = before + "!" + "()" + ")" + after;
            filterField.setText(newText);
            filterField.positionCaret(caret + 2); // after "("
        } else {
            // No surrounding parens: insert "(!(()))" -- cursor after inner "("
            String newText = before + "(!(" + "()" + "))" + after;
            filterField.setText(newText);
            filterField.positionCaret(caret + 4); // after innermost "("
        }
    }

    private VBox buildSearchResultsList() {
        VBox resultsPane = new VBox(4);
        resultsPane.setMinWidth(200);
        resultsPane.setPrefWidth(340);
        resultsPane.setStyle("-fx-background-color: -color-bg-default;");

        // Header: "N result(s)" + "Export All as LDIF" link
        Label header = new Label();
        header.setFont(Font.font("System", FontWeight.BOLD, 13));

        Label exportLink = new Label("Export All as LDIF...");
        exportLink.setStyle("-fx-text-fill: -color-accent-fg; -fx-cursor: hand;");
        exportLink.setOnMouseClicked(e -> {
            var entries = controller.getSearchResults();
            if (!entries.isEmpty()) {
                String ldif = new com.pointbluetech.arborj.service.LDIFExporter().exportEntries(entries);
                saveLDIF(ldif, "search_results", resultsPane);
            }
        });

        tableViewLink = new Label("Table View");
        tableViewLink.setStyle("-fx-text-fill: -color-accent-fg; -fx-cursor: hand;");
        tableViewLink.setOnMouseClicked(e -> {
            if (controller.getSearchResults().isEmpty()) return;
            if (!hasSpecificAttributes()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION,
                        "Table View is only available when specific attributes have been selected.\n\n"
                        + "Use the Attributes button to choose which attributes to return, "
                        + "then run your search again.");
                alert.setTitle("Table View");
                alert.setHeaderText("Select Attributes First");
                alert.showAndWait();
            } else {
                new SearchResultsTableView(controller, selectedSearchAttributes).show();
            }
        });
        tableViewLink.setVisible(false);
        tableViewLink.setManaged(false);

        ProgressIndicator searchSpinner = new ProgressIndicator();
        searchSpinner.setPrefSize(18, 18);
        searchSpinner.visibleProperty().bind(controller.searchingProperty());
        searchSpinner.managedProperty().bind(searchSpinner.visibleProperty());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox headerRow = new HBox(8, header, searchSpinner, spacer, tableViewLink, exportLink);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(6, 8, 6, 8));
        headerRow.setStyle("-fx-background-color: -color-bg-subtle; "
                + "-fx-border-color: -color-border-default; -fx-border-width: 0 0 1 0;");

        Label searchWarning = new Label();
        searchWarning.setStyle("-fx-text-fill: -color-warning-fg; -fx-font-size: 11;");
        searchWarning.setPadding(new Insets(4, 8, 4, 8));
        searchWarning.setWrapText(true);
        searchWarning.textProperty().bind(controller.searchErrorProperty());
        searchWarning.visibleProperty().bind(
                controller.searchErrorProperty().isNotNull()
                        .and(controller.searchErrorProperty().isNotEqualTo("")));
        searchWarning.managedProperty().bind(searchWarning.visibleProperty());

        // No results message
        Label noResultsLabel = new Label("No results found");
        noResultsLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-style: italic;");
        noResultsLabel.setPadding(new Insets(40));
        noResultsLabel.setMaxWidth(Double.MAX_VALUE);
        noResultsLabel.setAlignment(Pos.CENTER);

        // Results list: RDN bold + full DN muted below
        ListView<com.pointbluetech.arborj.model.LDAPEntry> resultsList =
                new ListView<>(controller.getSearchResults());
        resultsList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.pointbluetech.arborj.model.LDAPEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }
                String dn = item.getDn();
                String rdn = dn.contains(",") ? dn.substring(0, dn.indexOf(',')) : dn;

                Label rdnLabel = new Label(rdn);
                rdnLabel.setFont(com.pointbluetech.arborj.service.FontSettings.getInstance().getDetailBoldFont());

                Label dnLabel = new Label(dn);
                dnLabel.setFont(Font.font(
                        com.pointbluetech.arborj.service.FontSettings.getInstance().fontFamilyProperty().get(),
                        com.pointbluetech.arborj.service.FontSettings.getInstance().fontSizeProperty().get() - 2));
                dnLabel.setStyle("-fx-text-fill: -color-fg-muted;");

                VBox cell = new VBox(1, rdnLabel, dnLabel);
                setGraphic(cell);
                setText(null);
                setContextMenu(EntryContextMenu.build(
                        controller, dn, item.getValues("objectClass"), this));
            }
        });
        VBox.setVgrow(resultsList, Priority.ALWAYS);

        // Update header and list/no-results visibility
        controller.getSearchResults().addListener(
                (javafx.collections.ListChangeListener<? super com.pointbluetech.arborj.model.LDAPEntry>) c -> {
                    int count = controller.getSearchResults().size();
                    header.setText(count + " result" + (count != 1 ? "s" : ""));
                    boolean empty = count == 0 && !controller.searchingProperty().get();
                    noResultsLabel.setVisible(empty);
                    noResultsLabel.setManaged(empty);
                    resultsList.setVisible(!empty);
                    resultsList.setManaged(!empty);
                    exportLink.setVisible(count > 0);
                    exportLink.setManaged(count > 0);
                    tableViewLink.setVisible(count > 0);
                    tableViewLink.setManaged(count > 0);
                });

        controller.searchingProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                header.setText("Searching...");
                noResultsLabel.setVisible(false);
                noResultsLabel.setManaged(false);
            } else {
                // Search finished — update based on results
                int count = controller.getSearchResults().size();
                header.setText(count + " result" + (count != 1 ? "s" : ""));
                boolean empty = count == 0;
                noResultsLabel.setVisible(empty);
                noResultsLabel.setManaged(empty);
                resultsList.setVisible(!empty);
                resultsList.setManaged(!empty);
                exportLink.setVisible(count > 0);
                exportLink.setManaged(count > 0);
                tableViewLink.setVisible(count > 0);
                tableViewLink.setManaged(count > 0);
            }
        });

        noResultsLabel.setVisible(false);
        noResultsLabel.setManaged(false);
        exportLink.setVisible(false);
        exportLink.setManaged(false);

        // Select result -> load its attributes (respecting selected search attributes)
        resultsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String[] attrs = selectedSearchAttributes.toArray(new String[0]);
                controller.loadAttributes(newVal.getDn(), attrs);
            }
        });

        resultsPane.getChildren().addAll(headerRow, searchWarning, noResultsLabel, resultsList);
        return resultsPane;
    }

    private boolean hasSpecificAttributes() {
        return !(selectedSearchAttributes.size() == 1 && "*".equals(selectedSearchAttributes.getFirst()));
    }

    private void saveLDIF(String ldif, String suggestedName, javafx.scene.Node owner) {
        if (ldif == null || ldif.isEmpty()) return;
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Save LDIF");
        fc.setInitialFileName(suggestedName.replaceAll("[^a-zA-Z0-9._-]", "_") + ".ldif");
        fc.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("LDIF Files", "*.ldif"));
        java.io.File file = fc.showSaveDialog(owner.getScene().getWindow());
        if (file != null) {
            try {
                java.nio.file.Files.writeString(file.toPath(), ldif);
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to save: " + ex.getMessage()).showAndWait();
            }
        }
    }

    // --- Dialogs ---

    private HBox buildUpdateBanner() {
        HBox banner = new HBox(8);
        banner.setPadding(new javafx.geometry.Insets(6, 12, 6, 12));
        banner.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        banner.setStyle("-fx-background-color: -color-accent-subtle;");
        banner.setVisible(false);
        banner.setManaged(false);

        Label icon = new Label("\u2B06"); // up arrow
        Label message = new Label();
        message.setStyle("-fx-font-size: 12;");

        Hyperlink downloadLink = new Hyperlink("Download");
        downloadLink.setOnAction(e -> {
            String url = updateChecker.downloadUrlProperty().get();
            if (url != null && !url.isEmpty()) {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                } catch (Exception ignored) {}
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button dismissBtn = new Button("\u2715"); // X
        dismissBtn.setStyle("-fx-font-size: 10; -fx-padding: 2 6 2 6;");
        dismissBtn.setOnAction(e -> updateChecker.dismiss());

        banner.getChildren().addAll(icon, message, downloadLink, spacer, dismissBtn);

        // Show/hide based on update state
        updateChecker.updateAvailableProperty().addListener((obs, o, available) -> {
            if (available && !updateChecker.dismissedProperty().get()) {
                message.setText("ArborJ " + updateChecker.latestVersionProperty().get() + " is available.");
                banner.setVisible(true);
                banner.setManaged(true);
            }
        });
        updateChecker.dismissedProperty().addListener((obs, o, dismissed) -> {
            if (dismissed) {
                banner.setVisible(false);
                banner.setManaged(false);
            }
        });

        // Trigger the check
        updateChecker.checkAsync(com.pointbluetech.arborj.ArborJApp.APP_VERSION);

        return banner;
    }

    private void showConnectionDialog() {
        ConnectionDialog dialog = new ConnectionDialog(controller, windowOf());
        dialog.showAndWait();
    }

    private void showSettingsDialog() {
        new SettingsDialog(controller.getCredentialStore(), windowOf()).showAndWait();
    }

    private javafx.stage.Window windowOf() {
        return root.getScene() != null ? root.getScene().getWindow() : null;
    }

    /**
     * F5 / Cmd+R handler: refresh the currently-selected tree node, or the
     * whole tree if no node is selected. Refreshing a leaf node is a no-op
     * (handled in MainController.refreshNode), so this is safe to fire
     * unconditionally.
     */
    private void refreshSelectedNodeOrTree() {
        if (!controller.connectedProperty().get()) return;
        var sel = treeView.getTreeView().getSelectionModel().getSelectedItem();
        if (sel != null && sel.getValue() != null) {
            controller.refreshNode(sel.getValue());
        } else {
            controller.refreshTree();
        }
    }
}
