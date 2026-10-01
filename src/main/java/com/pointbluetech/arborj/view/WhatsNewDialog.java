package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.ArborJApp;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.prefs.Preferences;

/**
 * Shows a "What's New" dialog on the first launch of a new version.
 */
public class WhatsNewDialog {

    private static final Preferences prefs = Preferences.userNodeForPackage(WhatsNewDialog.class);
    private static final String PREF_LAST_SEEN_VERSION = "lastSeenVersion";

    /**
     * Shows the dialog if the user hasn't seen it for this version yet.
     */
    public static void showIfNew(javafx.stage.Window owner) {
        String lastSeen = prefs.get(PREF_LAST_SEEN_VERSION, "");
        String current = ArborJApp.APP_VERSION;

        if (!current.equals(lastSeen)) {
            show(owner);
            prefs.put(PREF_LAST_SEEN_VERSION, current);
        }
    }

    public static void show() {
        show(null);
    }

    public static void show(javafx.stage.Window owner) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle("What's New in ArborJ " + ArborJApp.APP_VERSION);
        stage.setResizable(false);

        VBox root = new VBox(16);
        root.setPadding(new Insets(28));
        root.setAlignment(Pos.TOP_CENTER);

        // Icon
        ImageView icon = new ImageView();
        var iconUrl = WhatsNewDialog.class.getResource("/com/pointbluetech/arborj/icons/icon-128.png");
        if (iconUrl != null) {
            icon.setImage(new Image(iconUrl.toExternalForm()));
            icon.setFitWidth(72);
            icon.setFitHeight(72);
        }

        // Title
        Label title = new Label("What's New in ArborJ " + ArborJApp.APP_VERSION);
        title.setFont(Font.font("System", FontWeight.BOLD, 20));

        // Feature list
        VBox features = new VBox(12);
        features.setPadding(new Insets(8, 0, 8, 0));

