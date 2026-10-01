package com.pointbluetech.arborj.controller;

import com.pointbluetech.arborj.model.*;
import com.pointbluetech.arborj.service.*;
import com.pointbluetech.arborj.util.DirectoryTopology;
import com.pointbluetech.arborj.util.DirectoryTopology.ChildCount;
import com.pointbluetech.arborj.view.ErrorAlert;
import com.unboundid.ldap.sdk.DN;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Primary controller binding the service layer to the UI.
 * All UI-visible state is exposed as JavaFX properties.
 */
public class MainController {

    // Services
    private final LDAPService ldapService = new LDAPService();
    private final SchemaService schemaService = new SchemaService(ldapService);
    private final ProfileStore profileStore = new ProfileStore();
    private final CredentialStore credentialStore = new CredentialStore();
    private final LicenseService licenseService = new LicenseService();

    // Background executor for LDAP operations
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Connection state
    private final BooleanProperty connected = new SimpleBooleanProperty(false);
    private final BooleanProperty connecting = new SimpleBooleanProperty(false);
    private final BooleanProperty reconnecting = new SimpleBooleanProperty(false);
    private final BooleanProperty readOnly = new SimpleBooleanProperty(false);
    private final StringProperty connectionError = new SimpleStringProperty();
    private final StringProperty connectedProfileName = new SimpleStringProperty();
    private final ObjectProperty<DirectoryType> directoryType =
            new SimpleObjectProperty<>(DirectoryType.AUTO);

    // Tree state
    private final ObservableList<LDAPNode> rootNodes = FXCollections.observableArrayList();
    private final ObjectProperty<LDAPNode> selectedNode = new SimpleObjectProperty<>();
    private final StringProperty treeError = new SimpleStringProperty();

    // Attribute state
    private final ObservableList<AttributeRow> selectedAttributes = FXCollections.observableArrayList();
    private final StringProperty selectedDN = new SimpleStringProperty();
    private final BooleanProperty loadingAttributes = new SimpleBooleanProperty(false);
    private final BooleanProperty showOperationalAttributes = new SimpleBooleanProperty(true);

    // Cached attributes for the currently selected entry (avoids re-fetch on toggle)
    private Map<String, List<String>> cachedAttributes;
    private String cachedAttributesDN;

    // Search state
    private final ObservableList<LDAPEntry> searchResults = FXCollections.observableArrayList();
    private final BooleanProperty searching = new SimpleBooleanProperty(false);
    private final StringProperty searchError = new SimpleStringProperty();

    // Certificate trust
    private final ObjectProperty<CertificateDetails> pendingCertDetails = new SimpleObjectProperty<>();

    // Last connection attempt (for cert trust retry)
    private ConnectionProfile lastAttemptedProfile;
    private String lastAttemptedPassword;

    // Multi-value collapse: tracks which attributes show all values
    private static final int MAX_VALUES_COLLAPSED = 3;
    private final Set<String> expandedMultiValueAttrs = new HashSet<>();

    // eDirectory feature flags
    private final BooleanProperty dirXMLSupported = new SimpleBooleanProperty(false);
    private final BooleanProperty effectiveRightsSupported = new SimpleBooleanProperty(false);
    private final BooleanProperty passwordModifySupported = new SimpleBooleanProperty(false);

    public MainController() {
        // Auto-reconnect when heartbeat detects connection loss
        ldapService.setConnectionLostHandler(() -> {
            if (lastAttemptedProfile == null) return;
            System.out.println("[ArborJ] Connection lost, attempting auto-reconnect...");
            Platform.runLater(() -> reconnecting.set(true));

            try {
                byte[] approvedCert = credentialStore.loadApprovedCert(
                        lastAttemptedProfile.getConnection().getHost(),
                        lastAttemptedProfile.getConnection().getPort());
                ldapService.connect(lastAttemptedProfile.getConnection(),
                        lastAttemptedPassword, approvedCert);

                // Re-detect type, reload schema, and refresh root nodes so the UI
                // doesn't keep showing stale state from before the disconnect.
                LDAPConnectionConfig cfg = lastAttemptedProfile.getConnection();
                DirectoryType type = cfg.getDirectoryType() == DirectoryType.AUTO
                        ? schemaService.detectDirectoryType()
                        : cfg.getDirectoryType();
                schemaService.setDetectedType(type);
                schemaService.loadSchema();
                ldapService.setBinaryAttributeNames(schemaService.getBinaryAttributeNames());

                System.out.println("[ArborJ] Auto-reconnect successful");
                Platform.runLater(() -> {
                    reconnecting.set(false);
                    connected.set(true);
                    directoryType.set(type);
                });
                loadRootNodes();
            } catch (Exception e) {
                System.err.println("[ArborJ] Auto-reconnect failed: " + e.getMessage());
                Platform.runLater(() -> {
                    reconnecting.set(false);
                    connected.set(false);
                    connectionError.set("Connection lost: " + simplifyErrorMessage(e.getMessage()));
                });
            }
        });
    }

    // --- Connection ---

