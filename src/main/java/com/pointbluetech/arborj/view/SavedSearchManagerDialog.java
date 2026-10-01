package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.model.SavedSearch;
import com.pointbluetech.arborj.service.SavedSearchStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;

/**
 * Dialog for managing saved searches — rename and delete.
 */
public class SavedSearchManagerDialog extends Stage {

    private final SavedSearchStore store = SavedSearchStore.getInstance();

    public SavedSearchManagerDialog() {
        this(null);
    }

    public SavedSearchManagerDialog(javafx.stage.Window owner) {
        setTitle("Manage Saved Searches");
        initModality(Modality.APPLICATION_MODAL);
        if (owner != null) initOwner(owner);
        setResizable(true);

        ListView<SavedSearch> list = new ListView<>(store.getEntries());
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SavedSearch item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String attrs = formatAttributes(item.getSelectedAttributes());
                    setText(item.getName() + "  —  " + item.getFilter()
                            + (attrs.isEmpty() ? "" : "  [" + attrs + "]"));
                }
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Button renameBtn = new Button("Rename");
        renameBtn.setDisable(true);
        renameBtn.setOnAction(e -> {
            SavedSearch selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            TextInputDialog dialog = new TextInputDialog(selected.getName());
            dialog.setTitle("Rename Saved Search");
            dialog.setHeaderText(null);
            dialog.setContentText("Name:");
            dialog.showAndWait().ifPresent(newName -> {
                if (!newName.trim().isEmpty()) {
                    selected.setName(newName.trim());
                    store.update(selected);
                    list.refresh();
                }
            });
        });

        Button deleteBtn = new Button("Delete");
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(e -> {
            SavedSearch selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete saved search \"" + selected.getName() + "\"?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Confirm Delete");
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    store.remove(selected);
                }
            });
        });

        list.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasSelection = newVal != null;
            renameBtn.setDisable(!hasSelection);
            deleteBtn.setDisable(!hasSelection);
        });

        Button closeBtn = new Button("Close");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> close());

        HBox buttons = new HBox(8, renameBtn, deleteBtn, new Region(), closeBtn);
        HBox.setHgrow(buttons.getChildren().get(2), Priority.ALWAYS);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, list, buttons);
        root.setPadding(new Insets(12));

        Scene scene = new Scene(root, 550, 350);
        setScene(scene);
    }

    private String formatAttributes(List<String> attrs) {
        if (attrs == null || attrs.isEmpty()) return "";
        if (attrs.size() == 1 && "*".equals(attrs.getFirst())) return "";
        return String.join(", ", attrs);
    }
}
