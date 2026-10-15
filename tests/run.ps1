<#
    Lipi's test suite.

        .\tests\run.ps1              run the suite against the working tree
        .\tests\run.ps1 -Original    run it against the first commit instead

    The suite compiles with javac and runs with java. It does not use Maven and
    it has no dependencies, because neither does Lipi: its POM declares
    <dependencies/> and the application is Swing, which ships with the JDK.
    Adding JUnit to test it would make the build download an artefact for the
    first time in its life. tests/java/com/lipi/tests/Harness.java is the whole
    framework.

    -Original writes the first commit's sources into a temporary directory with
    git show and runs the same suite against those instead. It is how the
    defects this suite describes were shown to be real rather than asserted:
    the checks that do not depend on a seam added during the fix fail there and
    pass here. Nothing is transcribed and nothing is copied into the repository,
    so the assertions cannot drift from the code they describe.
#>

[CmdletBinding()]
param(
    [switch] $Original,
    [switch] $KeepBuild
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot    = Join-Path $projectRoot 'tests'

# -- Find a JDK ---------------------------------------------------------------
#
# javac is often not on PATH on a Windows machine even when a JDK is installed,
# so JAVA_HOME and the usual install roots are tried before giving up. The
# project targets Java 21 in its POM; the suite itself needs nothing newer than
# 17, but it compiles the application sources, so whatever is found has to be
# able to build them.

function Find-Javac {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
        return "$env:JAVA_HOME\bin\javac.exe"
    }
    $onPath = Get-Command javac -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $roots = @(
        'D:\Program Files\Java', 'C:\Program Files\Java',
        'D:\dev-tools', 'C:\Program Files\Eclipse Adoptium'
    )
    $found = foreach ($root in $roots) {
        if (Test-Path $root) {
            Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Get-ChildItem $_.FullName -Filter 'javac.exe' -Recurse -Depth 2 -ErrorAction SilentlyContinue } |
                Select-Object -ExpandProperty FullName
        }
    }
    # Newest first: the POM asks for source/target 21, so an older JDK that
    # happens to be listed first would fail the compile with "invalid target
    # release" and look like a broken suite rather than a missing toolchain.
    $found | Sort-Object -Descending | Select-Object -First 1
}

$javac = Find-Javac
if (-not $javac) {
    Write-Host 'SKIP: no JDK found. Set JAVA_HOME or put javac on PATH.' -ForegroundColor Yellow
    exit 0
}
$java = Join-Path (Split-Path -Parent $javac) 'java.exe'

Write-Host "JDK:  $javac"
& $javac -version

# -- Choose the sources under test --------------------------------------------

$buildDir = Join-Path ([System.IO.Path]::GetTempPath()) ("lipi-tests-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$classes  = Join-Path $buildDir 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null

if ($Original) {
    $originalSrc = Join-Path $buildDir 'original-src'
    New-Item -ItemType Directory -Force -Path $originalSrc | Out-Null

    Push-Location $projectRoot
    try {
        $baseCommit = (& git rev-list --max-parents=0 HEAD).Trim()
        if (-not $baseCommit) { throw 'could not find the first commit' }
        Write-Host "Mode: ORIGINAL, from commit $baseCommit" -ForegroundColor Yellow

        $files = & git ls-tree -r --name-only $baseCommit -- 'src/main/java'
        foreach ($f in $files) {
            $dest = Join-Path $originalSrc $f
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
            # WriteAllLines, not Set-Content -Encoding UTF8: in Windows
            # PowerShell 5.1 that writes a byte-order mark, and javac rejects a
            # source file that starts with one ("illegal character: '﻿'").
            # .NET's WriteAllLines defaults to UTF-8 with no BOM.
            $lines = & git show "${baseCommit}:$f"
            [System.IO.File]::WriteAllLines($dest, $lines)
        }
        $appSourceRoot = Join-Path $originalSrc 'src\main\java'
    } finally {
        Pop-Location
    }
} else {
    Write-Host 'Mode: working tree'
    $appSourceRoot = Join-Path $projectRoot 'src\main\java'
}

# -- Compile -------------------------------------------------------------------

$appSources  = Get-ChildItem $appSourceRoot -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName
$testSources = Get-ChildItem (Join-Path $testRoot 'java') -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName

$argsFile = Join-Path $buildDir 'sources.txt'
# An argument file, because 25 paths with spaces in them on one command line is
# how a build starts failing for reasons that have nothing to do with the code.
# Written with WriteAllLines for the same reason as above: a BOM here is
# reported as "error: Invalid filename: ?C:\..." with an invisible character in
# it, which reads as a missing file rather than an encoding problem.
[System.IO.File]::WriteAllLines(
    $argsFile,
    [string[]](($appSources + $testSources) | ForEach-Object { '"' + $_.Replace('\', '\\') + '"' }))

Write-Host ("Compiling {0} application and {1} test sources..." -f $appSources.Count, $testSources.Count)
& $javac -nowarn -encoding UTF-8 -d $classes "@$argsFile"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'FAILED to compile.' -ForegroundColor Red
    if (-not $KeepBuild) { Remove-Item -Recurse -Force $buildDir -ErrorAction SilentlyContinue }
    exit 1
}

# -- Run -----------------------------------------------------------------------

Write-Host ''
# The -D argument is quoted because PowerShell otherwise reads "-Djava" as one
# of its own parameter names and hands java the remainder, which it then
# reports as "Could not find or load main class .awt.headless=true".
& $java '-Djava.awt.headless=true' -cp $classes com.lipi.tests.Runner
$result = $LASTEXITCODE

if ($KeepBuild) {
    Write-Host "Build kept at $buildDir"
} else {
    Remove-Item -Recurse -Force $buildDir -ErrorAction SilentlyContinue
}

exit $result
