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

    /**
     * Opens the LitRoute window, as {@link LitRouteApp#main}, from a plain classpath (an IDE's Run, or
     * {@code exec:java -Dexec.mainClass=com.litroute.ui.Launcher}).
     *
     * @param args passed on to JavaFX
     */
    public static void main(String[] args) {
        LitRouteApp.main(args);
    }
}
