# =============================================================================
#  verify-all.ps1 -- run every offline check for the iOS 26 port
#
#  Usage (from the ios-port directory):
#      powershell -ExecutionPolicy Bypass -File tools\verify-all.ps1
#
#  Exits 0 only when all checks pass. Requires:
#    * JDK 8+ on PATH or via -JavaHome (needs java + javac + jar)
#    * the ASM jars from dex-tools (see $AsmHome below)
#    * libs\*.jar already built (run tools\build-libs.ps1 first if missing)
#
#  Why the classpath is passed as SEPARATE arguments everywhere:
#  LinkCheck / VerifyJar / AccessCheck / ObjCNameCheck each take a list of jars
#  and open them with ZipFile. A single "a.jar;b.jar" string is treated as one
#  file path, which yields a flood of bogus ClassNotFoundException results.
# =============================================================================
param(
    [string]$JavaHome,
    [string]$AsmHome,
    [string]$MavenDir
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$veg  = Split-Path -Parent $root

# Defaults are derived from the script location, never hard-coded: this project
# lives under a non-ASCII path, and Windows PowerShell 5.1 decodes a BOM-less
# UTF-8 script as ANSI, which would mangle any literal CJK path in the file.
if (-not $JavaHome) {
    foreach ($cand in @(
        (Join-Path $env:ProgramFiles 'STMicroelectronics\STM32Cube\STM32CubeMX\jre'),
        (Join-Path $env:ProgramFiles 'JetBrains\PyCharm 2026.2.3\jbr'),
        $env:JAVA_HOME
    )) {
        if ($cand -and (Test-Path (Join-Path $cand 'bin\javac.exe'))) { $JavaHome = $cand; break }
    }
}
if (-not $AsmHome)  { $AsmHome  = Join-Path $veg 'tools\dextools\dex-tools-v2.4\lib' }
if (-not $MavenDir) { $MavenDir = Join-Path $veg 'maven' }

Push-Location $root
try {
    $java  = Join-Path $JavaHome 'bin\java.exe'
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $jarExe = Join-Path $JavaHome 'bin\jar.exe'
    foreach ($exe in @($java, $javac, $jarExe)) {
        if (-not (Test-Path $exe)) { throw "missing tool: $exe  (pass -JavaHome)" }
    }

    $asmcp = (@('asm-9.5', 'asm-tree-9.5', 'asm-util-9.5', 'asm-analysis-9.5', 'asm-commons-9.5') |
              ForEach-Object { Join-Path $AsmHome "$_.jar" }) -join ';'
    foreach ($j in $asmcp.Split(';')) {
        if (-not (Test-Path $j)) { throw "missing ASM jar: $j  (pass -AsmHome)" }
    }

    $L = Join-Path $root 'libs'
    $B = Join-Path $root 'build'
    $rv = Join-Path $MavenDir 'robovm'
    $rt = @(
        (Join-Path $MavenDir 'ios26\gdx-backend-robovm-metalangle-1.14.2.jar'),
        (Join-Path $rv 'robovm-rt-2.3.19.jar'),
        (Join-Path $rv 'robovm-objc-2.3.19.jar'),
        (Join-Path $rv 'robovm-cocoatouch-2.3.19.jar')
    )
    $gdx  = Join-Path $L 'gdx-1.14.2-ios.jar'
    $game = Join-Path $L 'game-core2.jar'
    $c26  = Join-Path $B 'classes-core26.jar'
    $i26  = Join-Path $B 'classes-ios26.jar'

    foreach ($f in @($gdx, $game) + $rt) {
        if (-not (Test-Path $f)) { throw "missing dependency: $f" }
    }

    New-Item -ItemType Directory -Force -Path "$B\classes-tools" | Out-Null
    New-Item -ItemType Directory -Force -Path "$B\classes-core26" | Out-Null
    New-Item -ItemType Directory -Force -Path "$B\classes-ios26" | Out-Null

    function Run([string]$label, [string[]]$argv) {
        $out = & $java @argv 2>&1
        $code = $LASTEXITCODE
        $last = ($out | Where-Object { "$_".Trim() -ne '' } | Select-Object -Last 1)
        $bad = ($code -ne 0) -or ("$last" -match 'problems=[1-9]|violations=[1-9]|unresolved=[1-9]|mismatches=[1-9]|failed=[1-9]|FAILED|ԭ��|Exception')
        $mark = if ($bad) { 'FAIL' } else { 'ok  ' }
        '{0} {1,-22} {2}' -f $mark, $label, "$last".Trim()
        if ($bad) { $script:failures++ }
    }

    $script:failures = 0
    $toolcp = "$asmcp;$B\classes-tools"

    # ---- 1. recompile the two hand-written source sets against 1.14.2 ----
    # Done before the tools, because LaunchCheck26 / LaunchCheck26Scene /
    # RuntimeContract / SmokeTest reference IOSPlatForm, the launchers, libGDX
    # and the RoboVM bindings at compile time.
    $coreSrc = Get-ChildItem (Join-Path $root 'core\src\main\java') -Recurse -Filter *.java |
               ForEach-Object { $_.FullName }
    & $javac -nowarn -proc:none -encoding UTF-8 -source 8 -target 8 `
             -cp "$gdx;$game" -d "$B\classes-core26" @coreSrc 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'core26 compilation failed' }

    $iosSrc = Get-ChildItem (Join-Path $root 'ios26\src\main\java') -Recurse -Filter *.java |
              ForEach-Object { $_.FullName }
    & $javac -nowarn -proc:none -encoding UTF-8 -source 8 -target 8 `
             -cp "$gdx;$game;$B\classes-core26;$($rt -join ';')" -d "$B\classes-ios26" @iosSrc 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'ios26 compilation failed' }

    Push-Location "$B\classes-core26"; & $jarExe cf $c26 . 2>&1 | Out-Null; Pop-Location
    Push-Location "$B\classes-ios26";  & $jarExe cf $i26 . 2>&1 | Out-Null; Pop-Location

    # ---- 2. rebuild the tools against the full runtime classpath ----
    # OggToWav needs the vendored JOrbis decoder; the checkers need ASM plus the
    # classes they inspect.
    $jorbis = Join-Path $L 'jorbis.jar'
    $toolSrc = Get-ChildItem (Join-Path $root 'tools\src') -Filter *.java | ForEach-Object { $_.FullName }
    $metalangle = Join-Path $MavenDir 'ios26\gdx-backend-robovm-metalangle-1.14.2.jar'
    $vcd0 = Join-Path $root 'tools\vcdeps'
    $toolBuildCp = @($asmcp.Split(';')) + @(
        $jorbis, $gdx, $game, $metalangle,
        "$B\classes-core26", "$B\classes-ios26"
    ) + $rt + @(
        (Join-Path $vcd0 'robovm-compiler-2.3.19.jar'),
        (Join-Path $vcd0 'simple-xml-2.7.1.jar'),
        (Join-Path $vcd0 'commons-io-2.7.jar'),
        (Join-Path $vcd0 'commons-lang3-3.9.jar'),
        (Join-Path $vcd0 'commons-compress-1.5.jar'),
        (Join-Path $vcd0 'asm-9.0.jar'),
        (Join-Path $vcd0 'xz-1.2.jar'),
        (Join-Path $vcd0 'jsoup-1.7.1.jar')
    )
    $toolBuildCp = $toolBuildCp -join ';'
    & $javac -nowarn -encoding UTF-8 -cp $toolBuildCp -d "$B\classes-tools" @toolSrc 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'tool compilation failed' }

    # ---- 2. static bytecode checks ----
    # Each dependent jar is appended as its own argv entry (never joined with ';').
    $iosDeps = @($gdx, $game, $c26) + $rt
    Run 'VerifyJar shim'    (@('-cp', $toolcp, 'VerifyJar', $gdx))
    Run 'LinkCheck game'    (@('-cp', $toolcp, 'LinkCheck', $game, $gdx))
    Run 'VerifyJar core26'  (@('-cp', $toolcp, 'VerifyJar', $c26, $gdx, $game))
    Run 'LinkCheck core26'  (@('-cp', $toolcp, 'LinkCheck', $c26, $gdx, $game))
    Run 'VerifyJar ios26'   (@('-cp', $toolcp, 'VerifyJar', $i26) + $iosDeps)
    Run 'LinkCheck ios26'   (@('-cp', $toolcp, 'LinkCheck', $i26) + $iosDeps)
    Run 'AccessCheck game'  (@('-cp', $toolcp, 'AccessCheck', $game, $gdx) + $rt)
    Run 'AccessCheck ios26' (@('-cp', $toolcp, 'AccessCheck', $i26) + $iosDeps)

    # ---- 3. semantic / runtime-shape checks (desktop JVM, no device needed) ----
    $runCp = "$toolcp;$gdx;$game;$c26;$i26;$($rt -join ';')"
    Run 'RuntimeContract'    @('-cp', $runCp, 'RuntimeContract')
    Run 'SmokeTest'          @('-cp', $runCp, 'SmokeTest')
    Run 'LaunchCheck26'      @('-cp', $runCp, 'LaunchCheck26')
    Run 'LaunchCheck26Scene' @('-cp', $runCp, 'LaunchCheck26Scene')

    # ---- 3b. launch-order check: nothing may touch Gdx inside createApplication() ----
    # libGDX 1.14.2 calls Delegate.createApplication() BEFORE IOSApplication assigns
    # Gdx.app, so a V3Main built there throws "Gdx.app not available." LazyGameListener
    # defers the construction to listener.create(), which runs after Gdx is ready.
    Run 'OrderCheck'         @('-cp', $toolcp, 'OrderCheck', $i26, $c26)

    # ---- 4. RoboVM ObjC class-name cross-check against both plists ----
    Run 'ObjCNameCheck' @('-cp', $toolcp, 'ObjCNameCheck', $i26,
                          (Join-Path $rv 'robovm-rt-2.3.19.jar'),
                          (Join-Path $rv 'robovm-objc-2.3.19.jar'),
                          (Join-Path $rv 'robovm-cocoatouch-2.3.19.jar'),
                          (Join-Path $root 'ios26\Info.plist.xml'),
                          (Join-Path $root 'ios26scene\Info-scene.plist.xml'))

    # ---- 5. RoboVM config XML validation (parse + path resolution, no Xcode) ----
    # RoboVM's Simple-XML mapping rejects unknown elements wholesale. Running
    # Config.Builder.read() here catches a config typo in seconds instead of
    # after an hour-long CI build. Framework paths inside ios26*/ resolve
    # through the junctions recreated by setupLinks.sh (or created locally).
    #
    # ValidateConfig's runtime deps (Simple-XML, commons-io, commons-lang3,
    # commons-compress, ASM, XZ, jsoup, robovm-compiler) are vendored under
    # tools/vcdeps so this works identically on a dev box and on CI.
    $vcd = Join-Path $root 'tools\vcdeps'
    $vccp = @(
        (Join-Path $vcd 'robovm-compiler-2.3.19.jar'),
        (Join-Path $vcd 'robovm-gradle-plugin-2.3.19.jar'),
        (Join-Path $vcd 'simple-xml-2.7.1.jar'),
        (Join-Path $vcd 'commons-io-2.7.jar'),
        (Join-Path $vcd 'commons-lang3-3.9.jar'),
        (Join-Path $vcd 'commons-compress-1.5.jar'),
        (Join-Path $vcd 'asm-9.0.jar'),
        (Join-Path $vcd 'xz-1.2.jar'),
        (Join-Path $vcd 'jsoup-1.7.1.jar')
    )
    foreach ($j in $vccp) {
        if (-not (Test-Path $j)) { throw "missing vendored dep: $j" }
    }
    $vcCp = ($vccp + @("$B\classes-tools")) -join ';'
    Run 'ValidateConfig ios26' (@('-cp', $vcCp, 'ValidateConfig',
                                  (Join-Path $root 'ios26\robovm-ios26.xml')))
    Run 'ValidateConfig scene' (@('-cp', $vcCp, 'ValidateConfig',
                                  (Join-Path $root 'ios26scene\robovm-ios26scene.xml')))


    Write-Host ''
    if ($script:failures -gt 0) {
        Write-Host "FAILED: $($script:failures) check(s) did not pass" -ForegroundColor Red
        exit 1
    }
    Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green
    Write-Host ''
    Write-Host 'Not covered here: RoboVM AOT compilation, code signing, and anything'
    Write-Host 'on-device. Those need macOS + Xcode; see INSTALL.md.'
    exit 0
}
finally {
    Pop-Location
}
