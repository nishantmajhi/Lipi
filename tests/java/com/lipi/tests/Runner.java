package com.lipi.tests;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every test in the suite and prints one line per check.
 *
 * <p>Test classes register their checks by calling {@link #test} from a
 * {@code static void run(Runner)} method; this class names them explicitly
 * below rather than scanning the classpath, so the set of suites that run is
 * visible in one place and a file that fails to compile is a loud error
 * instead of a silently smaller run.</p>
 *
 * <p>Exit code is 0 only when every check passed. The count is printed last
 * and is the number recorded in {@code review.md}: a suite that quietly runs
 * fewer checks than it used to is the failure mode this guards against.</p>
 */
public final class Runner {

    private final List<String> failures = new ArrayList<>();
    private int passed;
    private String group = "";

    public void group(String name) {
        this.group = name;
        System.out.println();
        System.out.println("-- " + name + " " + "-".repeat(Math.max(0, 68 - name.length())));
    }

    public void test(String name, Harness.ThrowingRunnable body) {
        try {
            body.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Harness.Failure f) {
            failures.add(group + " / " + name + "\n      " + f.getMessage());
            System.out.println("  FAIL " + name + "\n      " + f.getMessage());
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder(t.toString());
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith("com.lipi")) {
                    sb.append("\n        at ").append(e);
                }
            }
            failures.add(group + " / " + name + "\n      threw " + sb);
            System.out.println("  FAIL " + name + "\n      threw " + sb);
        }
    }

    /**
     * Records a check that cannot run in this environment. Counted separately
     * so a skip can never be mistaken for a pass - the mountain_cipher lesson,
     * where a suite reported nine passes against binaries that never started.
     */
    public void skip(String name, String why) {
        System.out.println("  skip " + name + " (" + why + ")");
    }

    public static void main(String[] args) {
        // WindowsFontRegistry.log() writes every operation to System.err, by
        // design - under a jpackage launcher there is no console and the log
        // file is the only diagnostic there is. Here it would bury the results,
        // so it is held aside and printed only if something failed, when it is
        // exactly what you want to read.
        java.io.PrintStream realErr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));

        Runner r = new Runner();
        try {
            FontNameReaderTest.run(r);
            RegistrySnapshotTest.run(r);
            PendingDeleteTest.run(r);
            FontExportServiceTest.run(r);
            FontServiceTest.run(r);
            SystemPathTest.run(r);
        } finally {
            Harness.cleanUpScratch();
            System.setErr(realErr);
        }

        System.out.println();
        if (r.failures.isEmpty()) {
            System.out.println("All " + r.passed + " checks passed.");
            System.exit(0);
        } else {
            System.out.println(r.failures.size() + " of " + (r.passed + r.failures.size())
                    + " checks failed:");
            for (String f : r.failures) System.out.println("  - " + f);
            String diagnostics = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (!diagnostics.isBlank()) {
                System.out.println();
                System.out.println("-- application log (System.err) " + "-".repeat(38));
                System.out.println(diagnostics);
            }
            System.exit(1);
        }
    }
}