        features.getChildren().addAll(
                featureItem("eDirectory Partitions Browse Correctly",
                        "Containers that are also partition roots — the top of the tree, "
                        + "IDM driver sets, partitioned OUs — now open and show their "
                        + "children. Previously eDirectory reported them as having zero "
                        + "subordinates (they are seen as subordinate references from the "
                        + "parent partition), so ArborJ drew them as childless leaves. A "
                        + "subordinateCount of zero is no longer taken at face value."),

                featureItem("No More Duplicate Partition Roots",
                        "eDirectory publishes every partition it holds as a naming context, "
                        + "including ones nested deep in the tree, and each of those was "
                        + "being added to the top of the tree as well as appearing in its "
                        + "real place. The stray copy carried no objectClass and opened its "
                        + "attributes but never its children. The tree and the DN picker now "
                        + "list a naming context at the top only when it cannot be reached "
                        + "by walking down. Active Directory's Configuration and Schema "
                        + "contexts still appear at the top, where they belong."),

                featureItem("Empty Containers Stop Pretending",
                        "A container that turns out to be empty now loses its expansion "
                        + "arrow once the search comes back, instead of leaving an arrow "
                        + "pointing at nothing, and a stale subordinate count next to the "
                        + "name is corrected rather than left as the server first "
                        + "advertised it. Refresh is now offered on those entries too, so "
                        + "a container that later gains children can be opened again "
                        + "without reconnecting."),

                featureItem("Deleting a Binary Attribute Value Works Again",
                        "Right-click → Delete Value on a binary or stream-syntax "
                        + "attribute (DirXML-DriverStorage, certificates, JPEGs, "
                        + "audio, embedded configs) now actually removes the value "
                        + "instead of failing with NDS error -603 “no such "
                        + "attribute value.” The hex-encoded display string was "
                        + "being sent back to the server as the value to delete, "
                        + "which couldn't match the underlying bytes; deletes now "
                        + "decode the hex and submit the raw bytes via a "
                        + "byte-level LDAP modify."),

                featureItem("Refresh Without Reconnecting",
                        "New entries added under a container no longer require a "
                        + "disconnect / reconnect cycle to appear. Right-click any "
                        + "container in the tree and pick Refresh, or press F5 "
                        + "(Cmd+R / Ctrl+R also works) to refresh the selected node "
                        + "— or the whole tree if nothing is selected. Expansion "
                        + "state is preserved across the reload."),

                featureItem("Locate DN in DIT",
                        "Right-click any DN-syntax attribute value — member, "
                        + "manager, groupMembership, securityEquals, manager, etc. "
                        + "— and pick “Locate DN in DIT” to jump straight to that "
                        + "entry in the tree. The tree expands from the matching "
                        + "naming context down to the target, loading each "
                        + "container along the way, then selects and scrolls to "
                        + "the entry. Mirrors the same-named action in Apache "
                        + "Directory Studio."),

                featureItem("Editing MUST Attributes Works Again",
                        "Editing a single value of a mandatory attribute (sn, cn, "
                        + "uid, …) on a User now succeeds. The 2.0.5 “atomic edit” "
                        + "fix sent DELETE(old) and ADD(new) as one LDAP modify; "
                        + "eDirectory validates schema between the two steps in a "
                        + "single request and refused with NDS error -602 “missing "
                        + "mandatory” after the DELETE because the entry "
                        + "transiently had no value for the attribute. The edit "
                        + "now sends a single REPLACE with the full new value "
                        + "list, which has no intermediate empty state and is "
                        + "still atomic in the RFC 4511 sense."),

                featureItem("Reliability Pass: Atomic Edits, Safer Saves, More Trustworthy TLS",
                        "Editing an attribute value now sends the old-value-delete and "
                        + "new-value-add as a single atomic LDAP modify, so a server-side "
                        + "rejection of the new value can't leave the entry with the old "
                        + "value already deleted. The Connection dialog's Fetch-Base-DN "
                        + "button now actually authenticates with the entered credentials "
                        + "(it was anonymous before, which silently returned nothing on "
                        + "directories that disallow anonymous Root DSE reads). Active "
                        + "Directory password reset no longer risks clobbering "
                        + "userAccountControl when the dialog couldn't read the existing "
                        + "flags first. Unchecking “Save Password” now properly removes "
                        + "any stored credential on the next successful connect, instead "
                        + "of leaving it behind. The TLS trust prompt now fires for every "
                        + "default-keystore failure — no more opaque “Connection failed” "
                        + "errors when a self-signed or private-CA cert needs manual "
                        + "approval."),

                featureItem("Hex Viewer: Edit Hex, Load from File, Save to File",
                        "Three new buttons in the binary value viewer for editing and "
                        + "transferring bytes:\n"
                        + "• Edit Hex… opens a paste-style sub-dialog where you can "
                        + "enter or modify hex directly. Tolerates whitespace, commas, "
                        + "colons, and the dialog's own dump format, so you can copy "
                        + "from the main view, edit, and OK.\n"
                        + "• Load from File… replaces the value with the contents of a "
                        + "file (handy for cert imports, embedded configs).\n"
                        + "• Save to File… writes the current bytes to disk. Always "
                        + "available, even on read-only attributes — useful for "
                        + "extracting certificates or raw configuration blobs."),

                featureItem("Attribute Picker: Paste List, History, and Ordered Columns",
                        "The Select Attributes dialog has a new “Paste List” tab — an "
                        + "editable input field where you can paste or type a comma- "
                        + "or space-separated attribute list, like Apache Directory "
                        + "Studio's Returning Attributes affordance. The dropdown "
                        + "remembers your past inputs across sessions. Edits in the "
                        + "Pick tab and Paste tab stay in sync automatically — no "
                        + "Apply button. The selected list also preserves your "
                        + "chosen order (with new ↑/↓ buttons to reorder), and the "
                        + "Search Results Table View now shows columns in that order. "
                        + "On OK, every selected name is validated against the schema "
                        + "and unknown names are flagged with an error so you can fix "
                        + "the input before searching."),

                featureItem("Hex Viewer for Large Binary Attributes",
                        "Opening the hex editor on very large binary or stream-syntax "
                        + "attributes (DirXML driver-set Java parameters, embedded "
                        + "configuration, large certificates) now shows the full "
                        + "contents. The hex view is virtualized — display cost no "
                        + "longer scales with the size of the value, and the previous "
                        + "rendering limit that could make a long dump look truncated "
                        + "is gone."),

                featureItem("Password Changes: Admin Reset and Better Errors",
                        "The Change Password dialog now has an "
                        + "“Administrative reset” checkbox that omits the current "
                        + "password and authorizes the change with the bound user's "
                        + "rights — useful when an admin is resetting another "
                        + "user's password. Failure messages now decode NMAS error "
                        + "codes from eDirectory's response controls and matched-DN "
                        + "field, so reasons like “Password too short”, “Password "
                        + "in history”, or “Password unique violation” appear "
                        + "directly in the alert instead of the generic "
                        + "“NMAS Change Password extension failed”. The LDAP result "
                        + "code is always included for diagnosis. Also fixed a "
                        + "crash when an error happened during a password change."),

                featureItem("Partition Status Fixes",
                        "Partition Status now reports the correct replica type, state, and "
                        + "number for every holder. Previously the root partition could show "
                        + "every server as Master, and read/write replicas could show state "
                        + "“New” regardless of their actual state. The view now pulls "
                        + "authoritative data from the same NDS replica-info call that "
                        + "Replication Status uses, so the two are consistent."),

                featureItem("Streaming LDIF Import",
                        "Imports now parse and apply entries one at a time straight from disk "
                        + "instead of reading the whole file into memory, so multi-hundred-MB "
                        + "LDIFs no longer OOM the app. Base64 values written without a space "
                        + "after “::” now decode correctly per RFC 2849."),

                featureItem("No More Modal Flash on macOS",
                        "Settings, About, Filter Builder, DN Picker, and every eDirectory / "
                        + "Active Directory subview now open with a proper window owner, so "
                        + "they no longer briefly flash and then drop behind the main window."),

                featureItem("Search Results Truncation Warning",
                        "When a non-paged search hits the server's size limit, the results "
                        + "panel now shows a warning explaining that the list was truncated, "
                        + "instead of silently displaying a partial result as if it were "
                        + "complete."),

                featureItem("Filter Builder Round-Trip",
                        "The visual filter builder now escapes and unescapes RFC 4515 special "
                        + "characters (\\, *, parens, NUL), so filters with literal "
                        + "wildcards or parentheses in values are preserved exactly when "
                        + "edited and saved."),

                featureItem("Directory-Aware Menus",
                        "DirXML Command and Effective Rights items are hidden on eDirectory "
                        + "servers that don't advertise the corresponding extended-operation "
                        + "OIDs, and the Change Password item is hidden on servers that don't "
                        + "support RFC 3062. No more clicks that fail with an obscure error."),

                featureItem("Cmd+W Closes the Window",
                        "On macOS, ⌘W now closes the active ArborJ window (matching system "
                        + "convention), instead of just disconnecting the session. The window's "
                        + "close handler still tears down the LDAP connection cleanly."),

                featureItem("Auto-Reconnect Refreshes State",
                        "When the heartbeat reconnects after a connection drop, ArborJ now "
                        + "re-detects the directory type, reloads the schema, and refreshes "
                        + "the tree. Previously the UI kept showing stale data after a "
                        + "reconnect."),

                featureItem("Atomic Profile and Credential Saves",
                        "Connection profiles and stored passwords / certificates are now "
                        + "written via a temp file and atomic rename, so a crash mid-write "
                        + "can't leave a truncated profiles.json or credentials.enc behind. "
                        + "Drag-drop profile reorders also now do a single write per "
                        + "operation instead of one per profile."),

                featureItem("Quick Connect Stops Saving Itself",
                        "Connecting from the dialog without selecting or editing a saved "
                        + "profile no longer creates a permanent “Quick Connect” entry on "
                        + "disk. Saved profiles still persist as before."),

                featureItem("Visible Sync Errors",
                        "When a group-membership change succeeds but the secondary "
                        + "securityEquals / equivalentToMe write fails, a non-fatal alert "
                        + "now tells you the directory is partially synced. Previously the "
                        + "failure was swallowed silently.")
        );

        ScrollPane scroll = new ScrollPane(features);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(300);
        scroll.setStyle("-fx-background-color: transparent;");

        // Close button
        Button closeBtn = new Button("Get Started");
        closeBtn.setDefaultButton(true);
        closeBtn.setOnAction(e -> stage.close());
        closeBtn.setStyle("-fx-font-size: 14;");

        root.getChildren().addAll(icon, title, scroll, closeBtn);

        Scene scene = new Scene(root, 480, 520);
        stage.setScene(scene);

        if (iconUrl != null) {
            stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }

        stage.showAndWait();
    }

    private static VBox featureItem(String heading, String description) {
        Label headLabel = new Label(heading);
        headLabel.setFont(Font.font("System", FontWeight.BOLD, 13));

        Label descLabel = new Label(description);
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12;");

        VBox box = new VBox(2, headLabel, descLabel);
        box.setPadding(new Insets(0, 4, 0, 4));
        return box;
    }
}
