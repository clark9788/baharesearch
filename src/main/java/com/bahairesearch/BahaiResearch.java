package com.bahairesearch;

import com.bahairesearch.config.AppConfig;
import com.bahairesearch.config.ConfigLoader;
import com.bahairesearch.corpus.CorpusIngestService;
import com.bahairesearch.model.QuoteResult;
import com.bahairesearch.model.ResearchReport;
import com.bahairesearch.research.ResearchService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/**
 * JavaFX desktop application for topic-based Bahá'í quote research.
 */
public class BahaiResearch extends Application {

    private static final int WINDOW_WIDTH = 1100;
    private static final int WINDOW_HEIGHT = 760;

    private final ResearchService researchService = new ResearchService();

    /**
     * Build and display the main application window.
     */
    @Override
    public void start(Stage stage) {
        // ── Filter bar (above input) ──────────────────────────────────────────
        // Author ComboBox — hardcoded (Baha'i primary authors are fixed)
        Label authorLabel = new Label("Author:");
        ComboBox<String> authorCombo = new ComboBox<>();
        authorCombo.getItems().addAll(
            "All Authors",
            "'Abdu'l-Baha",
            "Bab",
            "Baha'u'llah",
            "Compilation",
            "Shoghi Effendi",
            "Universal House of Justice"
        );
        authorCombo.setValue("All Authors");

        // Title ComboBox — populated from manifest.csv at startup.
        // To add a new compilation, update manifest.csv only — no code change needed.
        Label titleLabel = new Label("Title:");
        ComboBox<String> titleCombo = new ComboBox<>();
        titleCombo.getItems().add("All Titles");
        try {
            AppConfig startupConfig = ConfigLoader.load();
            List<String> titles = CorpusIngestService.readTitlesFromManifest(startupConfig);
            titleCombo.getItems().addAll(titles);
        } catch (Exception ignored) {
            // Graceful: dropdown shows only "All Titles" if config not yet available
        }
        titleCombo.setValue("All Titles");
        HBox.setHgrow(titleCombo, Priority.ALWAYS);

        HBox filterBar = new HBox(8, authorLabel, authorCombo, titleLabel, titleCombo);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(0, 0, 4, 0));

        // ── Input area ────────────────────────────────────────────────────────
        Label inputLabel = new Label("Research Topic");

        TextArea topicInputArea = new TextArea();
        topicInputArea.setPromptText("Describe your topic in paragraph form. Research begins only when you click the button.");
        topicInputArea.setWrapText(true);
        topicInputArea.setPrefRowCount(5);

        Button beginResearchButton = new Button("Begin Research →");

        HBox controls = new HBox(beginResearchButton);
        controls.setSpacing(8);

        // ── Output area ───────────────────────────────────────────────────────
        TextArea outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setWrapText(true);
        outputArea.setPromptText("Summary and sourced quotes will appear here.");

        // Wire button now that both input and output areas are in scope
        beginResearchButton.setOnAction(event -> {
            String authorFilter = "All Authors".equals(authorCombo.getValue()) ? null : authorCombo.getValue();
            String titleFilter  = "All Titles".equals(titleCombo.getValue())   ? null : titleCombo.getValue();
            runResearch(topicInputArea, outputArea, beginResearchButton, authorFilter, titleFilter);
        });

        // ── Layout ────────────────────────────────────────────────────────────
        VBox root = new VBox(filterBar, inputLabel, topicInputArea, controls, outputArea);
        root.setSpacing(12);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-font-size: 16px;");

        VBox.setVgrow(outputArea, Priority.ALWAYS);

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);

        stage.setTitle("BahaiResearch");
        stage.setScene(scene);
        stage.setMinWidth(840);
        stage.setMinHeight(560);
        stage.setResizable(true);
        stage.show();

        Platform.runLater(topicInputArea::requestFocus);
    }

    private void runResearch(
        TextArea topicInputArea,
        TextArea outputArea,
        Button beginResearchButton,
        String selectedAuthor,
        String selectedTitle
    ) {
        String topic = topicInputArea.getText();
        outputArea.setText("Running research... this may take a moment.\n");
        beginResearchButton.setDisable(true);

        Task<ResearchReport> researchTask = new Task<>() {
            @Override
            protected ResearchReport call() {
                return researchService.conductResearch(topic, selectedAuthor, selectedTitle);
            }
        };

        researchTask.setOnSucceeded(workerStateEvent -> {
            ResearchReport report = researchTask.getValue();
            outputArea.setText(formatReport(report));
            beginResearchButton.setDisable(false);
        });

        researchTask.setOnFailed(workerStateEvent -> {
            Throwable throwable = researchTask.getException();
            String message = throwable == null ? "Unknown error" : throwable.getMessage();
            outputArea.setText("Research failed:\n" + message);
            beginResearchButton.setDisable(false);
        });

        Thread workerThread = new Thread(researchTask, "research-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private String formatReport(ResearchReport report) {
        StringBuilder resultBuilder = new StringBuilder();
        resultBuilder.append("Summary\n");
        resultBuilder.append("=======\n");
        resultBuilder.append(report.summary()).append("\n\n");
        resultBuilder.append("Quotes\n");
        resultBuilder.append("======\n\n");

        if (report.quotes().isEmpty()) {
            resultBuilder.append("No quotes were returned. Check topic wording and source availability.\n");
            return resultBuilder.toString();
        }

        int quoteNumber = 1;
        for (QuoteResult quote : report.quotes()) {
            resultBuilder.append(quoteNumber)
                .append(") \"")
                .append(quote.quote())
                .append("\"\n")
                .append("   Author: ")
                .append(quote.author())
                .append("\n")
                .append("   Book: ")
                .append(quote.bookTitle())
                .append("\n")
                .append("   Paragraph/Page: ")
                .append(quote.paragraphOrPage())
                .append("\n")
                .append("   URL: ")
                .append(quote.sourceUrl())
                .append("\n\n");
            quoteNumber++;
        }

        return resultBuilder.toString();
    }
}
