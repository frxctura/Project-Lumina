package com.javafxapp.javafxapp.ui.shell;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

// every dialog in the app: title, message, optional list and extras, then a footer with the buttons.
// layout follows the Windows dialog guidance: left-aligned text, 24px padding, 12px between blocks,
// the "do it" button first and the safe one on the right, both the same width
public final class Prompt {

    private static final double PADDING = 24, BUTTON_GAP = 8;
    private static final int LIST_SCROLL_AFTER = 8;

    private final Window owner;
    private String title, message, note;
    private List<String> details = List.of();
    private final List<Node> extras = new ArrayList<>();
    private String confirm = "OK", cancel = "Cancel";
    private boolean destructive, singleButton;
    private double width = 460;
    private Alert built;

    Prompt(Window owner) {
        this.owner = owner;
    }

    public Prompt title(String text)      { title = text; return this; }
    public Prompt message(String text)    { message = text; return this; }
    public Prompt note(String text)       { note = text; return this; }
    public Prompt details(List<String> lines) { details = lines; return this; }
    public Prompt extra(Node... nodes)    { extras.addAll(List.of(nodes)); return this; }
    public Prompt confirm(String label)   { confirm = label; return this; }
    public Prompt cancel(String label)    { cancel = label; return this; }
    public Prompt destructive()           { destructive = true; return this; }
    public Prompt width(double w)         { width = w; return this; }

    // a dialog with just one button, for telling rather than asking
    public void inform(String buttonLabel) {
        confirm = buttonLabel;
        singleButton = true;
        showDimmed();
    }

    public boolean ask() {
        return showDimmed().filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE).isPresent();
    }

    private java.util.Optional<ButtonType> showDimmed() {
        Alert alert = build();
        Runnable undim = Ui.dim(owner);
        try {
            return alert.showAndWait();
        } finally {
            undim.run();
        }
    }

    // for dialogs that need to touch the confirm button before showing (e.g. disable it)
    public Button confirmButton() {
        Alert alert = build();
        return (Button) alert.getDialogPane().lookupButton(alert.getButtonTypes().get(alert.getButtonTypes().size() - 1));
    }

    private Alert build() {
        if (built != null) return built;
        ButtonType yes = new ButtonType(confirm, ButtonBar.ButtonData.OK_DONE);
        Alert alert = singleButton
                ? new Alert(Alert.AlertType.NONE, null, yes)
                : new Alert(Alert.AlertType.NONE, null, new ButtonType(cancel, ButtonBar.ButtonData.CANCEL_CLOSE), yes);
        Ui.styled(alert, owner);
        DialogPane pane = alert.getDialogPane();

        VBox content = new VBox(12);
        content.getStyleClass().add("prompt");
        if (title != null) content.getChildren().add(text(title, "prompt-title"));
        if (message != null) content.getChildren().add(text(message, "prompt-message"));
        if (!details.isEmpty()) content.getChildren().add(detailList());
        if (note != null) content.getChildren().add(text(note, "prompt-note"));
        content.getChildren().addAll(extras);
        pane.setContent(content);
        Ui.setDialogWidth(pane, width);

        // buttons share the footer evenly, like Windows dialogs
        List<ButtonType> types = alert.getButtonTypes();
        double each = (width - 2 * PADDING - BUTTON_GAP * (types.size() - 1)) / types.size();
        for (ButtonType type : types) {
            Button button = (Button) pane.lookupButton(type);
            ButtonBar.setButtonUniformSize(button, false);
            button.setPrefWidth(each);
            if (destructive && type == yes) button.getStyleClass().add("destructive");
        }
        built = alert;
        return alert;
    }

    private Node detailList() {
        VBox lines = new VBox(4);
        lines.getStyleClass().add("prompt-list");
        for (String line : details) lines.getChildren().add(text(line, "prompt-list-line"));
        if (details.size() <= LIST_SCROLL_AFTER) return lines;
        ScrollPane scroll = new ScrollPane(lines);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(LIST_SCROLL_AFTER * 21);
        scroll.getStyleClass().add("prompt-list-scroll");
        return scroll;
    }

    private static Label text(String value, String style) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add(style);
        return label;
    }
}
