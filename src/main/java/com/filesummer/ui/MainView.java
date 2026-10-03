package com.filesummer.ui;

import com.filesummer.config.ProjectStore;
import com.filesummer.config.Workspace;
import com.filesummer.core.CollectReport;
import com.filesummer.core.FileCollector;
import com.filesummer.core.FileIssue;
import com.filesummer.gradle.GradleScriptGenerator;
import com.filesummer.i18n.I18n;
import com.filesummer.model.ConflictPolicy;
import com.filesummer.model.ProjectConfig;
import com.filesummer.model.RuleGroup;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.tbee.javafx.scene.layout.MigPane;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.filesummer.i18n.I18n.t;

public class MainView {

    private final Stage stage;
    private final MigPane root = new MigPane("fill,insets 0,gap 0", "[grow]", "[][grow][][]");
    private final ProjectStore store = new ProjectStore();
    private final FileCollector collector = new FileCollector();
    private final GradleScriptGenerator generator = new GradleScriptGenerator();

    private Workspace workspace = Workspace.withDefaults();
    private ProjectConfig current;

    private final ComboBox<ProjectConfig> projectBox = new ComboBox<>();
    private final TableView<RuleGroup> groupTable = new TableView<>();
    private final ObservableList<RuleGroup> groups = FXCollections.observableArrayList();

    private final CheckBox enabledCheck = new CheckBox();
    private final TextField nameField = new TextField();
    private final TextField subDirField = new TextField();
    private final ListView<String> sourceDirList = new ListView<>();
    private final TextField includeSuffixField = new TextField();
    private final TextField excludeSuffixField = new TextField();
    private final TextField dirFragField = new TextField();
    private final Label scriptState = new Label();
    private final CheckBox dateCheck = new CheckBox();
    private final DatePicker fromPicker = new DatePicker();
    private final DatePicker toPicker = new DatePicker();
    private final Label dateToLabel = new Label();
    private final CheckBox recursiveCheck = new CheckBox();
    private final TextField outputDirField = new TextField();
    private final ComboBox<String> conflictBox = new ComboBox<>();
    private final RadioButton copyRadio = new RadioButton();
    private final RadioButton zipRadio = new RadioButton();
    private final ToggleGroup outputMode = new ToggleGroup();
    private final Button collectButton = new Button();
    private final ProgressBar progress = new ProgressBar();
    private final TextArea logArea = new TextArea();
    private final Label statusLabel = new Label();

    private boolean suppressSync;

    public MainView(Stage stage) {
        this.stage = stage;
    }

    public MigPane getRoot() {
        return root;
    }

    // ------------------------------------------------------------------ init

