package com.javafxapp.javafxapp.common;

@FunctionalInterface
public interface Progress {

    Progress NONE = (done, total) -> {};

    void update(long done, long total);
}
