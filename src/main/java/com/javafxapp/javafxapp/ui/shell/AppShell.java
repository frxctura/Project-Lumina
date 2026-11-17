package com.javafxapp.javafxapp.ui.shell;

import com.javafxapp.javafxapp.ui.components.Toaster;

import javafx.beans.value.ObservableValue;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;

public interface AppShell {

    enum View { MAIN, LIBRARY, HISTORY, SETTINGS }

    void show(View view);

    void openGame(Path dir);

    void enqueueBatch(List<Path> dirs);

    void setStatus(String text);

    void bindStatus(ObservableValue<String> text);

    void unbindStatus();

    void setNavigationLocked(boolean locked);

    void toast(Toaster.Kind kind, String text);

    void toast(Toaster.Kind kind, String text, String actionText, Runnable action);

    Window window();
}