    public void initFromStore() {
        ProjectStore.LoadResult result = store.load();
        this.workspace = result.workspace();
        I18n.setLocale(I18n.parse(workspace.getLanguage()));
        buildUi();
        if (result.status() != ProjectStore.LoadStatus.OK) {
            if (result.status() == ProjectStore.LoadStatus.CORRUPTED_RESET_TO_DEFAULTS) {
                store.quarantineCorruptFile();
            }
            try {
                store.save(workspace);
            } catch (Exception e) {
                log(t("config.write_failed", e.getMessage()));
            }
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    result.message() + "\n" + t("config.recover_body"), ButtonType.OK);
            alert.setTitle(t("config.recover_title"));
            alert.setHeaderText(t("config.status_prefix", statusText(result.status())));
            alert.showAndWait();
        }
        refreshProjectBox();
        selectProject(workspace.getCurrentProject());
        log(t("app.loaded", current.getName(), current.getRules().size()));
    }

    private String statusText(ProjectStore.LoadStatus s) {
        return switch (s) {
            case OK -> t("status.ok");
            case CREATED_DEFAULTS -> t("status.first_run");
            case RESTORED_FROM_BACKUP -> t("status.restored");
            case CORRUPTED_RESET_TO_DEFAULTS -> t("status.reset");
        };
    }

    // ----------------------------------------------------------------- layout

    private void buildUi() {
        statusLabel.setText(t("status.ready"));
        root.add(topBar(), "growx,wrap");

        SplitPane center = new SplitPane(leftPane(), rightPane());
        center.setOrientation(Orientation.HORIZONTAL);
        center.setDividerPositions(0.5);
        root.add(center, "grow,wrap");

        // Bottom log: always visible, min height ~6 lines.
        logArea.setEditable(false);
        logArea.setStyle("-fx-font-size: 11px;");
        logArea.setPrefRowCount(6);
        logArea.setMinHeight(Region.USE_PREF_SIZE);
        HBox logBox = new HBox(logArea);
        HBox.setHgrow(logArea, Priority.ALWAYS);
        logBox.setPadding(new Insets(0, 8, 2, 8));
        root.add(logBox, "growx,wrap");

        HBox statusBar = new HBox(6, statusLabel);
        statusBar.setPadding(new Insets(2, 8, 4, 8));
        root.add(statusBar, "growx");
    }

    private ToolBar topBar() {
        Label projLabel = new Label(t("project.label"));
        projectBox.setMaxWidth(260);
        projectBox.setCellFactory(box -> new ListCell<>() {
            @Override
            protected void updateItem(ProjectConfig item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        projectBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ProjectConfig p) {
                return p == null ? "" : p.getName();
            }

            @Override
            public ProjectConfig fromString(String s) {
                return null;
            }
        });
        projectBox.valueProperty().addListener((obs, oldP, p) -> {
            if (p != null && p != current && !suppressSync) {
                selectProject(p);
            }
        });

        // Language menu: plain 文A button (no MenuButton arrow) + ContextMenu popup
        Button langBtn = new Button();
        Label langIcon = new Label("文A");
        langIcon.setStyle("-fx-font-weight: bold;");
        langBtn.setGraphic(langIcon);
        langBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        ToggleGroup langGroup = new ToggleGroup();
        RadioMenuItem zhItem = new RadioMenuItem(t("lang.zh"));
        RadioMenuItem jaItem = new RadioMenuItem(t("lang.ja"));
        RadioMenuItem enItem = new RadioMenuItem(t("lang.en"));
        zhItem.setToggleGroup(langGroup);
        jaItem.setToggleGroup(langGroup);
        enItem.setToggleGroup(langGroup);
        String currentLang = workspace.getLanguage();
        zhItem.setSelected(!I18n.TAG_JA.equals(currentLang) && !I18n.TAG_EN.equals(currentLang));
        jaItem.setSelected(I18n.TAG_JA.equals(currentLang));
        enItem.setSelected(I18n.TAG_EN.equals(currentLang));
        zhItem.setOnAction(e -> {
            if (!I18n.TAG_ZH.equals(workspace.getLanguage())) {
                switchLanguage(I18n.TAG_ZH);
            }
        });
        jaItem.setOnAction(e -> {
            if (!I18n.TAG_JA.equals(workspace.getLanguage())) {
                switchLanguage(I18n.TAG_JA);
            }
        });
        enItem.setOnAction(e -> {
            if (!I18n.TAG_EN.equals(workspace.getLanguage())) {
                switchLanguage(I18n.TAG_EN);
            }
        });
        ContextMenu langMenu = new ContextMenu(zhItem, jaItem, enItem);
        langBtn.setOnAction(e -> langMenu.show(langBtn, Side.BOTTOM, 0, 0));

        Button newBtn = new Button(t("project.new"));
        newBtn.setOnAction(e -> createProject());
        Button deleteBtn = new Button(t("project.delete"));
        deleteBtn.setOnAction(e -> deleteProject());
        Button saveBtn = new Button(t("config.save"));
        saveBtn.setOnAction(e -> {
            flushDetailToGroup();
            persistNow();
            info(t("config.saved", store.configFile()));
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new ToolBar(projLabel, projectBox, newBtn, deleteBtn, saveBtn, spacer, langBtn);
    }

    /** Rebuild the whole view in the new language (settings are already persisted). */
    private void switchLanguage(String tag) {
        workspace.setLanguage(tag);
        I18n.setLocale(I18n.parse(tag));
        persistNow();
        MainView v = new MainView(stage);
        v.initFromStore();
        stage.getScene().setRoot(v.getRoot());
        stage.setTitle(I18n.t("app.title"));
    }

    /** Left: project settings on top, task-group list below filling the rest. */
    private MigPane leftPane() {
        MigPane pane = new MigPane("fillx,insets 8,gap 6", "[grow]", "[][grow]");
        pane.setMinWidth(340);
        pane.add(projectSection(), "growx,wrap");
        pane.add(groupListSection(), "grow");
        return pane;
    }

    /** Right: the selected task group's form, no title chrome. */
    private ScrollPane rightPane() {
        ScrollPane scroller = new ScrollPane(groupForm());
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroller;
    }

    private MigPane groupListSection() {
        TableColumn<RuleGroup, String> colName = new TableColumn<>(t("table.col.name"));
        colName.setCellValueFactory(cb -> cb.getValue().nameProperty());
        colName.setSortable(false);
        colName.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                RuleGroup g = empty || getIndex() >= getTableView().getItems().size()
                        ? null : getTableView().getItems().get(getIndex());
                setOpacity(g != null && !g.isEnabled() ? 0.4 : 1.0);
            }
        });

        groupTable.getColumns().setAll(List.of(colName));
        groupTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        groupTable.setItems(groups);
        groupTable.setEditable(false);
        groupTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldG, g) -> loadDetailFromGroup(g));
        groupTable.setPlaceholder(new Label(t("table.placeholder")));

        Button addBtn = new Button(t("group.add"));
        addBtn.setOnAction(e -> {
            RuleGroup g = current.newDefaultGroup();
            groups.add(g);
            syncModelFromGroups();
            groupTable.getSelectionModel().select(g);
            persistNow();
        });
        Button removeBtn = new Button(t("group.delete"));
        removeBtn.setOnAction(e -> {
            RuleGroup sel = groupTable.getSelectionModel().getSelectedItem();
            if (sel == null) {
                warn(t("group.select_first_delete"));
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    t("group.delete_confirm", sel.getName()), ButtonType.OK, ButtonType.CANCEL);
            confirm.setTitle(t("group.delete_title"));
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                groups.remove(sel);
                syncModelFromGroups();
                persistNow();
            }
        });
        Button upBtn = new Button("↑");
        upBtn.setOnAction(e -> moveSelected(-1));
        Button downBtn = new Button("↓");
        downBtn.setOnAction(e -> moveSelected(1));

        // Titleless group box, same border look as the project settings pane.
        MigPane pane = new MigPane("fillx,insets 6,gap 4", "[grow]", "[][grow]");
        pane.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 3;");
        pane.add(new ToolBar(addBtn, removeBtn, upBtn, downBtn), "growx,wrap");
        // fills the remaining left column under the project settings
        pane.add(groupTable, "grow");
        return pane;
    }

    private MigPane groupForm() {
        sourceDirList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        dateToLabel.setText(t("date.to_label"));
        dateToLabel.setMinWidth(Region.USE_PREF_SIZE);

        enabledCheck.setOnAction(e -> {
            if (!suppressSync) {
                applyTextToGroup();
                groupTable.refresh();
                persistNow();
            }
        });
        for (TextField f : List.of(nameField, subDirField, includeSuffixField, excludeSuffixField, dirFragField)) {
            f.textProperty().addListener((obs, o, v) -> applyTextToGroup());
            f.focusedProperty().addListener((obs, was, focused) -> {
                if (!focused) {
                    persistNow();
                }
            });
        }

        Button addDir = new Button(t("dir.add"));
        addDir.setOnAction(e -> {
            RuleGroup g = groupTable.getSelectionModel().getSelectedItem();
            if (g == null) {
                warn(t("group.select_first"));
                return;
            }
            File dir = chooseDirectory(t("dir.choose_title"),
                    g.getSourceDirs().isEmpty() ? null : Paths.get(g.getSourceDirs().get(0)));
            if (dir != null && !g.getSourceDirs().contains(dir.getAbsolutePath())) {
                g.getSourceDirs().add(dir.getAbsolutePath());
                refreshSourceList(g);
                persistNow();
            }
        });
        Button removeDir = new Button(t("dir.remove"));
        removeDir.setOnAction(e -> {
            RuleGroup g = groupTable.getSelectionModel().getSelectedItem();
            if (g == null) {
                return;
            }
            List<String> picked = new ArrayList<>(sourceDirList.getSelectionModel().getSelectedItems());
            if (picked.isEmpty()) {
                return;
            }
            g.getSourceDirs().removeAll(picked);
            refreshSourceList(g);
            persistNow();
        });

        Button scriptEditBtn = new Button(t("script.edit"));
        scriptEditBtn.setMinWidth(Region.USE_PREF_SIZE);
        scriptEditBtn.setOnAction(e -> {
            RuleGroup g = groupTable.getSelectionModel().getSelectedItem();
            if (g == null) {
                warn(t("group.select_first"));
                return;
            }
            editFilterScript(g);
        });
        Button scriptClearBtn = new Button(t("script.clear"));
        scriptClearBtn.setMinWidth(Region.USE_PREF_SIZE);
        scriptClearBtn.setOnAction(e -> {
            RuleGroup g = groupTable.getSelectionModel().getSelectedItem();
            if (g == null || g.getFilterScript() == null || g.getFilterScript().isBlank()) {
                return;
            }
            g.setFilterScript(null);
            updateScriptState(g);
            persistNow();
        });

        dateCheck.setOnAction(e -> {
            fromPicker.setDisable(!dateCheck.isSelected());
            toPicker.setDisable(!dateCheck.isSelected());
            syncDateToGroup();
        });
        fromPicker.valueProperty().addListener((obs, o, v) -> syncDateToGroup());
        toPicker.valueProperty().addListener((obs, o, v) -> syncDateToGroup());

        recursiveCheck.setText(t("scan.recursive"));
        recursiveCheck.setOnAction(e -> {
            if (!suppressSync) {
                applyTextToGroup();
                persistNow();
            }
        });

        includeSuffixField.setPromptText(t("detail.include_prompt"));
        excludeSuffixField.setPromptText(t("detail.exclude_prompt"));
        dirFragField.setPromptText(t("detail.dir_frag_prompt"));
        nameField.setPromptText(t("detail.name_prompt"));
        subDirField.setPromptText(t("detail.subdir_prompt"));
        fromPicker.setPromptText(t("date.from_prompt"));
        toPicker.setPromptText(t("date.to_prompt"));

        HBox dirButtons = new HBox(6, addDir, removeDir);
        HBox scriptRow = new HBox(6, scriptEditBtn, scriptState, scriptClearBtn);
        scriptRow.setAlignment(Pos.CENTER_LEFT);
        HBox dateRow = new HBox(6, dateCheck, fromPicker, dateToLabel, toPicker);
        dateRow.setAlignment(Pos.CENTER_LEFT);

        // No border box here: its height never matched the left column, so the right
        // form sits unframed inside its scroll pane.
        MigPane groupBox = new MigPane("fillx,insets 8,gapx 8,gapy 6", "[pref!][grow]", "");
        groupBox.add(section(t("detail.enabled")), "");
        groupBox.add(enabledCheck, "wrap");
        groupBox.add(section(t("detail.name")), "");
        groupBox.add(nameField, "growx,wrap");
        groupBox.add(section(t("detail.subdir")), "");
        groupBox.add(subDirField, "growx,wrap");
        groupBox.add(section(t("detail.source_dirs")), "");
        groupBox.add(dirButtons, "wrap");
        groupBox.add(sourceDirList, "span 2,growx,h 110!,wrap");
        groupBox.add(section(t("detail.scan")), "");
        groupBox.add(recursiveCheck, "wrap");
        groupBox.add(section(t("detail.include")), "");
        groupBox.add(includeSuffixField, "growx,wrap");
        groupBox.add(section(t("detail.exclude")), "");
        groupBox.add(excludeSuffixField, "growx,wrap");
        groupBox.add(section(t("detail.dir_frag")), "");
        groupBox.add(dirFragField, "growx,wrap");
        groupBox.add(section(t("detail.script")), "");
        groupBox.add(scriptRow, "growx,wrap");
        groupBox.add(section(t("detail.date")), "");
        groupBox.add(dateRow, "growx,wrap");
        return groupBox;
    }

    private void editFilterScript(RuleGroup g) {
        TextArea area = new TextArea(g.getFilterScript() == null ? "" : g.getFilterScript());
        area.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        area.setPrefSize(600, 320);
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(stage);
        dlg.setTitle(t("script.title"));
        dlg.setHeaderText(t("script.header"));
        dlg.getDialogPane().setContent(area);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                String text = area.getText() == null ? "" : area.getText().trim();
                g.setFilterScript(text.isEmpty() ? null : text);
                updateScriptState(g);
                persistNow();
            }
        });
    }

    private void updateScriptState(RuleGroup g) {
        boolean on = g != null && g.getFilterScript() != null && !g.getFilterScript().isBlank();
        scriptState.setText(on ? t("script.set") : t("script.none"));
    }

    private MigPane projectSection() {
        conflictBox.setItems(FXCollections.observableArrayList(
                t("conflict.overwrite"), t("conflict.skip")));
        copyRadio.setText(t("out.copy"));
        zipRadio.setText(t("out.zip"));
        collectButton.setText(t("run.collect"));
        collectButton.getStyleClass().add("accent");

        outputDirField.setPromptText(t("output.prompt"));
        outputDirField.textProperty().addListener((obs, o, v) -> {
            if (current != null && !suppressSync) {
                current.setOutputDir(v);
            }
        });
        outputDirField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && !suppressSync) {
                persistNow();
            }
        });
        Button browseOut = new Button(t("browse"));
        browseOut.setOnAction(e -> {
            Path p = outputDirField.getText().isBlank() ? null : Paths.get(outputDirField.getText());
            File dir = chooseDirectory(t("out.choose_title"), p);
            if (dir != null) {
                outputDirField.setText(dir.getAbsolutePath());
            }
        });
        conflictBox.getSelectionModel().select(0);
        conflictBox.valueProperty().addListener((obs, o, v) -> {
            if (current != null && !suppressSync) {
                current.setConflictPolicy(conflictBox.getSelectionModel().getSelectedIndex() == 1
                        ? ConflictPolicy.SKIP : ConflictPolicy.OVERWRITE);
                persistNow();
            }
        });
        copyRadio.setToggleGroup(outputMode);
        zipRadio.setToggleGroup(outputMode);
        copyRadio.setSelected(true);

        collectButton.setOnAction(e -> startCollect());
        progress.setVisible(false);
        progress.setMaxWidth(140);

        Button genBtn = new Button("Gradle", gradleIcon());
        genBtn.setTooltip(new Tooltip(t("gradle.generate")));
        genBtn.setOnAction(e -> generateGradleProject());
        collectButton.setMinWidth(Region.USE_PREF_SIZE);
        genBtn.setMinWidth(Region.USE_PREF_SIZE);

        HBox outputRow = new HBox(6, outputDirField, browseOut);
        HBox.setHgrow(outputDirField, Priority.ALWAYS);
        outputRow.setAlignment(Pos.CENTER_LEFT);
        HBox modeRow = new HBox(10, copyRadio, zipRadio);
        HBox execRow = new HBox(8, section(t("detail.run")), collectButton, genBtn, progress);
        execRow.setAlignment(Pos.CENTER_LEFT);

        MigPane projectPane = new MigPane("fillx,insets 8,gapx 8,gapy 6", "[pref!][grow]", "");
        projectPane.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 3;");
        projectPane.add(section(t("detail.output")), "");
        projectPane.add(outputRow, "growx,wrap");
        projectPane.add(section(t("detail.conflict")), "");
        projectPane.add(conflictBox, "wrap");
        projectPane.add(section(t("detail.mode")), "");
        projectPane.add(modeRow, "wrap");
        projectPane.add(execRow, "span 2,growx");
        return projectPane;
    }

    private Label section(String text) {
        Label l = new Label(text);
        l.setMinWidth(84);
        return l;
    }

    /** Small outlined-box glyph (12px) used as the Gradle generate button icon. */
    private SVGPath gradleIcon() {
        SVGPath box = new SVGPath();
        box.setContent("M6 1 L11 3.6 L11 8.8 L6 11.4 L1 8.8 L1 3.6 Z "
                + "M1 3.6 L6 6.2 L11 3.6 M6 6.2 L6 11.4");
        box.setFill(null);
        box.setStroke(Color.web("#02303a"));
        box.setStrokeWidth(0.9);
        return box;
    }

    // -------------------------------------------------------------- date modes
    // No explicit mode control: the effective mode is derived from the checkbox
    // plus which of dateFrom/dateTo are filled (from=after, to=before, both=range).

    private void updateDateControls(RuleGroup g) {
        boolean on = g != null && g.getDateMode() != null;
        dateCheck.setSelected(on);
        fromPicker.setValue(g == null ? null : com.filesummer.core.FileFilters.parseDate(g.getDateFrom()));
        toPicker.setValue(g == null ? null : com.filesummer.core.FileFilters.parseDate(g.getDateTo()));
        fromPicker.setDisable(!on);
        toPicker.setDisable(!on);
    }

    private void syncDateToGroup() {
        if (suppressSync) {
            return;
        }
        RuleGroup g = groupTable.getSelectionModel().getSelectedItem();
        if (g == null) {
            return;
        }
        java.time.LocalDate from = fromPicker.getValue();
        java.time.LocalDate to = toPicker.getValue();
        String mode = null;
        if (dateCheck.isSelected()) {
            if (from != null && to != null) {
                mode = "RANGE";
            } else if (from != null) {
                mode = "AFTER";
            } else if (to != null) {
                mode = "BEFORE";
            }
        }
        g.setDateMode(mode);
        g.setDateFrom(from == null ? null : from.toString());
        g.setDateTo(to == null ? null : to.toString());
        persistNow();
    }

    // ----------------------------------------------------------------- actions

    private File chooseDirectory(String title, Path initial) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        if (initial != null) {
            Path dir = Files.isDirectory(initial) ? initial : initial.getParent();
            if (dir != null && Files.exists(dir)) {
                chooser.setInitialDirectory(dir.toFile());
            }
        }
        return chooser.showDialog(stage);
    }

    private void createProject() {
        TextInputDialog dlg = new TextInputDialog(t("project.new_default"));
        dlg.setTitle(t("project.new_title"));
        dlg.setHeaderText(t("project.new_header"));
        dlg.setContentText(t("project.new_content"));
        dlg.initOwner(stage);
        dlg.showAndWait().ifPresent(name -> {
            if (name.isBlank()) {
                warn(t("project.name_empty"));
                return;
            }
            ProjectConfig p = new ProjectConfig();
            p.setName(name.trim());
            p.getRules().add(p.newDefaultGroup());
            workspace.getProjects().add(p);
            workspace.setCurrentProjectIndex(workspace.getProjects().size() - 1);
            persistNow();
            refreshProjectBox();
            selectProject(p);
        });
    }

    private void deleteProject() {
        if (workspace.getProjects().size() <= 1) {
            warn(t("project.keep_one"));
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("project.delete_confirm", current.getName()), ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle(t("project.delete_title"));
        confirm.initOwner(stage);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        workspace.getProjects().remove(current);
        workspace.setCurrentProjectIndex(0);
        persistNow();
        refreshProjectBox();
        selectProject(workspace.getProjects().get(0));
    }

    private void generateGradleProject() {
        flushDetailToGroup();
        if (current.getOutputDir() == null || current.getOutputDir().isBlank()) {
            warn(t("gen.need_output"));
            return;
        }
        File base = chooseDirectory(t("gen.choose_title"), Paths.get(current.getOutputDir()).getParent());
        if (base == null) {
            return;
        }
        Path target = base.toPath().resolve(
                GradleScriptGenerator.sanitizeName(current.getName()) + "-collector");
        try {
            generator.generate(current, target);
            log(t("gen.ok_log", target));
            info(t("gen.info", target));
        } catch (Exception ex) {
            log(t("gen.fail_log", ex.getMessage()));
            error(t("gen.fail", ex.getMessage()));
        }
    }

    private void startCollect() {
        flushDetailToGroup();
        FileCollector.OutputMode mode = zipRadio.isSelected()
                ? FileCollector.OutputMode.ZIP : FileCollector.OutputMode.COPY;
        ProjectConfig snapshot = shallowCopyForRun(current);
        collectButton.setDisable(true);
        progress.setVisible(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        statusLabel.setText(t("status.collecting"));
        Task<CollectReport> task = new Task<>() {
            @Override
            protected CollectReport call() {
                return collector.collect(snapshot, mode);
            }
        };
        task.setOnSucceeded(e -> {
            collectButton.setDisable(false);
            progress.setVisible(false);
            renderReport(task.getValue(), mode);
        });
        task.setOnFailed(e -> {
            collectButton.setDisable(false);
            progress.setVisible(false);
            Throwable err = task.getException();
            statusLabel.setText(t("status.failed"));
            log(t("collect.exception_log", String.valueOf(err)));
            error(t("collect.exception", err.getMessage()));
        });
        new Thread(task, "file-summer-collect").start();
    }

    /** Detach the model so the background thread never races UI edits. */
    private ProjectConfig shallowCopyForRun(ProjectConfig p) {
        ProjectConfig c = new ProjectConfig();
        c.setName(p.getName());
        c.setOutputDir(p.getOutputDir());
        c.setConflictPolicy(p.getConflictPolicy());
        for (RuleGroup g : p.getRules()) {
            RuleGroup d = new RuleGroup();
            d.setName(g.getName());
            d.setEnabled(g.isEnabled());
            d.setRecursive(g.isRecursive());
            d.setSourceDirs(new ArrayList<>(g.getSourceDirs()));
            d.setSubDir(g.getSubDir());
            d.setIncludeSuffixes(new ArrayList<>(g.getIncludeSuffixes()));
            d.setExcludeSuffixes(new ArrayList<>(g.getExcludeSuffixes()));
            d.setExcludeDirFragments(new ArrayList<>(g.getExcludeDirFragments()));
            d.setDateMode(g.getDateMode());
            d.setDateFrom(g.getDateFrom());
            d.setDateTo(g.getDateTo());
            d.setFilterScript(g.getFilterScript());
            c.getRules().add(d);
        }
        return c;
    }

    private void renderReport(CollectReport report, FileCollector.OutputMode mode) {
        StringBuilder sb = new StringBuilder();
        sb.append(mode == FileCollector.OutputMode.ZIP
                ? t("rep.zip", report.getOutputLocation())
                : t("rep.dir", report.getOutputLocation())).append('\n');
        for (CollectReport.GroupStat s : report.getStats()) {
            sb.append(t("rep.group", s.group, s.matched, s.written));
            if (s.skippedByConflict > 0) {
                sb.append(t("rep.conflict", s.skippedByConflict));
            }
            sb.append('\n');
        }
        for (FileIssue i : report.getIssues()) {
            sb.append("  ").append(i).append('\n');
        }
        log(sb.toString());
        boolean hasErrors = report.hasErrors();
        if (report.totalMatched() == 0) {
            statusLabel.setText(t("status.no_match"));
            warn(t("rep.no_match_warn"));
        } else if (hasErrors) {
            statusLabel.setText(t("status.done_errors"));
            error(t("rep.errors_header", report.getIssues().stream()
                    .filter(i -> i.getLevel() == FileIssue.Level.ERROR)
                    .map(FileIssue::toString).collect(Collectors.joining("\n"))));
        } else {
            statusLabel.setText(t("status.done"));
        }
    }

    // ------------------------------------------------------------ model <-> UI

    private void refreshProjectBox() {
        suppressSync = true;
        projectBox.getItems().setAll(workspace.getProjects());
        suppressSync = false;
    }

    private void selectProject(ProjectConfig p) {
        suppressSync = true;
        current = p;
        projectBox.getSelectionModel().select(p);
        groups.setAll(p.getRules());
        outputDirField.setText(p.getOutputDir());
        conflictBox.getSelectionModel().select(p.getConflictPolicy() == ConflictPolicy.SKIP ? 1 : 0);
        groupTable.refresh();
        RuleGroup first = groups.isEmpty() ? null : groups.get(0);
        groupTable.getSelectionModel().select(first);
        loadDetailFromGroup(first);
        suppressSync = false;
    }

    private void syncModelFromGroups() {
        current.setRules(new ArrayList<>(groups));
    }

    private void moveSelected(int delta) {
        int i = groupTable.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= groups.size()) {
            return;
        }
        RuleGroup tmp = groups.get(i);
        groups.set(i, groups.get(j));
        groups.set(j, tmp);
        groupTable.getSelectionModel().select(j);
        syncModelFromGroups();
        persistNow();
    }

    private void loadDetailFromGroup(RuleGroup g) {
        suppressSync = true;
        updateScriptState(g);
        enabledCheck.setSelected(g != null && g.isEnabled());
        recursiveCheck.setSelected(g == null || g.isRecursive());
        nameField.setText(g == null ? "" : g.getName());
        subDirField.setText(g == null ? "" : g.getSubDir());
        if (g == null) {
            sourceDirList.getItems().clear();
            includeSuffixField.clear();
            excludeSuffixField.clear();
            dirFragField.clear();
            updateDateControls(null);
            suppressSync = false;
            return;
        }
        refreshSourceList(g);
        includeSuffixField.setText(String.join(",", g.getIncludeSuffixes()));
        excludeSuffixField.setText(String.join(",", g.getExcludeSuffixes()));
        dirFragField.setText(String.join(",", g.getExcludeDirFragments()));
        updateDateControls(g);
        suppressSync = false;
    }

    private void refreshSourceList(RuleGroup g) {
        sourceDirList.getItems().setAll(g.getSourceDirs());
    }

    private void applyTextToGroup() {
        if (suppressSync) {
            return;
        }
        flushDetailToGroup();
    }

    private void flushDetailToGroup() {
        RuleGroup g = groupTable.getSelectionModel().getSelectedItem();
        if (g == null || suppressSync) {
            return;
        }
        String nm = nameField.getText() == null ? "" : nameField.getText().trim();
        if (!nm.isEmpty() && !nm.equals(g.getName())) {
            g.setName(nm);
        }
        String sub = subDirField.getText() == null ? "" : subDirField.getText().trim();
        if (!sub.equals(g.getSubDir())) {
            g.setSubDir(sub);
        }
        if (enabledCheck.isSelected() != g.isEnabled()) {
            g.setEnabled(enabledCheck.isSelected());
        }
        if (recursiveCheck.isSelected() != g.isRecursive()) {
            g.setRecursive(recursiveCheck.isSelected());
        }
        List<String> inc = parseList(includeSuffixField.getText());
        List<String> exc = parseList(excludeSuffixField.getText());
        List<String> frags = parseList(dirFragField.getText());
        if (!g.getIncludeSuffixes().equals(inc)) {
            g.setIncludeSuffixes(inc);
        }
        if (!g.getExcludeSuffixes().equals(exc)) {
            g.setExcludeSuffixes(exc);
        }
        if (!g.getExcludeDirFragments().equals(frags)) {
            g.setExcludeDirFragments(frags);
        }
    }

    private List<String> parseList(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(text.split("[,;，；\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void persistNow() {
        if (current == null || suppressSync) {
            return;
        }
        flushDetailToGroup();
        syncModelFromGroups();
        workspace.setCurrentProjectIndex(Math.max(0, workspace.getProjects().indexOf(current)));
        try {
            store.save(workspace);
        } catch (Exception e) {
            log(t("config.save_failed", e.getMessage()));
            error(t("config.save_failed", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ misc

    private void log(String msg) {
        Platform.runLater(() -> logArea.appendText(
                "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + msg + "\n"));
    }

    private void info(String msg) {
        dialog(Alert.AlertType.INFORMATION, t("dlg.info"), msg);
    }

    private void warn(String msg) {
        dialog(Alert.AlertType.WARNING, t("dlg.info"), msg);
    }

    private void error(String msg) {
        dialog(Alert.AlertType.ERROR, t("dlg.error"), msg);
    }

    private void dialog(Alert.AlertType type, String title, String msg) {
        Alert a = new Alert(type, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.initOwner(stage);
        a.showAndWait();
    }
}
