package com.litroute.ui;

/**
 * Starts {@link LitRouteApp} from a plain classpath (an IDE's Run, or {@code exec:java}): the JVM refuses
 * to launch a main class that extends {@code Application} unless JavaFX is on the module path, but a
 * class that merely calls {@code launch} is fine.
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=com.litroute.ui.Launcher
 * </pre>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        LitRouteApp.main(args);
    }
}
