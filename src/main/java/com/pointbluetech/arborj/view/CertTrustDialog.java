package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.model.CertificateDetails;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Dialog prompting user to trust an untrusted TLS certificate.
 */
public class CertTrustDialog extends Dialog<Boolean> {

    private static final Font MONO = Font.font("monospaced", 12);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public CertTrustDialog(CertificateDetails cert) {
        setTitle("Untrusted Certificate");
        setHeaderText("The server presented a certificate that is not trusted by your system.");
        setResizable(true);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));
        content.setPrefWidth(520);

        // Warning
        Label warning = new Label("Do you want to trust this certificate?");
        warning.setFont(Font.font("System", FontWeight.BOLD, 14));
        warning.setTextFill(Color.web("#cc6600"));

        // Certificate details grid
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);

        int row = 0;
        addField(grid, row++, "Subject", cert.getSubject());
        addField(grid, row++, "Issuer", cert.getIssuer());

        if (cert.getNotBefore() != null) {
            addField(grid, row++, "Valid From", DATE_FMT.format(cert.getNotBefore()));
        }
        if (cert.getNotAfter() != null) {
            addField(grid, row++, "Valid Until", DATE_FMT.format(cert.getNotAfter()));
        }

        addField(grid, row++, "SHA-256 Fingerprint", cert.getSha256Fingerprint());

        // Chain info
        Label chainLabel = new Label("Certificate chain: " + cert.getChain().size() + " certificate(s)");
        chainLabel.setTextFill(Color.GRAY);

        content.getChildren().addAll(warning, new Separator(), grid, chainLabel);

        getDialogPane().setContent(content);

        // Buttons
        ButtonType trustBtn = new ButtonType("Trust This Certificate", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(trustBtn, cancelBtn);

        setResultConverter(buttonType -> buttonType == trustBtn);
    }

    private void addField(GridPane grid, int row, String label, String value) {
        Label nameLabel = new Label(label + ":");
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        nameLabel.setMinWidth(130);

        Label valueLabel = new Label(value);
        valueLabel.setFont(MONO);
        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(360);

        grid.add(nameLabel, 0, row);
        grid.add(valueLabel, 1, row);
    }
}
