package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.DirectoryType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;

/**
 * Password change dialog that adapts its UI and behavior
 * based on the connected directory type.
 *
 * - Active Directory: new password only, plus UAC flag checkboxes.
 * - eDirectory / Generic / OpenLDAP: RFC 3062 Password Modify. Supports
 *   self-service (current + new password) and administrative reset
 *   (new password only — uses the connected admin's rights).
 */
public class ChangePasswordDialog extends Dialog<Void> {

    private static final int AD_UF_DONT_EXPIRE_PASSWD = 0x00010000;

    private final MainController controller;
    private final String targetDN;

    private final PasswordField currentPasswordField = new PasswordField();
    private final PasswordField newPasswordField = new PasswordField();
    private final PasswordField confirmPasswordField = new PasswordField();

    // Non-AD: admin-reset toggle (RFC 3062 allows omitting the old password
    // when the bound user has reset rights on the target).
    private final CheckBox adminResetCheckbox =
            new CheckBox("Administrative reset (don't require current password)");

    // AD-specific controls
    private final CheckBox mustChangeCheckbox = new CheckBox("User must change password at next logon");
    private final CheckBox neverExpiresCheckbox = new CheckBox("Password never expires");

    private int currentUAC = 0;
    /** True only after fetchADFlags successfully read userAccountControl.
     *  When false, doChangePassword skips updateADPasswordFlags so a stale
     *  zero doesn't clobber the existing UAC bits. */
    private boolean uacFetched = false;

    public ChangePasswordDialog(MainController controller, String targetDN) {
        this(controller, targetDN, null);
    }

