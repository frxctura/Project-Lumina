package com.javafxapp.javafxapp.ui.shell;

public interface Page {

    void attach(AppShell shell);

    default void onShow() {}

    default void onClose() {}
}
