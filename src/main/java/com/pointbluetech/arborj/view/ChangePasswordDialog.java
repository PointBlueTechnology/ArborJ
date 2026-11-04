package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import com.pointbluetech.arborj.model.DirectoryType;
import com.pointbluetech.arborj.util.EdirPasswordPolicy;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
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
 * - eDirectory: shows the Universal Password policy that applies (read from
 *   {@code nspmPasswordPolicyDN} on the user, the parent container, the
 *   partition root, or the Login Policy). When none applies, the user
 *   chooses Universal Password (RFC 3062) or Legacy NDS (replace
 *   {@code userPassword} over LDAPS). A Password Modify failure offers
 *   that legacy replace only when the policy lookup succeeded and found
 *   no policy. The writes run off the JavaFX thread.
 * - Generic / OpenLDAP: RFC 3062 Password Modify. Supports self-service
 *   (current + new password) and administrative reset (new password only —
 *   uses the connected admin's rights).
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

    // eDirectory: policy status and, when no Universal Password policy
    // applies, which write to send.
    private final Label policyStatusLabel =
            new Label("Checking whether a Universal Password policy applies…");
    private final Label policyDnLabel = new Label();
    private final RadioButton universalRadio =
            new RadioButton("Universal Password (Password Modify)");
    private final RadioButton legacyRadio =
            new RadioButton("Legacy NDS (replace userPassword)");
    private final ToggleGroup methodGroup = new ToggleGroup();
    private final VBox methodBox = new VBox(4);
    private final Label legacySecureHint = new Label(
            "Legacy NDS replaces userPassword and needs an LDAPS connection.");
    private final Label legacyNoVerifyHint = new Label(
            "Legacy NDS does not verify the current password. It replaces userPassword using the bound account's rights.");

    /** Set on the JavaFX thread when the policy read finishes. Null if it failed. */
    private EdirPasswordPolicy.PolicyLookup policyLookup;
    private Button okButton;

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
        content.setPrefWidth(520);

        getDialogPane().setContent(content);

        // Dialog buttons
        ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(okType, cancelType);

        okButton = (Button) getDialogPane().lookupButton(okType);
        Button okBtn = okButton;
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

        // For eDirectory, read the Universal Password policy before OK is
        // enabled so the default method matches what the tree actually has.
        if (dirType == DirectoryType.EDIRECTORY) {
            okBtn.setDisable(true);
            loadEdirPolicy(okBtn);
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

        if (dirType == DirectoryType.EDIRECTORY) {
            policyStatusLabel.setWrapText(true);
            policyDnLabel.setFont(Font.font("monospaced", 11));
            policyDnLabel.setWrapText(true);
            policyDnLabel.setVisible(false);
            policyDnLabel.setManaged(false);
            VBox policyBox = new VBox(2, policyStatusLabel, policyDnLabel);
            form.add(policyBox, 0, row++, 2, 1);

            universalRadio.setToggleGroup(methodGroup);
            legacyRadio.setToggleGroup(methodGroup);
            universalRadio.setSelected(true);
            Label methodLabel = new Label("Method");
            methodLabel.setStyle("-fx-font-weight: bold;");
            legacySecureHint.setWrapText(true);
            legacySecureHint.setStyle("-fx-font-size: 11px;");
            legacySecureHint.setVisible(false);
            legacySecureHint.setManaged(false);
            legacyNoVerifyHint.setWrapText(true);
            legacyNoVerifyHint.setStyle("-fx-font-size: 11px;");
            methodBox.getChildren().addAll(
                    methodLabel, universalRadio, legacyRadio, legacyNoVerifyHint, legacySecureHint);
            // Hidden until the policy read finishes. Shown only when no
            // Universal Password policy applies.
            methodBox.setVisible(false);
            methodBox.setManaged(false);
            form.add(methodBox, 0, row++, 2, 1);
        }

        // Current password -- shown for non-AD directories, and only when
        // an administrative reset is NOT being performed. Legacy NDS is a
        // userPassword replace and does not send the current password.
        boolean showOldPassword = dirType != DirectoryType.ACTIVE_DIRECTORY;
        Label currentLabel = new Label("Current Password:");
        if (showOldPassword) {
            form.add(currentLabel, 0, row);
            form.add(currentPasswordField, 1, row++);

            // Empty placeholder cell + checkbox in the value column
            form.add(adminResetCheckbox, 1, row++);

            var showCurrent = Bindings.and(
                    adminResetCheckbox.selectedProperty().not(),
                    legacyRadio.selectedProperty().not());
            currentLabel.visibleProperty().bind(showCurrent);
            currentLabel.managedProperty().bind(currentLabel.visibleProperty());
            currentPasswordField.visibleProperty().bind(showCurrent);
            currentPasswordField.managedProperty().bind(currentPasswordField.visibleProperty());
            adminResetCheckbox.visibleProperty().bind(legacyRadio.selectedProperty().not());
            adminResetCheckbox.managedProperty().bind(adminResetCheckbox.visibleProperty());
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
        // Self-service (non-admin) Password Modify requires the current
        // password. A legacy NDS replace does not send one.
        if (dirType != DirectoryType.ACTIVE_DIRECTORY
                && !legacyRadio.isSelected()
                && !adminResetCheckbox.isSelected()
                && currentPasswordField.getText().isEmpty()) {
            showError("Current password is required.\n\n"
                    + "Check “Administrative reset” if you are doing an "
                    + "admin password reset using your bound credentials.");
            currentPasswordField.requestFocus();
            return;
        }

        // Read every control here. The write itself runs off the JavaFX thread.
        boolean legacy = legacyRadio.isSelected();
        String oldPassword = effectiveOldPassword();
        boolean mustChange = mustChangeCheckbox.isSelected();
        boolean neverExpires = neverExpiresCheckbox.isSelected();
        boolean applyUac = uacFetched;
        int uac = currentUAC;
        EdirPasswordPolicy.PolicyLookup lookup = policyLookup;

        okButton.setDisable(true);
        Thread.ofVirtual().start(() -> {
            try {
                writePassword(dirType, newPassword, oldPassword, legacy,
                        mustChange, neverExpires, applyUac, uac);
                Platform.runLater(this::close);
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    okButton.setDisable(false);
                    // formatPasswordError's text is the exception message.
                    // A legacy retry is only for a conclusive "no policy".
                    String msg = messageOf(ex);
                    if (dirType == DirectoryType.EDIRECTORY
                            && !legacy
                            && EdirPasswordPolicy.offerLegacyRetry(lookup, ex)) {
                        offerLegacyRetry(newPassword, msg);
                        return;
                    }
                    showError(msg);
                });
            }
        });
    }

    private void writePassword(DirectoryType dirType, String newPassword, String oldPassword,
                               boolean legacy, boolean mustChange, boolean neverExpires,
                               boolean applyUac, int uac) throws Exception {
        switch (dirType) {
            case ACTIVE_DIRECTORY -> {
                controller.getLdapService().changePasswordAD(targetDN, newPassword);
                // Only touch userAccountControl when we successfully read
                // the existing value first — otherwise a REPLACE based on
                // currentUAC=0 would wipe account-type bits and ACCOUNTDISABLE.
                if (applyUac) {
                    controller.getLdapService().updateADPasswordFlags(
                            targetDN, mustChange, neverExpires, uac);
                }
            }
            case EDIRECTORY -> {
                if (legacy) {
                    controller.getLdapService().changePasswordLegacyNDS(targetDN, newPassword);
                } else {
                    controller.getLdapService().changePasswordEDir(targetDN, oldPassword, newPassword);
                }
            }
            default -> controller.getLdapService().changePasswordRFC3062(
                    targetDN, oldPassword, newPassword);
        }
    }

    /**
     * Password Modify failed and the lookup had already found no Universal
     * Password policy. Ask before replacing {@code userPassword}. The
     * replace runs off the JavaFX thread. A cleartext connection cannot do
     * it, so the Password Modify error stands.
     */
    private void offerLegacyRetry(String newPassword, String message) {
        if (!controller.getLdapService().isSecureConnection()) {
            showError(message
                    + "\n\nA legacy NDS replace was not attempted because this "
                    + "connection is not LDAPS.");
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Retry with Legacy NDS Password");
        alert.setHeaderText("Retry by replacing userPassword?");
        alert.setContentText(message
                + "\n\nNo Universal Password policy applies. Retry with Legacy NDS "
                + "(replace userPassword over this LDAPS connection)? "
                + "That does not verify the current password and does not go "
                + "through Universal Password.");
        alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        var window = ownerWindow();
        if (window != null) alert.initOwner(window);
        var choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.YES) return;

        okButton.setDisable(true);
        Thread.ofVirtual().start(() -> {
            try {
                controller.getLdapService().changePasswordLegacyNDS(targetDN, newPassword);
                Platform.runLater(this::close);
            } catch (Exception retryEx) {
                Platform.runLater(() -> {
                    okButton.setDisable(false);
                    showError(messageOf(retryEx));
                });
            }
        });
    }

    private void loadEdirPolicy(Button okBtn) {
        Thread.ofVirtual().start(() -> {
            EdirPasswordPolicy.PolicyLookup lookup = null;
            String failure = null;
            try {
                lookup = controller.getLdapService().findUniversalPasswordPolicy(targetDN);
            } catch (Exception e) {
                failure = e.getMessage() == null || e.getMessage().isBlank()
                        ? e.toString() : e.getMessage();
            }
            EdirPasswordPolicy.PolicyLookup found = lookup;
            String error = failure;
            boolean secure = false;
            try {
                secure = controller.getLdapService().isSecureConnection();
            } catch (Exception ignored) {
                secure = false;
            }
            boolean secureConnection = secure;
            Platform.runLater(() -> {
                // A failed read is not a lookup result. Leave policyLookup null
                // so a later Password Modify error is shown as-is.
                policyLookup = error == null ? found : null;
                EdirPasswordPolicy.PolicyLookup forUi = policyLookup;
                policyStatusLabel.setText(EdirPasswordPolicy.describeStatus(forUi, error));
                boolean hasPolicy = forUi != null && forUi.assignment() != null;
                policyDnLabel.setText(hasPolicy ? forUi.assignment().policyDN() : "");
                policyDnLabel.setVisible(hasPolicy);
                policyDnLabel.setManaged(hasPolicy);

                boolean offer = EdirPasswordPolicy.offerMethodChoice(forUi);
                methodBox.setVisible(offer);
                methodBox.setManaged(offer);
                legacyRadio.setDisable(!secureConnection);
                legacySecureHint.setVisible(offer && !secureConnection);
                legacySecureHint.setManaged(offer && !secureConnection);

                EdirPasswordPolicy.Method method = EdirPasswordPolicy.defaultMethod(
                        forUi, secureConnection);
                if (method == EdirPasswordPolicy.Method.LEGACY_NDS) {
                    legacyRadio.setSelected(true);
                } else {
                    universalRadio.setSelected(true);
                }
                okBtn.setDisable(false);
            });
        });
    }

    private static String messageOf(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) return "Password change failed.";
        return msg;
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
        var window = ownerWindow();
        if (window != null) alert.initOwner(window);
        alert.showAndWait();
    }

    private javafx.stage.Window ownerWindow() {
        // Belt-and-suspenders: even with the addEventFilter fix, an error from
        // the constructor's async policy / UAC read can fire before the dialog
        // has a scene, so guard the owner lookup.
        var pane = getDialogPane();
        var scene = (pane != null) ? pane.getScene() : null;
        return (scene != null) ? scene.getWindow() : null;
    }
}