    public ChangePasswordDialog(MainController controller, String targetDN,
                                 javafx.stage.Window owner) {
        this.controller = controller;
        this.targetDN = targetDN;

        setTitle("Change Password");
        setResizable(false);
        if (owner != null) initOwner(owner);

        DirectoryType dirType = controller.directoryTypeProperty().get();

        Font mono = Font.font("monospaced", 12);
        currentPasswordField.setFont(mono);
        newPasswordField.setFont(mono);
        confirmPasswordField.setFont(mono);

        VBox content = buildContent(dirType);
        content.setPadding(new Insets(12));
        content.setPrefWidth(450);

        getDialogPane().setContent(content);

        // Dialog buttons
        ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(okType, cancelType);

        Button okBtn = (Button) getDialogPane().lookupButton(okType);
        // addEventFilter (not setOnAction): JavaFX runs filters before the
        // Dialog's internal action that closes the pane, so consuming here
        // keeps the dialog open. setOnAction runs after the close filter,
        // by which time getDialogPane().getScene() is already null.
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            doChangePassword(dirType);
        });

        // For AD, fetch current UAC flags asynchronously. Disable OK until done so
        // the user can't submit a request that would clobber existing UAC bits with 0.
        if (dirType == DirectoryType.ACTIVE_DIRECTORY) {
            okBtn.setDisable(true);
            fetchADFlags(okBtn);
        }
    }

    private VBox buildContent(DirectoryType dirType) {
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(140);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelCol, fieldCol);

        int row = 0;

        // DN display
        Label dnLabel = new Label("DN:");
        Label dnValue = new Label(targetDN);
        dnValue.setFont(Font.font("monospaced", 11));
        dnValue.setWrapText(true);
        form.add(dnLabel, 0, row);
        form.add(dnValue, 1, row++);

        form.add(new Separator(), 0, row++, 2, 1);

        // Current password -- shown for non-AD directories, and only when
        // an administrative reset is NOT being performed.
        boolean showOldPassword = dirType != DirectoryType.ACTIVE_DIRECTORY;
        Label currentLabel = new Label("Current Password:");
        if (showOldPassword) {
            form.add(currentLabel, 0, row);
            form.add(currentPasswordField, 1, row++);

            // Empty placeholder cell + checkbox in the value column
            form.add(adminResetCheckbox, 1, row++);

            // Hide current-password row when admin-reset is checked. Keeps the
            // form compact and makes the active mode obvious at a glance.
            currentLabel.visibleProperty().bind(adminResetCheckbox.selectedProperty().not());
            currentLabel.managedProperty().bind(currentLabel.visibleProperty());
            currentPasswordField.visibleProperty().bind(adminResetCheckbox.selectedProperty().not());
            currentPasswordField.managedProperty().bind(currentPasswordField.visibleProperty());
            // Clear the field when switching to admin-reset so a stale value
            // can't accidentally be sent.
            adminResetCheckbox.selectedProperty().addListener((obs, was, now) -> {
                if (now) currentPasswordField.clear();
            });
        }

        // New password
        form.add(new Label("New Password:"), 0, row);
        form.add(newPasswordField, 1, row++);

        // Confirm password
        form.add(new Label("Confirm Password:"), 0, row);
        form.add(confirmPasswordField, 1, row++);

        VBox container = new VBox(8, form);

        // AD-specific options
        if (dirType == DirectoryType.ACTIVE_DIRECTORY) {
            container.getChildren().add(new Separator());
            Label adOptionsLabel = new Label("Active Directory Options");
            adOptionsLabel.setStyle("-fx-font-weight: bold;");
            container.getChildren().addAll(adOptionsLabel, mustChangeCheckbox, neverExpiresCheckbox);

            // Mutual exclusion: "must change" and "never expires" conflict
            mustChangeCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal) neverExpiresCheckbox.setSelected(false);
            });
            neverExpiresCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal) mustChangeCheckbox.setSelected(false);
            });
        }

        return container;
    }

    private void fetchADFlags(Button okBtn) {
        Thread.ofVirtual().start(() -> {
            try {
                int uac = controller.getLdapService().fetchADUserAccountControl(targetDN);
                Platform.runLater(() -> {
                    currentUAC = uac;
                    uacFetched = true;
                    neverExpiresCheckbox.setSelected((uac & AD_UF_DONT_EXPIRE_PASSWD) != 0);
                    okBtn.setDisable(false);
                });
            } catch (Exception e) {
                // Couldn't read the existing UAC. The password change itself
                // still works (unicodePwd modify doesn't depend on UAC), but
                // we must NOT touch userAccountControl with a fabricated zero
                // — that would clear account-type bits, ACCOUNTDISABLE, etc.
                // Disable the AD-only options so the user knows the flag
                // toggles below won't be applied.
                Platform.runLater(() -> {
                    currentUAC = 0;
                    uacFetched = false;
                    mustChangeCheckbox.setSelected(false);
                    mustChangeCheckbox.setDisable(true);
                    neverExpiresCheckbox.setSelected(false);
                    neverExpiresCheckbox.setDisable(true);
                    showError("Could not read this user's account flags: " + e.getMessage()
                            + "\n\nThe password can still be reset, but the "
                            + "“must change at next logon” and “never expires” "
                            + "options below have been disabled to avoid "
                            + "overwriting other userAccountControl flags.");
                    okBtn.setDisable(false);
                });
            }
        });
    }

    private void doChangePassword(DirectoryType dirType) {
        String newPassword = newPasswordField.getText();
        String confirmPassword = confirmPasswordField.getText();

        // Validation
        if (newPassword.isEmpty()) {
            showError("New password must not be empty.");
            newPasswordField.requestFocus();
            return;
        }
        if (!newPassword.equals(confirmPassword)) {
            showError("New password and confirmation do not match.");
            confirmPasswordField.requestFocus();
            return;
        }
        // Self-service (non-admin) on non-AD requires the current password.
        if (dirType != DirectoryType.ACTIVE_DIRECTORY
                && !adminResetCheckbox.isSelected()
                && currentPasswordField.getText().isEmpty()) {
            showError("Current password is required.\n\n"
                    + "Check “Administrative reset” if you are doing an "
                    + "admin password reset using your bound credentials.");
            currentPasswordField.requestFocus();
            return;
        }

        try {
            switch (dirType) {
                case ACTIVE_DIRECTORY -> {
                    controller.getLdapService().changePasswordAD(targetDN, newPassword);
                    // Only touch userAccountControl when we successfully read
                    // the existing value first — otherwise a REPLACE based on
                    // currentUAC=0 would wipe account-type bits and ACCOUNTDISABLE.
                    if (uacFetched) {
                        controller.getLdapService().updateADPasswordFlags(
                                targetDN,
                                mustChangeCheckbox.isSelected(),
                                neverExpiresCheckbox.isSelected(),
                                currentUAC);
                    }
                }
                case EDIRECTORY -> {
                    String oldPassword = effectiveOldPassword();
                    controller.getLdapService().changePasswordEDir(targetDN, oldPassword, newPassword);
                }
                default -> {
                    // Generic / OpenLDAP -- RFC 3062
                    controller.getLdapService().changePasswordRFC3062(
                            targetDN, effectiveOldPassword(), newPassword);
                }
            }
            close();
        } catch (Exception ex) {
            // The service already prefixes its message with "Password change
            // failed:" and includes the LDAP result code; don't double-prefix.
            String msg = ex.getMessage();
            if (msg == null || msg.isBlank()) msg = "Password change failed.";
            showError(msg);
        }
    }

    /**
     * Returns the old-password value to send with RFC 3062, or null when the
     * user has selected an administrative reset. UnboundID's
     * PasswordModifyExtendedRequest accepts null for the old password and the
     * server uses the bound user's rights to authorize the change.
     */
    private String effectiveOldPassword() {
        if (adminResetCheckbox.isSelected()) return null;
        String typed = currentPasswordField.getText();
        return typed.isEmpty() ? null : typed;
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        // Belt-and-suspenders: even with the addEventFilter fix, an error from
        // the constructor's async fetchADFlags can fire before the dialog has
        // a scene, so guard the owner lookup.
        var pane = getDialogPane();
        var scene = (pane != null) ? pane.getScene() : null;
        var window = (scene != null) ? scene.getWindow() : null;
        if (window != null) alert.initOwner(window);
        alert.showAndWait();
    }
}
