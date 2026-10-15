# लिपि (Lipi)

A desktop font manager for Nepali typefaces. It shows you a folder of font
files, tells you which of them the system already has, and installs or removes
them in bulk.

![Lipi](icons/icon-128.png)

Legacy Nepali fonts are awkward to manage. They arrive in ZIP archives with
names like `4prrch_.ttf`, `KSN-21.TTF` and `Copy of RUKMINI.TTF`, none of which
says what the font is; there are often hundreds of them; and installing one by
one through Explorer means reading each name in a preview window first. Lipi
reads the name out of each file, lists the lot, and lets you select and install
in one go.

---

## What it does, and where

Installing a font is not the same job on every operating system, and Lipi does
not do it equally well everywhere.

| | Windows | macOS | Linux |
| --- | --- | --- | --- |
| List fonts and read their real names | yes | yes | yes |
| Install and remove | yes | yes | yes |
| Registers the font so the OS sees it | yes | n/a | n/a |
| Tells running applications to refresh | yes | no | no |
| Handles files the OS has locked | yes | no | no |
| Rebuilds the font cache afterwards | n/a | best effort | yes |

**Windows is where the work went.** Copying a file into `C:\Windows\Fonts`
looks like it works and does not: that folder is a view onto a registry key,
not a directory listing, so a font that has been copied but not registered is
invisible to every application on the machine. Lipi writes the registry entry,
broadcasts `WM_FONTCHANGE` so open applications pick the change up, and deals
with the Font Driver Host holding a font file open by queueing the deletion for
the next restart — the same mechanism installers use.

**macOS and Linux are best effort.** Fonts are copied to the right per-user
directory and, on Linux, `fc-cache` is run so applications can see them. None of
the Windows-specific work applies or is attempted.

## Where fonts are installed

Lipi installs to the per-user location by default, because that needs no
privileges. It removes a font from wherever it actually is, which may be either.

