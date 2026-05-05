package ysogui;

import javafx.application.Application;
import javafx.stage.Stage;
import ysogui.ui.MainWindow;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        new MainWindow(primaryStage);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
