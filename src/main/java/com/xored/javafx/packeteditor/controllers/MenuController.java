package com.xored.javafx.packeteditor.controllers;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.xored.javafx.packeteditor.metatdata.PacketTemplate;
import com.xored.javafx.packeteditor.metatdata.ProtocolMetadata;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

public class MenuController implements Initializable {

    private Logger logger= LoggerFactory.getLogger(MenuController.class);
    
    @Inject
    FieldEditorController controller;

    @FXML
    MenuBar applicationMenu;

    @FXML
    Menu newTemplateMenu;

    @Inject
    @Named("resources")
    ResourceBundle resourceBundle;

    private ChoiceDialog<ProtocolMetadata> dialog = new ChoiceDialog<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        PacketTemplate.loadTemplates().forEach(templateFile -> {
            MenuItem menuItem = new MenuItem(templateFile.metadata.caption);
            menuItem.setOnAction(event -> {
                controller.getModel().loadTemplate(templateFile);
            });
            newTemplateMenu.getItems().add(menuItem);
        });
    }

    @FXML
    private void handleCloseAction() {
        logger.info("Closing application");
        System.exit(0);
    }
    
    @FXML
    private void handleAddProtocolAction(ActionEvent actionEvent) {
        List<ProtocolMetadata> items = controller.getAvailbleProtocolsToAdd();

        dialog.getItems().clear();
        dialog.getItems().addAll(items);
        if(!items.isEmpty()) {
            dialog.setSelectedItem(items.get(0));
        }
        
        dialog.setTitle(resourceBundle.getString("ADD_LAYER"));
        dialog.setContentText(resourceBundle.getString("SELECT_PROTOCOL"));
        
        // TODO: Add proper protocol icon to dialog
        // dialog.setGraphic(new ImageView(this.getClass().getResource("protocol-image.png").toString()));
        
        Optional<ProtocolMetadata> result = dialog.showAndWait();
        if(result.isPresent()) {
            controller.addProtocol(result.get());
        }
    }
    @FXML
    public void handleDeleteProtocolAction(ActionEvent actionEvent) {
        controller.removeLast();
    }

    @FXML
    public void handleNewDocument(ActionEvent actionEvent) {
        controller.newPacket();
    }

    @FXML
    public void handleOpenAction(ActionEvent actionEvent) {
        controller.showLoadDialog();
    }

    @FXML
    public void handleSaveAction(ActionEvent event) {
        controller.showSaveDialog();
    }

    @FXML
    public void handleRecalculateValues(ActionEvent actionEvent) {
        controller.recalculateAutoValues();
    }

    @FXML
    public void handleUndo(ActionEvent actionEvent) {
        controller.undo();
    }

    @FXML
    public void handleRedo(ActionEvent actionEvent) {
        controller.redo();
    }

    @FXML
    public void handleModeBinary(ActionEvent actionEvent) {
        controller.getModel().setBinaryMode(true);
    }

    @FXML
    public void handleModeAbstract(ActionEvent actionEvent) {
        controller.getModel().setBinaryMode(false);
    }
}