| Platform | Per-user (default) | System-wide (needs elevation) |
| --- | --- | --- |
| Windows | `%LOCALAPPDATA%\Microsoft\Windows\Fonts\` | `%WINDIR%\Fonts\` |
| macOS | `~/Library/Fonts/` | `/Library/Fonts/` |
| Linux | `~/.local/share/fonts/` | — |

On Windows the two stores are told apart by which registry hive holds the
font's entry, not by which file exists — the shell creates compatibility links
that make a per-user font appear to exist at the system path as well, so file
existence cannot distinguish them.

---

## The font bundle, and its licensing

`fonts/` in this repository holds 303 TrueType files, about 15 MB, all
committed to git. They are the Nepali families that circulate as shareware and
commercial products — Preeti, Kantipur, Sagarmatha and relatives.

**Their redistribution licensing has not been reviewed.** There is no
`fonts/LICENSE` and no per-font attribution, and some fonts of this kind carry
embedded licence strings that prohibit redistribution. If you are forking or
publishing this, that is worth settling before you do.

Lipi does not need the bundle. Point it at any folder of `.ttf`/`.otf` files,
or use **File → Add Fonts** to bring your own; a fresh clone with an empty
`fonts/` directory starts and works normally.

---

## Running it

### From a release

Download the `.exe` from the [Releases](../../releases) page and run it. It
installs to Program Files, adds a Start Menu entry, and asks for Administrator
rights at launch.

### From source

You need **JDK 21 or newer** and **Maven 3.6 or newer**. There are no other
dependencies — Swing ships with the JDK, and `pom.xml` declares an empty
`<dependencies/>` on purpose.

```
mvn clean package
java -jar target/lipi-1.0.0.jar
```

The build puts the JAR in `target/` with `fonts/` and `icons/` beside it, which
is the layout the application expects at runtime. Running the JAR from
somewhere else, without those folders next to it, gives you an empty list.

### Administrator rights

Lipi needs elevation only to write to the *system* font store. Per-user
installs need nothing.

The installed `.exe` asks Windows for elevation at startup and takes the answer
at face value — decline it and Lipi keeps running, with install and remove
limited to the per-user store. Running from a JAR asks you first, because in a
development flow the relaunch goes through a different mechanism you may not
want.

---

## Using it

**The Font column is not the file name.** It is the name the font declares
inside itself, read out of its `name` table — the same string Windows, macOS
and every application's font menu use. The File Name column shows the file
separately, so where the two disagree you can see it. In a typical Nepali
library they usually do: `ANNA.TTF` is Annapurna, `KSN-21.TTF` is
KALAKAR-SHAKTI, `MFDEV112.TTF` is DevLys 110 Italic. A file damaged beyond
reading falls back to its file name with the extension removed.

**Selecting.** Tick rows, or right-click for Select All, Unselect All, Select
Installed and Invert Selection. The selection is kept on the fonts themselves,
not on the visible rows, so it survives a change of search term — which means a
selection can include rows currently filtered out of sight. Lipi says how many
when you act on it.

**Searching.** The box filters on both the font name and the file name, so an
old file name still finds its font.

**Importing.** File → Add Fonts takes `.ttf`/`.otf` files or a `.zip`, and
copies them into the `fonts/` folder. Everything in an archive that is not a
font is ignored, and archive subdirectories are flattened — `fonts/` is one
flat folder.

**Exporting.** File → Export Fonts writes the selected fonts to a directory of
your choice, or bundles them into a single ZIP.

---

## Building the Windows installer

```
mvn clean package jpackage:jpackage
```

The installer lands in `target/dist/`. `jpackage` is part of the JDK, so
nothing extra is downloaded, but on Windows it drives **WiX 3.x** and needs it
on the PATH:

1. Enable .NET 3.5 — WiX 3 will not install without it.
   Control Panel → Programs → Turn Windows features on or off → **.NET
   Framework 3.5**.
2. Install WiX 3.14, not WiX 4 or later. `jpackage` on JDK 21 calls
   `candle.exe` and `light.exe`; WiX 4 replaced both with a single `wix.exe`
   and `jpackage` only learned about that in JDK 24. "Install the latest WiX"
   fails with `Can not find WiX tools`.

`icons/icon.ico` is generated during the build from the PNGs beside it by
`src/build/java/ConvertIcon.java`, and is not tracked in git.

## Releasing

Push a tag shaped `v1.2.3` and the GitHub Actions workflow builds the installer
on a Windows runner and attaches it to a Release. The tag is the source of
truth for the version, so what is baked into the installer always matches what
people downloaded. Run the workflow manually for a test build that publishes
nothing.

Pre-release suffixes (`v1.2.3-beta.1`) mark the Release as a pre-release but
are stripped from the build version, because `jpackage` rejects any Windows app
version that is not `MAJOR.MINOR.PATCH`.

---

## Tests

```
mvn test                      # as part of the build
powershell tests\run.ps1      # on its own, without Maven
```

98 checks. The suite has no test framework: `tests/java/com/lipi/tests/`
carries its own assertions and runner, because the project has no dependencies
and adding JUnit to test it would make the build download an artefact for the
first time.

It builds its own font files rather than asserting against the ones in
`fonts/`. `Sfnt.java` writes TrueType containers byte by byte, so a check can
state exactly what is inside a font — two records disagreeing across platforms,
a name table pointing past the end of the file, a collection with no faces —
and then assert on the name Lipi displays for it. Those are the cases that
break a parser and the ones you cannot rely on finding in a real font library.

Nothing in the suite installs or removes a font. It asserts everything up to
that point: which files become rows, what those rows say, which store a font
resolves to, what goes into the registry entry, and what the counts report
afterwards.

```
powershell tests\run.ps1 -Original
```

runs the same suite against the first commit's sources, fetched with `git
show` into a temporary directory. 22 of the 98 checks fail there. That is how
the defects listed in `review.md` were shown to be real rather than asserted.

---

## Known limitations

**A font is identified by its file name.** Whether a font is already installed
is decided by matching file names, not by what the font calls itself. Windows,
installing a font per-user through Explorer, renames it to the font's full name
— so `KANTI.TTF` in your library and `Kantipur Regular.ttf` in the per-user
store are the same typeface and Lipi treats them as two. It will show the
library copy as not installed, and installing it adds a second copy of the same
face under a second registry entry. Removing either leaves the other.

**The registry value name is the font's family, not its full name.** Windows
uses the full name — `Kantipur (Plain):001.001 (TrueType)` where Lipi writes
`Kantipur (TrueType)`. Both work, since GDI resolves the file from the value's
data rather than its name, but it means Lipi's entry for a font will not
collide with, or replace, one Windows wrote for the same file.

**`fc-cache` is not run on macOS.** `atsutil databases -remove` is attempted
and needs privileges it usually will not have. Installed fonts may not appear
in already-running applications until they are restarted.

**No per-font licence checking on import.** Lipi copies what you give it.

---

## Troubleshooting

**The font list is empty.** Lipi looks for a `fonts/` folder next to the JAR or
in the installation directory. After `mvn clean package` that is `target/`, so
run the JAR from there or use the installer. An empty folder is not an error —
use File → Add Fonts.

**A font installed successfully but nothing can see it.** On Linux, the
fontconfig cache may not have been rebuilt; run `fc-cache -f` and check its exit
code. On Windows this should not happen — if it does, `lipi-debug.log` will say
which step failed. Applications that were already open need
`WM_FONTCHANGE` to reach them, which Lipi broadcasts but which some
applications ignore; restarting them is the fallback.

**A font will not delete.** Windows 10 and 11 hand font rendering to a separate
service (`fontdrvhost.exe`) which can hold a font file open in a way no ordinary
process can break, elevated or not. Lipi removes the registry entry — so the
font disappears from Explorer, Settings and every application immediately — and
queues the file itself for deletion at the next restart. It says so when this
happens. The font is uninstalled; only the bytes on disk are waiting.

**Install or remove fails with a permissions error.** The font is in the system
store and Lipi is not elevated. Restart it with right-click → Run as
administrator. Per-user fonts never need this.

**"Restart as Admin" does nothing, or cannot find `java`.** From a JAR, Lipi
relaunches through `javaw.exe` under `java.home`. A jpackage runtime has had
its launchers stripped out, which is why the installed `.exe` relaunches itself
instead. If it fails, start Lipi again by hand from an elevated prompt:
`java -jar target\lipi-1.0.0.jar`.

**Where the log is.** `%APPDATA%\Lipi\lipi-debug.log` on Windows; beside the
application elsewhere. It records every font operation with a timestamp and is
the only diagnostic there is under the native launcher, which has no console.
It rotates at 1 MB, keeping one previous file. Turn it off with `LIPI_LOG=off`
in the environment or `-Dlipi.log=off` on the command line.

---

## How the code is arranged

```
src/main/java/com/lipi/
├── Lipi.java                 entry point: log file, elevation, then the window
├── model/FontInfo.java       one row: file, declared name, installed or not
├── service/
│   ├── FontService.java      load the library; install and remove
│   └── FontExportService.java  import and export, ZIP and loose files
├── ui/                       Swing: window, table, menus, buttons
└── util/
    ├── FontNameReader.java   reads the name out of an sfnt file
    ├── WindowsFontRegistry.java  registry, WM_FONTCHANGE, reboot deletions
    ├── SystemUtil.java       which store owns a font; font cache refresh
    ├── AdminUtil.java        elevation checking and requesting
    └── AlertUtil.java        dialog helpers

src/build/java/              ConvertIcon: PNG → ICO, build-time only
tests/                       the suite and its runner
fonts/  icons/               data the application reads at runtime
```

The decisions worth testing are kept out of the UI and away from the
subprocesses. `FontNameReader` parses bytes. `WindowsFontRegistry`'s hive
parsing and value-data rules are separated from the `reg.exe` calls that use
them. `FontService` returns an enum per font rather than a boolean, so the
window can say what actually happened instead of showing one message for every
kind of failure.

Before changing anything in `WindowsFontRegistry`, read its class javadoc. It
records several Windows behaviours that were each found the hard way, and the
comments are the only place they are written down.

## Licence

Free for personal, non-commercial use. You may use, study and modify this
software for yourself, but you may not use it commercially, and you may not
share or publish it, in its original form or modified. See [LICENSE](LICENSE)
for the full terms.

The fonts in `fonts/` are not covered: they are other people's work, and
their licensing has not been reviewed (see above).
