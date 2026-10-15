# Review — लिपि (Lipi) Nepali Font Manager

First reviewed 2026-09-04. **Remediated 2026-09-25 on branch
`cleanup/2026-09-25`.** This file is now a record of that pass rather than a
to-do list: what was wrong, what was fixed, what was deliberately left, and
what the next person should know.

Tests: **98 checks**, `mvn test` or `powershell tests\run.ps1`.
`tests\run.ps1 -Original` runs the same suite against the first commit, where
**22 of the 98 fail**.

---

## Corrections to the first review

Two of its findings were wrong, and one of its recommendations was already
implemented. They are here first because a review that sends the next reader
to fix the wrong thing is worse than one that misses something.

**Issue 1 is wrong: `fc-cache` was already being invoked.** The review said
"Nothing in the code invokes it" and that a Linux install would therefore
silently fail to appear. `SystemUtil.refreshFontCache()` existed in the
original and ran `fc-cache -f` on Linux, and `FontService` called it from three
places — after an install, after a reboot-scheduled removal, and after an
ordinary removal (original lines 207, 395 and 407).

The real defects were narrower and none of them is the one described. The
process was started and never waited for, so an install could report success
before the cache had been rebuilt; the exit code was discarded, so a `fc-cache`
that failed was indistinguishable from one that worked; and no directory was
passed, so every font path on the system was rescanned rather than the one that
had changed. All three are fixed. The headline claim — that a Linux install is
invisible because nothing refreshes the cache — was not true.

**Issue 5 is half wrong: the temp file was already deleted in a `finally`.**
The review asked for "delete the temp file explicitly in a `finally` block
rather than relying only on `deleteOnExit`". `runPowerShellScript` had exactly
that in the original: `finally { if (tempPs1 != null) tempPs1.delete(); }`. The
other half of the item — say in a comment why `-ExecutionPolicy Bypass` is
needed — was a fair point and is done.

**The `ConvertIcon` question is answered: it is not in the shipped JAR.** The
review asked someone to check. `jar tf target/lipi-1.0.0.jar` lists only classes
under `com/lipi/`, and no `com/lipi/build/`. The `compile-build-tools`
execution sends it to `target/build-classes`, which nothing packages. It was
already right.

---

## What the first review missed

Both of these are in `WindowsFontRegistry`, which the review called "the
strongest engineering in this project" — and it is. Both are the same
misunderstanding, of a Windows detail the class is otherwise unusually careful
about, seen from opposite directions.

### P1 — an unelevated install writes a registry entry that points nowhere

`register()` passed `fontFile.getName()` as the value's data for both registry
hives. That is correct for `HKLM`, where a bare file name is resolved against
`%WINDIR%\Fonts`. `HKCU` has no such base directory: the per-user store is
under `%LOCALAPPDATA%\Microsoft\Windows\Fonts`, the registry has no way to know
that, and a bare name there sends Windows looking in `%WINDIR%\Fonts` for a
file that is not in it.

So a per-user install — which is what every user without Administrator rights
gets, and which the code goes out of its way to support — copied the font
correctly, wrote a registry entry correctly as far as `reg add` was concerned,
reported success, and produced a font that does not load. That is precisely the
failure mode the class comment opens by describing, reintroduced in the branch
added to avoid needing elevation.

The evidence is the reference implementation. Every one of the eight per-user
entries Windows itself had written on the machine this was found on is an
absolute path:

```
Kantipur Regular (TrueType)  REG_SZ  C:\Users\…\Microsoft\Windows\Fonts\Kantipur Regular.ttf
Limelight Regular (TrueType) REG_SZ  C:\Users\…\Microsoft\Windows\Fonts\Limelight-Regular.ttf
```

while every `HKLM` entry is bare:

```
Arial (TrueType)  REG_SZ  arial.ttf
```

Not one entry in either hive contradicts the rule. Fixed by
`registryDataFor(File)`, which returns the absolute path for the per-user store
and the bare name otherwise.

### P1 — the per-user hive was read, parsed, and then unreachable

The same fact from the reading side. `queryAllRegisteredFonts()` keyed its
snapshot on the registry data exactly as stored, so every `HKCU` entry went in
under a full path like
`c:\users\…\fonts\kantipur regular.ttf`. Every caller looks a font up by its
bare file name. Nothing ever matched.