    public void connect(ConnectionProfile profile, String password) {
        connecting.set(true);
        connectionError.set(null);
        lastAttemptedProfile = profile;
        lastAttemptedPassword = password;

        executor.submit(() -> {
            try {
                LDAPConnectionConfig config = profile.getConnection();
                System.out.println("[ArborJ] Connecting to " + config.getHost() + ":" + config.getPort()
                        + " TLS=" + config.isUseTLS() + " bindDN=" + config.getBindDN());

                // Load approved cert if available
                byte[] approvedCert = credentialStore.loadApprovedCert(
                        config.getHost(), config.getPort());

                ldapService.connect(config, password, approvedCert);
                System.out.println("[ArborJ] Connected successfully");

                // Detect directory type and load schema
                DirectoryType type = config.getDirectoryType() == DirectoryType.AUTO
                        ? schemaService.detectDirectoryType()
                        : config.getDirectoryType();
                schemaService.setDetectedType(type);
                schemaService.loadSchema();

                // Tell LDAPService which attributes are binary per schema
                ldapService.setBinaryAttributeNames(schemaService.getBinaryAttributeNames());

                // Check supported extensions and controls
                List<String> extensions = ldapService.fetchSupportedExtensions();

                // Check if paged results is supported
                Map<String, List<String>> rootDSE = ldapService.fetchRootDSE();
                List<String> controls = rootDSE.getOrDefault("supportedControl", List.of());
                boolean pagedSupported = controls.contains("1.2.840.113556.1.4.319");

                Platform.runLater(() -> {
                    connected.set(true);
                    connecting.set(false);
                    readOnly.set(config.isReadOnly());
                    directoryType.set(type);
                    connectedProfileName.set(profile.getName());

                    dirXMLSupported.set(extensions.contains(LDAPService.DIRXML_COMMAND_OID));
                    effectiveRightsSupported.set(extensions.contains(
                            LDAPService.GET_EFFECTIVE_PRIVILEGES_REQUEST_OID));
                    passwordModifySupported.set(extensions.contains(LDAPService.PASSWORD_MODIFY_OID));
                });

                // Load tree
                loadRootNodes();

                // Reconcile saved-password state. Always run a corresponding
                // delete when "Save Password" is unchecked so the credential
                // store doesn't keep a stale secret around after the user
                // turns the option off.
                //
                // Not covered: if the user toggles "Save Password" off and
                // saves the profile without ever clicking Connect again,
                // the stored credential lingers until the next successful
                // connect. ConnectionDialog.loadProfile already refuses to
                // surface that stale value in the UI, so the leak is
                // invisible-and-eventually-cleaned rather than visible-and-
                // permanent. Add a savePasswordCheckbox listener with a
                // loading-guard if we ever want eager cleanup on uncheck.
                if (profile.isSavePassword()) {
                    credentialStore.savePassword(profile.getId(), password);
                } else {
                    credentialStore.deletePassword(profile.getId());
                }

            } catch (Exception e) {
                // Check if the root cause is an untrusted certificate
                Throwable cause = e;
                LDAPService.UntrustedCertificateException certEx = null;
                while (cause != null) {
                    if (cause instanceof LDAPService.UntrustedCertificateException uce) {
                        certEx = uce;
                        break;
                    }
                    cause = cause.getCause();
                }

                if (certEx != null) {
                    System.out.println("[ArborJ] Untrusted certificate — prompting user");
                    final var certDetails = certEx.getDetails();
                    Platform.runLater(() -> {
                        connecting.set(false);
                        pendingCertDetails.set(certDetails);
                    });
                } else {
                    System.err.println("[ArborJ] Connection failed: " + e.getMessage());
                    e.printStackTrace();
                    String friendlyMsg = simplifyErrorMessage(e.getMessage());
                    Platform.runLater(() -> {
                        connecting.set(false);
                        connectionError.set(friendlyMsg);
                    });
                }
            }
        });
    }

    public void disconnect() {
        ldapService.disconnect();
        connected.set(false);
        connectedProfileName.set(null);
        rootNodes.clear();
        selectedAttributes.clear();
        selectedDN.set(null);
        searchResults.clear();
        directoryType.set(DirectoryType.AUTO);
        dirXMLSupported.set(false);
        effectiveRightsSupported.set(false);
        passwordModifySupported.set(false);
    }

    /**
     * Called when user approves an untrusted certificate.
     * Saves the cert and retries the connection.
     */
    public void trustCertificateAndReconnect() {
        CertificateDetails details = pendingCertDetails.get();
        if (details == null || lastAttemptedProfile == null) return;

        try {
            byte[] certDer = details.getChain().getFirst().getEncoded();
            credentialStore.saveApprovedCert(
                    lastAttemptedProfile.getConnection().getHost(),
                    lastAttemptedProfile.getConnection().getPort(),
                    certDer);
            System.out.println("[ArborJ] Certificate approved, reconnecting...");
        } catch (Exception ex) {
            System.err.println("[ArborJ] Failed to save cert: " + ex.getMessage());
        }

        pendingCertDetails.set(null);
        connect(lastAttemptedProfile, lastAttemptedPassword);
    }

    public void cancelCertTrust() {
        pendingCertDetails.set(null);
    }

    // --- Tree Navigation ---

