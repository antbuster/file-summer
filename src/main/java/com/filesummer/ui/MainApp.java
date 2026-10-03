package com.filesummer.ui;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.List;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        MainView view = new MainView(stage);
        Scene scene = new Scene(view.getRoot(), 1200, 720);
        stage.setScene(scene);
        for (String icon : List.of("filesummer-16.png", "filesummer-24.png",
                "filesummer-32.png", "filesummer-48.png")) {
            stage.getIcons().add(new Image(
                    MainApp.class.getResourceAsStream("/icon/" + icon)));
        }
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        view.initFromStore();
        stage.setTitle(com.filesummer.i18n.I18n.t("app.title"));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
