package com.bahairesearch;

import javafx.application.Application;

/**
 * JVM launcher entry point used by IDE/debug tooling.
 */
public final class BahaiResearchLauncher {

    private BahaiResearchLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(BahaiResearch.class, args);
    }
}