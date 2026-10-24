<#
.SYNOPSIS
    Build-free install of Veggies3HD onto a USB-attached iPhone from Windows.

.DESCRIPTION
    Everything here was learned on a real iOS 26.3 device; each block says why it
    exists so a future run does not rediscover it.

    Sideloadly cannot be used. Since 2026-10-12 Apple rejects anisette data derived
    from the ADI library, which is exactly what Sideloadly's "Local Anisette" mode
    uses on Windows. The symptom:

        Login failed (-80046): This action cannot be completed at this time.

    ...after the password AND the 2FA code were both accepted. Sideloadly's remote
    anisette (which does work) is Patreon-only. iPASide uses a free remote Mac
    anisette service, so it works.

    The other trap is the App ID name. Apple validates appIdName against ASCII and
    rejects anything else with:

        35: An invalid value '燃烧的蔬菜3HD' was provided for the parameter 'appIdName'.

    so this script always passes --name with an ASCII value.

.PARAMETER Ipa
    Path to the unsigned .ipa. Must be on an ASCII path: the engine reads it with a
    byte-oriented API and mangles non-ASCII paths.

.PARAMETER AppName
    ASCII App ID name. Defaults to Veggies3HD.

.EXAMPLE
    .\install-ios.ps1 -Ipa C:\Temp\Veggies3HD.ipa
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Ipa,
    [string]$AppName = 'Veggies3HD',
    [switch]$SkipLogin,
    [switch]$RefreshAnisette
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$engine = Join-Path $env:LOCALAPPDATA 'Programs\iPASide\engine'
$python = Join-Path $engine 'python\python.exe'
$site   = Join-Path $engine 'python\Lib\site-packages'

function Fail($msg) {
    Write-Host "FAILED: $msg" -ForegroundColor Red
    exit 1
}

# ---- 1. Engine present? -----------------------------------------------------
if (-not (Test-Path $python)) {
    Fail ("iPASide engine not found at $python`n`n" +
          "Install it first (normal Windows installer, no Mac needed):`n" +
          "  https://github.com/pwnapplehat/iPASide/releases/latest`n`n" +
          "Sideloadly will NOT work for this device -- see .DESCRIPTION.")
}

# ---- 2. IPA path must be ASCII ---------------------------------------------
if ($Ipa -match '[^\x00-\x7F]') {
    Fail ("IPA path contains non-ASCII characters: $Ipa`n" +
          "Copy it somewhere like C:\Temp\Veggies3HD.ipa first.")
}
if (-not (Test-Path $Ipa)) { Fail "IPA not found: $Ipa" }

# ---- 3. Environment for the engine -----------------------------------------
# PYTHONPATH lets "python -m ipaside_engine" find the bundled package without
# touching the interpreter's own site configuration.
$env:PYTHONPATH = $site
$env:PYTHONIOENCODING = 'utf-8'

function Invoke-Engine {
    param([string[]]$EngineArgs)
    & $python -m ipaside_engine @EngineArgs
    return $LASTEXITCODE
}

# ---- 4. Device must be visible ---------------------------------------------
Write-Host '==> Checking for a connected device' -ForegroundColor Cyan
$devices = & $python -m ipaside_engine devices 2>&1 | Out-String
Write-Host $devices
if ($devices -match 'No iOS devices visible') {
    Fail ("No device. Plug the iPhone in over USB, unlock it, tap Trust.`n`n" +
          "If it still does not appear, start the Apple service:`n" +
          "  Get-Service 'Apple Mobile Device Service' | Start-Service`n`n" +
          "The Microsoft Store build of iTunes/iCloud does NOT install a usable`n" +
          "usbmuxd -- install the apple.com versions.")
}

# ---- 5. Anisette -----------------------------------------------------------
# A stale identity cache is the other cause of -80046: the server-side session
# expires while the cached blob keeps being replayed.
if ($RefreshAnisette) {
    Write-Host '==> Refreshing anisette identity' -ForegroundColor Cyan
    Invoke-Engine @('anisette', '--refresh') | Out-Null
}
Write-Host '==> Anisette status' -ForegroundColor Cyan
Invoke-Engine @('anisette') | Out-Null

# ---- 6. Apple ID login -----------------------------------------------------
# -SkipLogin is for renewing a 7-day expiry inside the same session, where the
# token the engine already holds is still valid.
if (-not $SkipLogin) {
    Write-Host ''
    Write-Host '==> Apple ID login' -ForegroundColor Cyan
    Write-Host '    The password goes to Apple directly; this script never stores it.' -ForegroundColor DarkGray
    Write-Host '    If the account has 2FA you will be asked for the 6-digit code.' -ForegroundColor DarkGray
    $rc = Invoke-Engine @('login')
    if ($rc -ne 0) {
        Fail ("Login failed. Map the Apple error code:`n`n" +
              "  -80046  anisette rejected. Re-run with -RefreshAnisette. If it persists,`n" +
              "          the remote anisette service is rate limiting -- wait an hour.`n" +
              "  -20970  wrong password or unknown account (proves network + anisette are fine).`n" +
              "  -20101  too many attempts; Apple has locked the account for a while.")
    }
}

# ---- 7. Install ------------------------------------------------------------
Write-Host ''
Write-Host "==> Installing $([System.IO.Path]::GetFileName($Ipa))" -ForegroundColor Cyan
Write-Host "    App ID name: $AppName (must be ASCII -- see .DESCRIPTION)" -ForegroundColor DarkGray
$rc = Invoke-Engine @('sideload', $Ipa, '--name', $AppName)
if ($rc -ne 0) { Fail 'Sideload failed; see the engine output above.' }

# ---- 8. What the user still has to do on the phone -------------------------
Write-Host ''
Write-Host '==> Installed.' -ForegroundColor Green
Write-Host @"

Two things left, both on the phone:

  1. Settings > General > VPN & Device Management > Developer App >
     <your Apple ID> > Trust
     Without this the icon is there but tapping it does nothing.

  2. Settings > Privacy & Security > Developer Mode > On, then reboot.
     iOS 17+ refuses to run any development-signed binary until this is on.
     If the switch is missing, connect over USB once and run:
         python -m ipaside_engine developer-mode --reveal

The signature expires after 7 days. Re-run with -SkipLogin to renew; the save
file (Library/local/FireVeg201406) survives a reinstall.
"@ -ForegroundColor Gray
exit 0
