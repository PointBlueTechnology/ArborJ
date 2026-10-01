package com.pointbluetech.arborj.view;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Window;

public final class ErrorAlert {

    private ErrorAlert() {}

    public static void show(String title, String message) {
        Runnable r = () -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            Window owner = Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .findFirst()
                    .orElse(null);
            if (owner != null) alert.initOwner(owner);
            alert.showAndWait();
        };
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }
}