The consequence is not that the wrong answer comes back — it is that the
mechanism the class exists for is inert for per-user fonts.
`SystemUtil.getSystemFontPath()` documents at length why the registry hive is
authoritative and why file existence is not ("the shell namespace creates
compatibility links that make per-user fonts appear at the system path too"),
then falls through to the existence check for every per-user font, every time.

The method's own javadoc asserted the premise that made it wrong: "registry
data for font values is always just the bare filename". Fixed by indexing each
entry under the last path component as well as the raw value, in
`indexRegQueryOutput`, which is also now separate from the `reg.exe` call so it
can be tested against captured output from both hives.

`tests/java/com/lipi/tests/RegistrySnapshotTest.java` carries the original
six-line parsing loop transcribed, and asserts the defect on it, because the
original had no seam to call.

### P2 — removing a font that is not installed was reported as a failure

`removeFonts()` sent `RemoveResult.NOT_FOUND` to the `default` arm and counted
it as a failure. Selecting every row and clicking Remove is the ordinary way to
use this window, and most rows in a 303-font library are usually not installed,
so the normal outcome was an error dialog:

> Failed to remove 297 font(s). See lipi-debug.log for details.

after a removal in which nothing at all went wrong. The rows were also left
saying Installed, which is what the user had pressed the button about. Both
fixed: `NOT_FOUND` now corrects the row and reports nothing.

### P2 — a failed font left a zero-byte entry in an exported ZIP

`exportAsZip` called `putNextEntry` before opening the source file. A source
that could not be read threw into the catch block with the entry already
started and never closed, so the next `putNextEntry` closed it, and the archive
came out carrying a zero-byte `.ttf` for the font the dialog had just reported
as failed. Windows will not install one of those.

Fixed by reading the font fully before starting its entry, which also makes
each entry atomic: a read that dies half way through now writes nothing rather
than a truncated entry.

### P2 — a partial import reported that no fonts were found

`extractFromZip` returned `new ExportResult(0, 1)` from its catch block,
discarding the count of everything it had already written. `MainWindow` shows
"No Fonts Found — no .ttf or .otf font files were found in the ZIP archive"
whenever that count is zero, and only refreshes the table when it is positive.

So a truncated archive — a download that stopped — wrote fonts to disk, told
the user none had been found, and then hid them until the next launch. Fixed:
the count is what was really written. A font cut short is now deleted rather
than left in the library, because a partial `.ttf` installs and then renders
nothing.

### P3 — a file that is not a ZIP was reported as a ZIP containing no fonts

`ZipInputStream` over arbitrary bytes yields no entries and does not complain,
which is indistinguishable from an archive full of things that are not fonts.
Picking the wrong file in the chooser produced a message about fonts not being
found in it. Now the signature is checked.

### P3 — `setPendingDeleteFile(null)` threw

Three of the four pending-delete methods guarded against no list being
configured. `setPendingDeleteFile` did not, and pruning a null path threw
`NullPointerException` out of it. Not reachable from `Lipi.main`, which always
passes a path, but it is the state every non-Windows platform is in.

---

## What was fixed from the original checklist

- [x] **P2** `fc-cache` — see the correction above. It was being called; it is
      now waited for, its exit code is checked, and it is pointed at the
      directory that changed.
- [x] **P2** macOS installs targeted `/Library/Fonts` unconditionally, which an
      ordinary user cannot write to. Now resolved the same way as Windows: an
      existing installation in either location wins, otherwise
      `~/Library/Fonts`. "Open Fonts Folder" follows it, or the menu item would
      have opened an empty directory and invited the user to conclude nothing
      had installed.
- [x] **P2** The README's cross-platform claim. Rewritten from first
      principles; there is now a table of what works on which platform, and
      "best effort on macOS and Linux" is said plainly.
- [x] **P3** `lipi-debug.log` grew without bound. Rotates at 1 MB keeping one
      previous file, and can be turned off with `LIPI_LOG=off` or
      `-Dlipi.log=off`. It stays on by default, because under the jpackage
      launcher there is no console and it is the only diagnostic there is.
- [x] **P3** `ConvertIcon` — confirmed excluded, see the correction above.
- [x] **P3** `artifactId` was `font-manager` while everything else was `lipi`.
      Now `lipi`; the JAR is `lipi-1.0.0.jar`. The release workflow globs
      `target\*.jar` so it was unaffected.
- [x] **P3** PowerShell temp file — already handled, see the correction. The
      comment explaining `-ExecutionPolicy Bypass` is added.

Also fixed, not on the original list:

- The POM declared `maven-compiler-plugin` twice. Maven warns that this
  "threatens the stability of your build" — the merge decides which
  configuration survives, and one of the two carried the source and target
  release. Merged into one declaration with three executions.
- `icons/icon.ico` was tracked but is generated at `prepare-package`. The
  committed copy had drifted from its own generator: 15,406 bytes against the
  26,072 the current converter produces, so the file in the repository was not
  the file any build used. Untracked and ignored.
- `AdminUtil.isRunningAsAdmin()` is memoised. It was called once per font
  during a scan — 303 times for this library — and on Windows it works by
  creating a file in `C:\Windows\System32` and deleting it again. Measured, it
  is **not** the performance problem it looks like: 38 ms for 303 calls
  unelevated, because the write fails immediately. Elevated, it really does
  create and delete a file in System32 once per font per refresh. Elevation
  cannot change within a process, so caching it changes nothing else.
- A comment in `FontService` referred to a `BUG.md` that is not in the
  repository.

---

## Deliberately left alone

**The font bundle.** 303 files, 14.5 MB, redistribution licensing unreviewed.
This is the first review's P1 and it is the author's decision, not one to make
by deleting 14.5 MB of someone's library. The README now states plainly that the
licensing has not been reviewed and that Lipi does not need the bundle — the
Import feature means a user can bring their own, and a clone with an empty
`fonts/` starts and works. **Nothing was removed.**

**No LICENSE file.** Choosing a licence is the author's decision. Still absent,
still worth doing.

**Font identity is the file name.** Whether a font counts as installed is
decided by matching file names. Windows, installing a font per-user through
Explorer, renames it to the font's full name — so `KANTI.TTF` in the library
and `Kantipur Regular.ttf` in the per-user store are one typeface that Lipi
counts as two, and installing the library copy adds a second copy of the same
face under a second registry entry.

This is real and was observed on the machine this pass ran on, where `HKLM`
holds two entries for one file:

```
Kantipur (Plain):001.001 (TrueType)  REG_SZ  KANTI.TTF
Kantipur (TrueType)                  REG_SZ  KANTI.TTF
```

The second is the shape `registryValueName()` produces
(`Font.createFont().getFamily()` + " (TrueType)"); the first is the full name,
which is what Windows uses. Fixing it properly means identifying a font by its
declared name rather than its file name, which is a design change across
install, remove and the installed-status check, and it would change what the
Status column says about every row. Out of scope for a remediation pass.
Documented in the README's Known Limitations and here.

**The `isWindowsAdmin()` technique.** It detects elevation by writing a file
into `C:\Windows\System32`. A token check would be cleaner, but this works, it
is now called once per process instead of once per font, and replacing it
reaches into elevation handling that is otherwise correct and hard to test.

**Everything in the UI.** No layout, styling, copy or interaction was changed.
The one user-visible change is that a removal of fonts that were not installed
no longer raises an error dialog — that is the bug, not a redesign. Two strings
naming `font-manager-1.0.0.jar` in restart-failure dialogs were updated to
`lipi-1.0.0.jar`, because the rename would otherwise have made them wrong.

---

## The test suite

`tests/java/com/lipi/tests/`, 98 checks, no framework. The project declares
`<dependencies/>` empty on purpose, so the suite brings its own assertions
(`Harness.java`) and runner rather than making the build download JUnit for the
first time in its life.

**`Sfnt.java` is the piece worth stealing.** It writes TrueType and OpenType
containers byte by byte — table directory, `name` table, records, string
storage — so a check can state exactly what is inside a font and then assert on
the name Lipi shows for it. Real font files cannot do that job: to test that a
Windows record beats a Macintosh one you need a font carrying both, saying
different things, and no amount of searching a font library guarantees one
exists. It also reaches the cases that break parsers and never occur on
purpose: a name table pointing past the end of the file, a string offset beyond
the file, a collection declaring no faces, a name of 400 characters. About 280
lines.

**Nothing in the suite installs or removes a font.** A suite that did would be
changing the machine it runs on to check that it can change the machine it runs
on. It asserts everything up to that point, which is where all six defects
were. Two checks do read the live registry, read-only, and one of them is the
end-to-end proof of the per-user indexing bug — it fails against the original
on any machine with a per-user font installed, and says so explicitly when
there are none rather than passing silently.

**`run.ps1 -Original`** writes the first commit's sources into a temporary
directory with `git show` and runs the same suite against them. 22 of the 98
fail. Twelve of those fail because a seam added during the fix does not exist
there — the tests reach the extracted methods by reflection precisely so the
file still compiles against the original and reports a named failure instead of
a build error. The other ten are behavioural and need no seam: the live
registry check, the `NullPointerException`, the four ZIP checks, the three log
checks and the removal count.

Two traps cost time and are worth knowing before editing `run.ps1`. Windows
PowerShell 5.1's `Set-Content -Encoding UTF8` writes a byte-order mark, and
javac rejects both a source file and an `@argfile` that starts with one — the
argfile failure reads `error: Invalid filename: ?C:\...` with an invisible
character in it, which looks like a missing file rather than an encoding
problem. `[System.IO.File]::WriteAllLines` writes no BOM. And
`-Djava.awt.headless=true` must be quoted or PowerShell reads `-Djava` as one
of its own parameter names and hands java the remainder, reported as "Could not
find or load main class .awt.headless=true".

---

## Notes for whoever is next

**Build with JDK 21 or newer.** The POM asks for source and target 21. JDK 17
at `D:\dev-tools\jdk17` cannot build it and fails with `invalid target release:
21`, which reads like a broken project. **JDK 25 is installed at
`D:\Program Files\Java\jdk-25.0.1`** and builds it fine; Maven 3.9.11 is at
`D:\Program Files\apache-maven-3.9.11`. Neither `java` nor `mvn` is on the
PATH. The archive's handover file listed only the JDK 17 for this project.

**The build needs the network once.** `exec-maven-plugin:3.3.0` was not in the
local Maven repository and `mvn -o` fails on it. Everything else resolved
offline.

**Read `WindowsFontRegistry`'s class javadoc before changing it.** It records
Windows behaviours that were each found the hard way — the registry-backed
Fonts view, the Font Driver Host lock, why per-font registry queries turned
startup into a two-minute wait — and the comments are the only place any of it
is written down. This pass found two bugs in that class and neither was in the
parts those comments describe; the comments are accurate.

**If you touch the hive handling, the two storage forms are the thing.**
`HKLM` stores a bare file name resolved against `%WINDIR%\Fonts`; `HKCU` stores
an absolute path. Reading and writing both have to know that, and getting
either wrong is silent — the map simply comes back with keys nobody looks up,
or the font does not load and `reg add` reports success.

**The licensing question is still open and is the author's.** So is the missing
LICENSE file.

## Licence, 2026-10-02

The author chose one licence for all of their projects: free for personal,
non-commercial use, modifiable for the user's own use, and not to be
distributed, original or modified. No standard licence says that, so `LICENSE`
is a short custom text, the same in every project apart from the year and what
it excludes. The README's licence section says the same. Anything above that
calls the licence an open question, or calls the project MIT, is superseded.

The POM's `<vendor>` is now Manish Majhi rather than Nishant Majhi, at the
author's request; jpackage writes it into the installer as the publisher. The
fonts in `fonts/` are excluded from the licence by name, since their licensing
is still unreviewed.

## CI, 2026-10-02

`.github/workflows/ci.yml` runs `mvn test` with JDK 21 on `windows-latest`, on
every push. `release.yml` still builds the installer on tags. Nothing has been
pushed, so it has not run on GitHub yet. Every job was run before committing,
from a fresh clone of this branch, the way a runner would run it: Windows jobs
here, in PowerShell 7 or the shell the step names, with only the tools the job
sets up on `PATH`. 98 (with JDK 25 here, compiling at release 21).
