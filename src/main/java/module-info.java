module com.xebisco.yieldfx {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires YieldEngine;
    opens com.xebisco.yieldfx to javafx.graphics;
    exports com.xebisco.yieldfx;
}