    private void loadRootNodes() {
        executor.submit(() -> {
            try {
                LDAPConnectionConfig config = ldapService.getCurrentConfig();
                String baseDN = config != null ? config.getBaseDN() : "";

                if (baseDN != null && !baseDN.isEmpty()) {
                    // Specific base DN — show it as the single root
                    String rdn = extractRDN(baseDN);
                    LDAPNode node = new LDAPNode(baseDN, rdn, List.of(), true);
                    Platform.runLater(() -> rootNodes.setAll(node));
                    return;
                }

                // No base DN — show Root DSE as top-level node with tree name
                String treeName = null;
                try {
                    treeName = ldapService.fetchTreeName();
                } catch (Exception ignored) {}

                String rootLabel = treeName != null ? treeName : "[Root DSE]";
                LDAPNode rootNode = new LDAPNode("", rootLabel, List.of("treeRoot"), true);

                // Pre-load root's children: one-level search of the root DSE,
                // topped up with any naming context that search cannot reach.
                Map<String, List<String>> dnToOCs = new LinkedHashMap<>();
                Map<String, ChildCount> dnToCount = new LinkedHashMap<>();
                String[] rootAttrs = DirectoryTopology.TOPOLOGY_ATTRS;

                Set<String> topLevel = new java.util.LinkedHashSet<>();
                try {
                    List<LDAPEntry> rootChildren = ldapService.search("",
                            SearchScope.ONE_LEVEL, "(objectClass=*)", rootAttrs);
                    for (LDAPEntry entry : rootChildren) {
                        if (!entry.getDn().isEmpty()) {
                            topLevel.add(entry.getDn());
                            dnToOCs.put(entry.getDn(), entry.getValues("objectClass"));
                            dnToCount.put(entry.getDn(), DirectoryTopology.readChildCount(entry));
                        }
                    }
                } catch (Exception e) {
                }

                Set<String> seen = DirectoryTopology.selectRootDns(
                        ldapService.fetchNamingContexts(), topLevel);

                // A naming context the root-DSE search did not cover (Active
                // Directory's Configuration and Schema contexts, for one) has no
                // objectClass yet, and without one it would be drawn as a leaf.
                for (String dn : seen) {
                    if (dnToOCs.containsKey(dn)) continue;
                    try {
                        List<LDAPEntry> found = ldapService.search(dn, SearchScope.BASE,
                                "(objectClass=*)", rootAttrs);
                        if (!found.isEmpty()) {
                            LDAPEntry entry = found.getFirst();
                            dnToOCs.put(dn, entry.getValues("objectClass"));
                            dnToCount.put(dn, DirectoryTopology.readChildCount(entry));
                        }
                    } catch (Exception ignored) {
                    }
                }

                List<LDAPNode> children = new ArrayList<>();
                for (String dn : seen) {
                    String rdn = extractRDN(dn);
                    List<String> ocs = dnToOCs.getOrDefault(dn, List.of());
                    LDAPNode child = new LDAPNode(dn, rdn, ocs, true);
                    ChildCount count = dnToCount.getOrDefault(dn, ChildCount.UNKNOWN);
                    child.setLeaf(DirectoryTopology.isLeafNamingContext(
                            count, ocs, schemaService.isContainer(ocs)));
                    if (!child.isLeaf() && count.isKnownPopulated()) {
                        child.setTotalChildCount(count.value());
                    }
                    children.add(child);
                }

                Platform.runLater(() -> {
                    rootNode.getChildren().setAll(children);
                    rootNode.setChildrenLoaded(true);
                    rootNodes.setAll(rootNode);
                });

            } catch (Exception e) {
                Platform.runLater(() -> treeError.set("Failed to load tree: " + e.getMessage()));
            }
        });
    }

    public void loadChildren(LDAPNode node) {
        if (node.isChildrenLoaded() || node.isLoading()) return;
        node.setLoading(true);

        executor.submit(() -> {
            try {
                LDAPConnectionConfig config = ldapService.getCurrentConfig();
                boolean usePaging = config != null && config.isUsePagedResults();

                List<LDAPEntry> entries;
                byte[] nextCookie = null;

                // Request objectClass + subordinate count in one search
                String[] childAttrs = DirectoryTopology.TOPOLOGY_ATTRS;

                if (usePaging) {
                    var result = ldapService.searchPaged(node.getDn(),
                            SearchScope.ONE_LEVEL,
                            "(objectClass=*)",
                            childAttrs,
                            1000, null, 0);
                    entries = result.entries();
                    nextCookie = result.cookie();
                } else {
                    entries = ldapService.search(node.getDn(), SearchScope.ONE_LEVEL,
                            "(objectClass=*)", childAttrs);
                }

                List<LDAPNode> children = new ArrayList<>();
                for (LDAPEntry entry : entries) {
                    children.add(buildChildNode(entry));
                }

                // Sort: containers first, then alphabetically
                children.sort((a, b) -> {
                    if (a.isLeaf() != b.isLeaf()) return a.isLeaf() ? 1 : -1;
                    return a.getRdn().compareToIgnoreCase(b.getRdn());
                });

                byte[] finalCookie = nextCookie;
                Platform.runLater(() -> {
                    node.getChildren().setAll(children);
                    node.setChildrenLoaded(true);
                    node.setLoading(false);
                    node.setHasMore(finalCookie != null);
                    node.setPagingCookie(finalCookie);
                    // The server has now spoken: an expandable container that
                    // turned out to be empty becomes a leaf, so the expansion
                    // arrow disappears instead of hanging on an empty branch.
                    if (children.isEmpty() && finalCookie == null) {
                        node.setLeaf(true);
                        node.setTotalChildCount(-1);
                    } else if (!children.isEmpty()) {
                        node.setLeaf(false);
                    }
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    node.setLoading(false);
                    treeError.set("Failed to load children of " + node.getRdn() + ": " + e.getMessage());
                });
            }
        });
    }

