package com.lipi.tests;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The whole test framework for this project, in one file.
 *
 * <p>Lipi declares no dependencies at all - {@code <dependencies/>} in its POM
 * is empty on purpose, because Swing ships with the JDK. Pulling in JUnit to
 * test it would make the build download an artefact for the first time in its
 * life, so the suite supplies its own assertions instead. They are the four
 * that the tests actually need.</p>
 *
 * <p>A failed assertion throws rather than returning a flag, so a test method
 * stops at its first failure and the rest of the file still runs. {@link
 * Runner} catches it and records which check failed and what it saw.</p>
 */
public final class Harness {

    private Harness() {}

    /** Thrown by a failing assertion. Carries only a message; the runner adds the location. */
    public static class Failure extends RuntimeException {
        public Failure(String message) { super(message); }
    }

    public static void check(boolean condition, String what) {
        if (!condition) throw new Failure(what);
    }

    public static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new Failure(what + "\n      expected: " + show(expected) + "\n      actual:   " + show(actual));
        }
    }

    public static void notEqual(Object unwanted, Object actual, String what) {
        if (Objects.equals(unwanted, actual)) {
            throw new Failure(what + "\n      both were: " + show(actual));
        }
    }

    /** Asserts that {@code body} throws something. Returns what it threw. */
    public static Throwable throwsSomething(ThrowingRunnable body, String what) {
        try {
            body.run();
        } catch (Throwable t) {
            return t;
        }
        throw new Failure(what + "\n      expected a throw, got a normal return");
    }

    public interface ThrowingRunnable { void run() throws Exception; }

    private static String show(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + s + "\"";
        if (value instanceof Object[] a) return Arrays.deepToString(a);
        return String.valueOf(value);
    }

    // ── Scratch directories ────────────────────────────────────────────────

    private static final List<Path> SCRATCH = new ArrayList<>();

    /**
     * A fresh empty directory that is deleted when the suite ends. Every test
     * that touches the filesystem gets its own, so nothing a test writes can
     * be seen by another one - several of these tests are about what is on
     * disk after an operation, and a shared directory would let one test's
     * leftovers answer another test's question.
     */
    public static Path scratchDir(String name) {
        try {
            Path dir = Files.createTempDirectory("lipi_test_" + name + "_");
            SCRATCH.add(dir);
            return dir;
        } catch (IOException e) {
            throw new Failure("could not create a scratch directory: " + e);
        }
    }

    /** Removes every scratch directory this run created. Best effort. */
    public static void cleanUpScratch() {
        for (Path dir : SCRATCH) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
        SCRATCH.clear();
    }

    public static File write(Path dir, String name, byte[] bytes) {
        try {
            Path p = dir.resolve(name);
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
            return p.toFile();
        } catch (IOException e) {
            throw new Failure("could not write " + name + ": " + e);
        }
    }

    public static byte[] read(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new Failure("could not read " + file + ": " + e);
        }
    }
}
