package com.lipi.tests;

import com.lipi.util.WindowsFontRegistry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static com.lipi.tests.Harness.check;
import static com.lipi.tests.Harness.equal;
import static com.lipi.tests.Harness.write;

/**
 * The list of fonts whose bytes are still on disk but which are gone as far as
 * the user is concerned.
 *
 * <p>This exists because Windows will not always let a font file be deleted:
 * Font Driver Host holds it open and only a restart releases it. Removal
 * therefore unregisters the font and queues the file for deletion at the next
 * boot, and until that boot happens the file is still there. Without this
 * list the next directory scan sees the bytes, concludes the font is
 * installed, and puts it back in the table as Installed - a font the user
 * removed, that Explorer agrees is gone, reappearing in Lipi alone.</p>
 */
public final class PendingDeleteTest {

    private PendingDeleteTest() {}

    public static void run(Runner r) {
        r.group("WindowsFontRegistry - the pending-delete list");

        Path dir = Harness.scratchDir("pending");
        Path listFile = dir.resolve("lipi-pending-delete.txt");

        File fontA = write(dir, "a.ttf", new byte[]{1, 2, 3});
        File fontB = write(dir, "b.ttf", new byte[]{4, 5, 6});

        r.test("nothing is pending before anything is recorded", () -> {
            WindowsFontRegistry.setPendingDeleteFile(listFile);
            equal(0, WindowsFontRegistry.loadPendingDeletes().size(), "a fresh list is empty");
        });

        r.test("a recorded font comes back in the list", () -> {
            WindowsFontRegistry.recordPendingDelete(fontA);
            Set<String> pending = WindowsFontRegistry.loadPendingDeletes();
            check(pending.contains(fontA.getAbsolutePath().toLowerCase()),
                    "the path is stored lower-cased, which is how isFontInstalled looks it up");
        });

        r.test("recording the same font twice does not duplicate it", () -> {
            WindowsFontRegistry.recordPendingDelete(fontA);
            equal(1, WindowsFontRegistry.loadPendingDeletes().size(),
                    "removal can be attempted more than once in a session");
        });

        r.test("a second font joins the first", () -> {
            WindowsFontRegistry.recordPendingDelete(fontB);
            equal(2, WindowsFontRegistry.loadPendingDeletes().size(), "both are queued");
        });

        r.test("the list survives being written and read again", () -> {
            check(Files.exists(listFile), "the list is persisted, not held in memory");
            String onDisk = Files.readString(listFile);
            check(onDisk.contains("a.ttf") && onDisk.contains("b.ttf"),
                    "both paths are in the file, so the next session sees them");
        });

        r.test("clearing one font leaves the other", () -> {
            WindowsFontRegistry.clearPendingDelete(fontA);
            Set<String> pending = WindowsFontRegistry.loadPendingDeletes();
            equal(1, pending.size(), "one cleared, one left");
            check(pending.contains(fontB.getAbsolutePath().toLowerCase()),
                    "the wrong one must not be cleared");
        });

        r.test("clearing a font that was never queued is harmless", () -> {
            WindowsFontRegistry.clearPendingDelete(new File(dir.toFile(), "never.ttf"));
            equal(1, WindowsFontRegistry.loadPendingDeletes().size(),
                    "reinstalling a font that was not pending is the common case");
        });

        r.test("an entry whose file is gone is pruned at startup", () -> {
            // What a real reboot leaves behind: Windows deleted the file, so
            // the entry has done its job and must not keep the next session
            // reporting a font that no longer exists as merely pending.
            check(fontB.delete(), "could not delete the fixture");
            WindowsFontRegistry.setPendingDeleteFile(listFile);
            equal(0, WindowsFontRegistry.loadPendingDeletes().size(),
                    "the reboot happened; the entry is stale");
        });

        r.test("an entry whose file is still there survives pruning", () -> {
            WindowsFontRegistry.recordPendingDelete(fontA);
            WindowsFontRegistry.setPendingDeleteFile(listFile);
            equal(1, WindowsFontRegistry.loadPendingDeletes().size(),
                    "no reboot yet, so the font is still hidden from the table");
        });

        r.test("blank lines in the file are ignored", () -> {
            Files.writeString(listFile, "\n\n" + fontA.getAbsolutePath().toLowerCase() + "\n   \n");
            WindowsFontRegistry.setPendingDeleteFile(listFile);
            equal(1, WindowsFontRegistry.loadPendingDeletes().size(),
                    "an editor-added trailing newline is not a font");
        });

        r.test("with no list configured nothing is pending and nothing throws", () -> {
            WindowsFontRegistry.setPendingDeleteFile(null);
            equal(0, WindowsFontRegistry.loadPendingDeletes().size(),
                    "on macOS and Linux this path is never configured");
            WindowsFontRegistry.recordPendingDelete(fontA);
            WindowsFontRegistry.clearPendingDelete(fontA);
        });

        // Leave the static field pointing somewhere harmless for later suites.
        r.test("the list can be pointed at a new file", () -> {
            Path second = dir.resolve("second-list.txt");
            WindowsFontRegistry.setPendingDeleteFile(second);
            equal(0, WindowsFontRegistry.loadPendingDeletes().size(), "a different session, a different file");
        });

        // -- The diagnostic log --------------------------------------------------

        r.group("WindowsFontRegistry - the diagnostic log");

        Path logDir = Harness.scratchDir("logs");
        Path log = logDir.resolve("lipi-debug.log");

        r.test("operations are written to the log file", () -> {
            System.clearProperty("lipi.log");
            WindowsFontRegistry.setLogFile(log);
            WindowsFontRegistry.log("a line worth keeping");
            check(Files.readString(log).contains("a line worth keeping"),
                    "under a jpackage launcher this file is the only diagnostic there is");
        });

        r.test("each line is timestamped", () -> {
            check(Files.readString(log).matches("(?s).*\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\].*"),
                    "comparing a working run with a failing one needs times");
        });

        r.test("the log stops growing once it passes its cap", () -> {
            // Left uncapped this file grows for the life of the installation:
            // a refresh of a 300-font library writes a line per font and
            // nothing ever removes any of it.
            Files.writeString(log, "x".repeat(1_100_000));
            WindowsFontRegistry.log("the line that triggers the rotation");

            check(Files.size(log) < 1_048_576L,
                    "the current log was rotated away, not appended to; size is "
                            + Files.size(log));
            check(Files.exists(logDir.resolve("lipi-debug.log.1")),
                    "and the previous one is kept, because the interesting run is "
                            + "often the one before this");
            check(Files.readString(log).contains("the line that triggers the rotation"),
                    "the line that caused the rotation is still recorded");
        });

        r.test("only one previous log is kept", () -> {
            Files.writeString(log, "y".repeat(1_100_000));
            WindowsFontRegistry.log("second rotation");
            equal(2L, Files.list(logDir).filter(p -> p.getFileName().toString().startsWith("lipi-debug.log")).count(),
                    "two files, not a growing pile of them");
        });

        r.test("logging can be turned off", () -> {
            Path off = logDir.resolve("off.log");
            WindowsFontRegistry.setLogFile(off);
            System.setProperty("lipi.log", "off");
            try {
                WindowsFontRegistry.log("this must not be written");
                check(!Files.exists(off) || !Files.readString(off).contains("this must not be written"),
                        "a user who does not want a record of every font they install "
                                + "kept on disk needs a way to say so");
            } finally {
                System.clearProperty("lipi.log");
            }
        });

        r.test("turning logging off does not silence stderr", () -> {
            // The file is instrumentation the user can decline; stderr is how
            // a developer running from an IDE sees anything at all.
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            java.io.PrintStream previous = System.err;
            System.setErr(new java.io.PrintStream(sink, true));
            try {
                System.setProperty("lipi.log", "off");
                WindowsFontRegistry.log("still on stderr");
            } finally {
                System.setProperty("lipi.log", "off");
                System.clearProperty("lipi.log");
                System.setErr(previous);
            }
            check(sink.toString().contains("still on stderr"), "stderr is unaffected");
        });

        r.test("with no log file configured nothing is written and nothing throws", () -> {
            WindowsFontRegistry.setLogFile(logDir.resolve("unused.log"));
            WindowsFontRegistry.log("harmless");
        });
    }
}
