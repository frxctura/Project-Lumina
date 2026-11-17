module com.javafxapp.javafxapp {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires java.net.http;
    requires java.prefs;
    requires jdk.crypto.ec;
    requires org.slf4j;
    requires org.apache.commons.compress;
    requires org.tukaani.xz;

    exports com.javafxapp.javafxapp;
    opens com.javafxapp.javafxapp.ui.shell to javafx.fxml;
    opens com.javafxapp.javafxapp.ui.pages to javafx.fxml;
    opens com.javafxapp.javafxapp.ui.components to javafx.fxml;
}
