package com.javafxapp.javafxapp.common;

@FunctionalInterface
public interface StatusSink {

    StatusSink NONE = (message, error) -> {};

    void accept(String message, boolean error);

    default void info(String message) {
        Log.info(message);
        accept(message, false);
    }

    default void error(String message) {
        Log.error(message);
        accept(message, true);
    }

    default void error(String message, Throwable t) {
        Log.error(message, t);
        accept(message + ": " + Errors.describe(t), true);
    }
}