    public void loadMoreChildren(LDAPNode node) {
        if (node.getPagingCookie() == null || node.isLoadingMore()) return;
        node.setLoadingMore(true);

        executor.submit(() -> {
            try {
                String[] childAttrs = {"objectClass", "subordinateCount",
                        "numSubordinates", "msDS-Approx-Immed-Subordinates"};
                var result = ldapService.searchPaged(node.getDn(),
                        SearchScope.ONE_LEVEL,
                        "(objectClass=*)",
                        childAttrs,
                        1000, node.getPagingCookie(), 0);

                List<LDAPNode> newChildren = new ArrayList<>();
                for (LDAPEntry entry : result.entries()) {
                    newChildren.add(buildChildNode(entry));
                }

                Platform.runLater(() -> {
                    node.getChildren().addAll(newChildren);
                    node.setLoadingMore(false);
                    node.setHasMore(result.cookie() != null);
                    node.setPagingCookie(result.cookie());
                });

            } catch (Exception e) {
                Platform.runLater(() -> node.setLoadingMore(false));
            }
        });
    }

    /** Build a tree node for a child entry returned by a one-level search. */
    private LDAPNode buildChildNode(LDAPEntry entry) {
        String rdn = extractRDN(entry.getDn());
        List<String> ocs = entry.getValues("objectClass");
        LDAPNode child = new LDAPNode(entry.getDn(), rdn, ocs, false);

        ChildCount count = DirectoryTopology.readChildCount(entry);
        boolean isLeaf = DirectoryTopology.isLeafEntry(count, schemaService.isContainer(ocs));
        child.setLeaf(isLeaf);
        if (!isLeaf && count.isKnownPopulated()) {
            child.setTotalChildCount(count.value());
        }
        return child;
    }

    // --- Attribute Loading ---

    public void selectNode(LDAPNode node) {
        selectedNode.set(node);
        clearSearch();
        expandedMultiValueAttrs.clear();
        loadAttributes(node.getDn());
    }

    public void loadAttributes(String dn) {
        loadAttributes(dn, null);
    }

    public void loadAttributes(String dn, String[] attributes) {
        selectedDN.set(dn);
        loadingAttributes.set(true);
        selectedAttributes.clear();
        // Reset multi-value expansion: a new load means the row identities the
        // expansion set was tracking may no longer line up with the new rows.
        // (selectNode also clears, but search-result navigation reuses the same
        // DN with a different attribute list.)
        expandedMultiValueAttrs.clear();
        boolean includeOperational = showOperationalAttributes.get();

        executor.submit(() -> {
            try {
                Map<String, List<String>> attrs;
                if (attributes != null && !(attributes.length == 1 && "*".equals(attributes[0]))) {
                    attrs = ldapService.fetchAttributes(dn, attributes);
                } else {
                    attrs = ldapService.fetchAttributes(dn, includeOperational);
                }

                // Cache for toggle reuse
                cachedAttributes = attrs;
                cachedAttributesDN = dn;

                List<AttributeRow> rows = buildAttributeRows(attrs);

                Platform.runLater(() -> {
                    selectedAttributes.setAll(rows);
                    loadingAttributes.set(false);
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    loadingAttributes.set(false);
                    ErrorAlert.show("Load Failed",
                            "Could not load attributes for " + dn + ":\n" + e.getMessage());
                });
            }
        });
    }

    private List<AttributeRow> buildAttributeRows(Map<String, List<String>> attrs) {
        List<AttributeRow> rows = new ArrayList<>();
        for (var entry : attrs.entrySet()) {
            String attrName = entry.getKey();
            LDAPAttributeInfo info = schemaService.getAttributeMap()
                    .get(attrName.toLowerCase());
            LDAPAttributeSyntax syntax = info != null ? info.getSyntax() : LDAPAttributeSyntax.STRING;
            boolean operational = info != null && info.isOperational();

            List<String> values = entry.getValue();
            boolean isExpanded = expandedMultiValueAttrs.contains(attrName);
            int limit = (values.size() > MAX_VALUES_COLLAPSED && !isExpanded)
                    ? MAX_VALUES_COLLAPSED : values.size();

            for (int i = 0; i < limit; i++) {
                rows.add(new AttributeRow(attrName, values.get(i), syntax, operational));
            }

            if (values.size() > MAX_VALUES_COLLAPSED) {
                int hidden = values.size() - MAX_VALUES_COLLAPSED;
                rows.add(AttributeRow.toggle(attrName, isExpanded ? 0 : hidden));
            }
        }
        return rows;
    }

    // --- Search ---

