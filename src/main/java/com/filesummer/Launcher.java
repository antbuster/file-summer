package com.filesummer;

/**
 * Classpath entry point. Deliberately does NOT extend javafx.application.Application:
 * that keeps the JavaFX runtime version check happy when the app itself runs
 * from the classpath while JavaFX lives on the module path.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        com.filesummer.ui.MainApp.main(args);
    }
}