    public void performSearch(String baseDN, String filter, SearchScope scope,
                               String[] attributes, int sizeLimit) {
        searching.set(true);
        searchError.set(null);
        searchResults.clear();
        selectedAttributes.clear();
        selectedDN.set(null);

        executor.submit(() -> {
            try {
                int effectiveLimit = sizeLimit; // 0 = unlimited
                LDAPConnectionConfig config = ldapService.getCurrentConfig();
                boolean usePaging = config != null && config.isUsePagedResults();

                // Search from the specified base DN directly.
                // eDirectory with paged results handles subtree search from "" correctly.
                List<LDAPEntry> results = new ArrayList<>();
                String searchBase = baseDN != null ? baseDN : "";
                String partialError = null;

                if (usePaging) {
                    byte[] cookie = null;
                    try {
                        do {
                            int pageSize = (effectiveLimit > 0)
                                    ? Math.min(1000, effectiveLimit - results.size()) : 1000;
                            if (effectiveLimit > 0 && pageSize <= 0) break;
                            var page = ldapService.searchPaged(searchBase, scope, filter,
                                    attributes, pageSize, cookie, effectiveLimit);
                            results.addAll(page.entries());
                            cookie = page.cookie();

                            // Update UI incrementally per page
                            List<LDAPEntry> snapshot = List.copyOf(results);
                            Platform.runLater(() -> searchResults.setAll(snapshot));

                            if (effectiveLimit > 0 && results.size() >= effectiveLimit) break;
                        } while (cookie != null);
                    } catch (Exception ex) {
                        partialError = simplifyErrorMessage(ex.getMessage());
                    }
                } else {
                    try {
                        var limited = ldapService.searchLimited(searchBase, scope, filter,
                                attributes, effectiveLimit > 0 ? effectiveLimit : 0);
                        results.addAll(limited.entries());
                        if (limited.truncated()) {
                            partialError = "Results truncated: server size limit reached "
                                    + "(" + limited.entries().size() + " returned).";
                        }
                    } catch (Exception ex) {
                        partialError = simplifyErrorMessage(ex.getMessage());
                    }
                }

                List<LDAPEntry> finalResults = List.copyOf(results);
                String finalError = partialError;
                Platform.runLater(() -> {
                    searchResults.setAll(finalResults);
                    searchError.set(finalError);
                    searching.set(false);
                });

            } catch (Exception e) {
                System.err.println("[ArborJ] Search failed: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> {
                    searching.set(false);
                    searchError.set(e.getMessage());
                });
            }
        });
    }

    public void toggleMultiValueExpansion(String attrName) {
        if (expandedMultiValueAttrs.contains(attrName)) {
            expandedMultiValueAttrs.remove(attrName);
        } else {
            expandedMultiValueAttrs.add(attrName);
        }
        // Rebuild from cache instead of re-fetching from server
        if (cachedAttributes != null) {
            List<AttributeRow> rows = buildAttributeRows(cachedAttributes);
            selectedAttributes.setAll(rows);
        }
    }

    public void clearSearch() {
        searchResults.clear();
        searchError.set(null);
    }

    // --- Entry Management ---

    public void createEntry(String parentDN, String rdn, List<String> objectClasses,
                             Map<String, List<String>> attributes) {
        executor.submit(() -> {
            try {
                String dn = (parentDN == null || parentDN.isEmpty()) ? rdn : rdn + "," + parentDN;
                Map<String, List<String>> allAttrs = new LinkedHashMap<>(attributes);
                allAttrs.put("objectClass", objectClasses);
                ldapService.addEntry(dn, allAttrs);

                // Refresh parent node
                Platform.runLater(() -> refreshParent(dn));
            } catch (Exception e) {
                ErrorAlert.show("Add Entry Failed", e.getMessage());
            }
        });
    }

    public void deleteEntry(String dn) {
        executor.submit(() -> {
            try {
                ldapService.deleteEntry(dn);
                Platform.runLater(() -> refreshParent(dn));
            } catch (Exception e) {
                ErrorAlert.show("Delete Failed", e.getMessage());
            }
        });
    }

    public void renameEntry(String dn, String newRDN, boolean deleteOldRDN) {
        executor.submit(() -> {
            try {
                ldapService.renameEntry(dn, newRDN, deleteOldRDN, null);
                Platform.runLater(() -> refreshParent(dn));
            } catch (Exception e) {
                ErrorAlert.show("Rename Failed", e.getMessage());
            }
        });
    }

    /**
     * For a top-level DN like {@code o=data}, the parent in the tree UI is the
     * synthetic tree-root node whose DN is {@code ""} — not the entry itself.
     */
    static String parentDnOf(String dn) {
        if (dn == null || dn.isEmpty()) return "";
        try {
            String parent = DN.getParentString(dn);
            return parent == null ? "" : parent;
        } catch (Exception ignored) {
            int comma = firstUnescapedComma(dn);
            return comma < 0 ? "" : dn.substring(comma + 1);
        }
    }

    private void refreshParent(String childDn) {
        LDAPNode parent = findNode(parentDnOf(childDn), rootNodes);
        if (parent != null) {
            parent.setChildrenLoaded(false);
            loadChildren(parent);
        }
    }

    /**
     * Re-fetch the children of {@code node} from the directory. Keeps the
     * tree's expansion state intact but drops the cached child list so newly
     * added entries appear (and removed ones disappear) without needing a
     * disconnect/reconnect cycle.
     */
    public void refreshNode(LDAPNode node) {
        if (node == null) return;
        if (node.isLoading()) return;
        node.setChildrenLoaded(false);
        node.setPagingCookie(null);
        node.setHasMore(false);
        // Give a node that an earlier empty search demoted to a leaf another
        // chance to open. Without this the demotion is permanent for the
        // session, and an entry created under it elsewhere stays invisible.
        // loadChildren() demotes it again if it really is still empty.
        node.setLeaf(false);
        loadChildren(node);
    }

    /** Reload the whole tree — drops cached children at every level and re-fetches naming contexts. */
    public void refreshTree() {
        if (!connected.get()) return;
        rootNodes.clear();
        loadRootNodes();
    }

    /**
     * Request the tree view to expand to and select the entry with the given
     * DN. The tree view listens on this property and performs the async
     * navigation (loading children at each level as needed). Setting the
     * same value twice in a row still fires — the view re-resets to null.
     */
    private final StringProperty navigationTargetDN = new SimpleStringProperty();
    public StringProperty navigationTargetDNProperty() { return navigationTargetDN; }
    public void navigateToDN(String dn) {
        if (dn == null || dn.isBlank()) return;
        // Force fire even when the same DN is requested twice in a row.
        navigationTargetDN.set(null);
        navigationTargetDN.set(dn);
    }

    // eDirectory membership attributes that trigger security equivalence sync
    private static final Set<String> EDIR_GROUP_MEMBER_ATTRS = Set.of("member", "uniquemember");
    private static final Set<String> EDIR_USER_MEMBER_ATTRS = Set.of("groupmembership");

    public void saveAttributeValue(String dn, String attribute, String oldValue, String newValue) {
        executor.submit(() -> {
            try {
                if (oldValue == null) {
                    ldapService.modifyAttribute(dn, attribute,
                            LDAPService.LDAPModifyOperation.ADD, List.of(newValue));
                    // Sync security equivalence on add
                    syncMembershipAdd(dn, attribute, newValue);
                } else {
                    // Edit one value: send a single REPLACE with the full new
                    // value list. RFC 4511 §4.6 atomicity is per-request, but
                    // eDirectory enforces schema between modifications WITHIN
                    // the request — so a DELETE(old) + ADD(new) pair fails on
                    // MUST attributes (NDS error -602 "missing mandatory")
                    // because the entry is briefly without the attribute
                    // between the two steps. REPLACE has no such intermediate
                    // state. Works for single- and multi-valued attributes,
                    // mandatory or not.
                    List<String> newValues = computeReplacementValues(
                            dn, attribute, oldValue, newValue);
                    ldapService.modifyAttribute(dn, attribute,
                            LDAPService.LDAPModifyOperation.REPLACE, newValues);
                    // Sync: remove old, add new
                    syncMembershipDelete(dn, attribute, oldValue);
                    syncMembershipAdd(dn, attribute, newValue);
                }
                Platform.runLater(() -> loadAttributes(dn));
            } catch (Exception e) {
                ErrorAlert.show("Save Failed", e.getMessage());
            }
        });
    }

    /**
     * Build the value list to send with a REPLACE when the user edited one
     * value of an attribute from {@code oldValue} to {@code newValue}. Uses
     * the cached attribute list for the current DN when available, so a
     * multi-valued attribute keeps its other values; case-insensitive
     * matching mirrors the case-ignore behavior of most LDAP string syntaxes.
     */
    private List<String> computeReplacementValues(String dn, String attribute,
                                                   String oldValue, String newValue)
            throws com.unboundid.ldap.sdk.LDAPException {
        List<String> current;
        if (dn.equals(cachedAttributesDN) && cachedAttributes != null) {
            // Find the cached values case-insensitively — the cache preserves
            // the schema name's casing but attribute names elsewhere may vary.
            current = null;
            for (var e : cachedAttributes.entrySet()) {
                if (e.getKey().equalsIgnoreCase(attribute)) {
                    current = e.getValue();
                    break;
                }
            }
            if (current == null) current = List.of();
        } else {
            // Cache miss (selection changed, reconnect, etc.) — fetch fresh.
            Map<String, List<String>> attrs = ldapService.fetchAttributes(dn, false);
            current = List.of();
            for (var e : attrs.entrySet()) {
                if (e.getKey().equalsIgnoreCase(attribute)) {
                    current = e.getValue();
                    break;
                }
            }
        }
        // Replace oldValue (case-insensitive) with newValue in the list. If
        // the old value isn't found (stale cache or concurrent edit), append
        // the new value — we still want it in the result, and we shouldn't
        // accidentally drop other values we don't recognize.
        List<String> result = new ArrayList<>(current.size());
        boolean replaced = false;
        for (String v : current) {
            if (!replaced && v.equalsIgnoreCase(oldValue)) {
                result.add(newValue);
                replaced = true;
            } else {
                result.add(v);
            }
        }
        if (!replaced) result.add(newValue);
        return result;
    }

    public void deleteAttributeValue(String dn, String attribute, String value) {
        executor.submit(() -> {
            try {
                LDAPAttributeInfo info = schemaService.getAttributeMap().get(attribute.toLowerCase());
                boolean isBinary = info != null && info.getSyntax() == LDAPAttributeSyntax.BINARY;
                if (isBinary) {
                    // Binary values are shown as hex in the UI; the server needs the raw bytes
                    // to match the existing value, otherwise it returns NDS -603 (no such value).
                    ldapService.modifyBinaryAttribute(dn, attribute,
                            LDAPService.LDAPModifyOperation.DELETE, List.of(hexToBytes(value)));
                } else {
                    ldapService.modifyAttribute(dn, attribute,
                            LDAPService.LDAPModifyOperation.DELETE, List.of(value));
                    // Sync security equivalence on delete (string-valued membership attrs only)
                    syncMembershipDelete(dn, attribute, value);
                }
                Platform.runLater(() -> loadAttributes(dn));
            } catch (Exception e) {
                ErrorAlert.show("Delete Value Failed", e.getMessage());
            }
        });
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

    private boolean shouldSyncSecurityEquivalence(String attribute) {
        if (directoryType.get() != DirectoryType.EDIRECTORY) return false;
        var config = ldapService.getCurrentConfig();
        if (config == null || !config.isSyncSecurityEquivalence()) return false;
        if (readOnly.get()) return false;
        String lower = attribute.toLowerCase();
        return EDIR_GROUP_MEMBER_ATTRS.contains(lower) || EDIR_USER_MEMBER_ATTRS.contains(lower);
    }

    /**
     * When a user is added to a group, sync securityEquals and equivalentToMe.
     */
    private void syncMembershipAdd(String dn, String attribute, String valueAdded) {
        if (!shouldSyncSecurityEquivalence(attribute)) return;

        String lower = attribute.toLowerCase();
        String userDN, groupDN;
        if (EDIR_GROUP_MEMBER_ATTRS.contains(lower)) {
            // Editing a group's member attribute: dn=group, value=user
            groupDN = dn;
            userDN = valueAdded;
        } else {
            // Editing a user's groupMembership attribute: dn=user, value=group
            userDN = dn;
            groupDN = valueAdded;
        }

        // Best-effort: don't fail the primary operation if sync fails, but
        // surface the failure so the user can see when the directory is left
        // half-synced (member-of without securityEquals, or vice versa).
        try {
            ldapService.modifyAttribute(userDN, "securityEquals",
                    LDAPService.LDAPModifyOperation.ADD, List.of(groupDN));
        } catch (Exception ex) {
            warnSyncFailure("add securityEquals", userDN, groupDN, ex);
        }
        try {
            ldapService.modifyAttribute(groupDN, "equivalentToMe",
                    LDAPService.LDAPModifyOperation.ADD, List.of(userDN));
        } catch (Exception ex) {
            warnSyncFailure("add equivalentToMe", groupDN, userDN, ex);
        }
    }

    /**
     * When a user is removed from a group, remove securityEquals and equivalentToMe.
     */
    private void syncMembershipDelete(String dn, String attribute, String valueRemoved) {
        if (!shouldSyncSecurityEquivalence(attribute)) return;

        String lower = attribute.toLowerCase();
        String userDN, groupDN;
        if (EDIR_GROUP_MEMBER_ATTRS.contains(lower)) {
            groupDN = dn;
            userDN = valueRemoved;
        } else {
            userDN = dn;
            groupDN = valueRemoved;
        }

        try {
            ldapService.modifyAttribute(userDN, "securityEquals",
                    LDAPService.LDAPModifyOperation.DELETE, List.of(groupDN));
        } catch (Exception ex) {
            warnSyncFailure("remove securityEquals", userDN, groupDN, ex);
        }
        try {
            ldapService.modifyAttribute(groupDN, "equivalentToMe",
                    LDAPService.LDAPModifyOperation.DELETE, List.of(userDN));
        } catch (Exception ex) {
            warnSyncFailure("remove equivalentToMe", groupDN, userDN, ex);
        }
    }

    private void warnSyncFailure(String op, String onDN, String value, Exception ex) {
        // Logged-but-not-fatal: the primary group-membership change succeeded,
        // but the secondary security-equivalence sync didn't. Surface it so the
        // user knows the directory is partially synced.
        String msg = "[ArborJ] Membership sync failed (" + op + ") on "
                + onDN + " (value=" + value + "): " + ex.getMessage();
        System.err.println(msg);
        Platform.runLater(() ->
                ErrorAlert.show("Security-Equivalence Sync Failed",
                        "The group membership change succeeded, but updating the "
                        + "matching security-equivalence attribute failed.\n\n"
                        + "Operation: " + op + "\n"
                        + "Target: " + onDN + "\n"
                        + "Value: " + value + "\n\n"
                        + ex.getMessage()));
    }

    // --- ACL Management ---

    public List<com.pointbluetech.arborj.model.ACLModels.TrusteeGroup> loadACLs(String dn)
            throws com.unboundid.ldap.sdk.LDAPException {
        String targetDN = dn;
        if (dn == null || dn.isEmpty()) {
            // Tree-wide ACLs: stored on T=TreeName object
            String treeName = ldapService.fetchTreeName();
            if (treeName == null) throw new com.unboundid.ldap.sdk.LDAPException(
                    com.unboundid.ldap.sdk.ResultCode.OTHER, "Could not determine tree name");
            targetDN = "T=" + treeName;
        }
        Map<String, List<String>> attrs = ldapService.fetchAttributes(targetDN, false);
        List<String> aclValues = attrs.getOrDefault("ACL", List.of());
        // Case-insensitive fallback
        if (aclValues.isEmpty()) {
            for (var entry : attrs.entrySet()) {
                if (entry.getKey().equalsIgnoreCase("ACL")) {
                    aclValues = entry.getValue();
                    break;
                }
            }
        }
        return com.pointbluetech.arborj.model.ACLModels.parseACLs(aclValues);
    }

    public void saveACLs(String dn, List<com.pointbluetech.arborj.model.ACLModels.TrusteeGroup> groups)
            throws com.unboundid.ldap.sdk.LDAPException {
        String targetDN = dn;
        if (dn == null || dn.isEmpty()) {
            String treeName = ldapService.fetchTreeName();
            if (treeName == null) throw new com.unboundid.ldap.sdk.LDAPException(
                    com.unboundid.ldap.sdk.ResultCode.OTHER, "Could not determine tree name");
            targetDN = "T=" + treeName;
        }
        List<String> aclStrings = com.pointbluetech.arborj.model.ACLModels.toACLStrings(groups);
        ldapService.modifyAttribute(targetDN, "ACL",
                LDAPService.LDAPModifyOperation.REPLACE, aclStrings);
    }

    // --- LDIF Export ---

    public String exportEntryAsLDIF(String dn) {
        try {
            Map<String, List<String>> attrs = ldapService.fetchAttributes(dn, false);
            LDAPEntry entry = new LDAPEntry(dn, attrs);
            return new com.pointbluetech.arborj.service.LDIFExporter().exportEntry(entry);
        } catch (Exception e) {
            ErrorAlert.show("Export Failed", e.getMessage());
            return null;
        }
    }

    public String exportChildrenAsLDIF(String dn) {
        try {
            List<LDAPEntry> children = ldapService.search(dn, SearchScope.ONE_LEVEL,
                    "(objectClass=*)", "*");
            return new com.pointbluetech.arborj.service.LDIFExporter().exportEntries(children);
        } catch (Exception e) {
            ErrorAlert.show("Export Failed", e.getMessage());
            return null;
        }
    }

    public String exportSubtreeAsLDIF(String dn) {
        try {
            List<LDAPEntry> entries = ldapService.search(dn, SearchScope.SUBTREE,
                    "(objectClass=*)", "*");
            return new com.pointbluetech.arborj.service.LDIFExporter().exportEntries(entries);
        } catch (Exception e) {
            ErrorAlert.show("Export Failed", e.getMessage());
            return null;
        }
    }

    // --- Utility ---

    public void shutdown() {
        disconnect();
        executor.shutdownNow();
    }

    private LDAPNode findNode(String dn, List<LDAPNode> nodes) {
        for (LDAPNode node : nodes) {
            if (node.getDn().equalsIgnoreCase(dn)) return node;
            LDAPNode found = findNode(dn, node.getChildren());
            if (found != null) return found;
        }
        return null;
    }

    private String simplifyErrorMessage(String msg) {
        if (msg == null) return "Connection failed";
        // Extract the most useful part from nested LDAP exception messages
        if (msg.contains("ConnectException")) {
            if (msg.contains("Connection refused")) return "Connection refused — is the server running?";
            if (msg.contains("Network is unreachable")) return "Network unreachable — check the server address";
            if (msg.contains("timed out")) return "Connection timed out";
        }
        if (msg.contains("SSLHandshakeException")) {
            if (msg.contains("handshake_failure")) return "TLS handshake failed — server may require different TLS settings";
            if (msg.contains("certificate_unknown")) return "Server certificate not trusted";
        }
        if (msg.contains("resultCode=49")) return "Invalid credentials — check bind DN and password";
        if (msg.contains("resultCode=32")) return "Base DN not found on server";
        // Truncate overly verbose messages
        if (msg.length() > 120) {
            int idx = msg.indexOf("Exception(");
            if (idx > 0) {
                // Find the innermost cause
                int lastIdx = msg.lastIndexOf("Exception(");
                if (lastIdx > idx) {
                    String inner = msg.substring(lastIdx);
                    int end = inner.indexOf(')');
                    if (end > 0) return inner.substring(0, end + 1);
                }
            }
            return msg.substring(0, 117) + "...";
        }
        return msg;
    }

    private String extractRDN(String dn) {
        if (dn == null || dn.isEmpty()) return "";
        try {
            return DN.getRDNString(dn);
        } catch (Exception ignored) {
            int commaIdx = firstUnescapedComma(dn);
            return commaIdx > 0 ? dn.substring(0, commaIdx) : dn;
        }
    }

    private static int firstUnescapedComma(String dn) {
        boolean escaped = false;
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == ',') {
                return i;
            }
        }
        return -1;
    }

    // --- Property Accessors ---

    public BooleanProperty connectedProperty() { return connected; }
    public BooleanProperty connectingProperty() { return connecting; }
    public BooleanProperty reconnectingProperty() { return reconnecting; }
    public BooleanProperty readOnlyProperty() { return readOnly; }
    public StringProperty connectionErrorProperty() { return connectionError; }
    public StringProperty connectedProfileNameProperty() { return connectedProfileName; }
    public ObjectProperty<DirectoryType> directoryTypeProperty() { return directoryType; }
    public ObservableList<LDAPNode> getRootNodes() { return rootNodes; }
    public ObjectProperty<LDAPNode> selectedNodeProperty() { return selectedNode; }
    public StringProperty treeErrorProperty() { return treeError; }
    public ObservableList<AttributeRow> getSelectedAttributes() { return selectedAttributes; }
    public StringProperty selectedDNProperty() { return selectedDN; }
    public BooleanProperty loadingAttributesProperty() { return loadingAttributes; }
    public BooleanProperty showOperationalAttributesProperty() { return showOperationalAttributes; }
    public ObservableList<LDAPEntry> getSearchResults() { return searchResults; }
    public BooleanProperty searchingProperty() { return searching; }
    public StringProperty searchErrorProperty() { return searchError; }
    public ObjectProperty<CertificateDetails> pendingCertDetailsProperty() { return pendingCertDetails; }
    public BooleanProperty dirXMLSupportedProperty() { return dirXMLSupported; }
    public BooleanProperty effectiveRightsSupportedProperty() { return effectiveRightsSupported; }
    public BooleanProperty passwordModifySupportedProperty() { return passwordModifySupported; }

    public LDAPService getLdapService() { return ldapService; }
    public SchemaService getSchemaService() { return schemaService; }
    public ProfileStore getProfileStore() { return profileStore; }
    public CredentialStore getCredentialStore() { return credentialStore; }

    // --- Inner Types ---

    public record AttributeRow(String name, String value, LDAPAttributeSyntax syntax,
                                boolean operational, boolean isToggleRow, int hiddenCount) {

        /** Normal attribute value row. */
        public AttributeRow(String name, String value, LDAPAttributeSyntax syntax, boolean operational) {
            this(name, value, syntax, operational, false, 0);
        }

        /** Toggle row: "Show N more values" / "Show less". */
        public static AttributeRow toggle(String name, int hiddenCount) {
            return new AttributeRow(name, "", LDAPAttributeSyntax.STRING, false, true, hiddenCount);
        }
    }
}
