# fix-windows-tls.ps1 - Repair Apple GrandSlam TLS trust on Windows
#
# SYMPTOM
#   Sideloadly: "Login failed (-80046): This action cannot be completed at this time."
#   followed by "Install failed: Guru Meditation 0db732@418:3aea77"
#
# ROOT CAUSE (measured, not guessed)
#   gsa.apple.com (Apple's GrandSlam auth endpoint) serves a chain:
#       gsa.apple.com
#         <- Apple Server Authentication CA
#           <- Apple Root CA          <-- MISSING from Windows + from Sideloadly certifi
#   idmsa.apple.com works because it chains to "Apple Public Server RSA CA 1 - G1",
#   which IS present. That is why only the GrandSlam half of login fails.
#
#   Result before fix:
#     curl/schannel  -> SEC_E_UNTRUSTED_ROOT
#     python certifi -> CERTIFICATE_VERIFY_FAILED: self-signed certificate in chain
#     .NET X509Chain -> PartialChain
#
# WHAT THIS SCRIPT DOES
#   1. downloads Apple Root CA from apple.com and verifies its SHA-1 thumbprint
#   2. installs it into Cert:\CurrentUser\Root  (no admin needed)
#   3. appends it to Sideloadly's bundled certifi bundle (with a .bak backup)
#   4. re-verifies both trust paths
#
# IDEMPOTENT. Re-running is safe.

[CmdletBinding()]
param(
    [string]$SideloadlyDir = "$env:LOCALAPPDATA\Sideloadly"
)

$ErrorActionPreference = 'Stop'
$ExpectedThumb = '611E5B662C593A08FF58D14AE22452D198DF6C60'   # Apple Root CA, valid to 2035-02-10

Write-Host '== 1. download Apple Root CA ==' -ForegroundColor Cyan
$work = Join-Path $env:TEMP 'apple-root-ca-fix'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$cer = Join-Path $work 'AppleRootCA.cer'
& curl.exe -sS --max-time 60 -o $cer 'https://www.apple.com/appleca/AppleIncRootCertificate.cer'
if (-not (Test-Path $cer)) { throw 'download failed' }
$cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($cer)
Write-Host ("   subject : {0}" -f $cert.Subject)
Write-Host ("   thumb   : {0}" -f $cert.Thumbprint)
if ($cert.Thumbprint -ne $ExpectedThumb) {
    throw "thumbprint mismatch: got $($cert.Thumbprint), expected $ExpectedThumb"
}
Write-Host '   thumbprint verified' -ForegroundColor Green

Write-Host '== 2. install into CurrentUser\Root ==' -ForegroundColor Cyan
$store = New-Object System.Security.Cryptography.X509Certificates.X509Store('Root', 'CurrentUser')
$store.Open('ReadWrite')
if ($store.Certificates | Where-Object { $_.Thumbprint -eq $ExpectedThumb }) {
    Write-Host '   already present, skipping' -ForegroundColor Yellow
} else {
    $store.Add($cert)
    Write-Host '   installed' -ForegroundColor Green
}
$store.Close()

Write-Host '== 3. append to Sideloadly certifi bundle ==' -ForegroundColor Cyan
$bundle = Join-Path $SideloadlyDir 'certifi\cacert.pem'
if (-not (Test-Path $bundle)) {
    Write-Host "   $bundle not found - is Sideloadly installed?" -ForegroundColor Yellow
} else {
    $txt = [System.IO.File]::ReadAllText($bundle)
    if ($txt -match [regex]::Escape($ExpectedThumb)) {
        Write-Host '   already contains Apple Root CA, skipping' -ForegroundColor Yellow
    } elseif ($txt -match 'Apple Root CA') {
        Write-Host '   Apple Root CA marker present (thumb not embedded in PEM), skipping' -ForegroundColor Yellow
    } else {
        $bak = "$bundle.bak-$(Get-Date -Format yyyyMMdd-HHmmss)"
        Copy-Item $bundle $bak
        Write-Host "   backup -> $bak"
        $b64 = [Convert]::ToBase64String($cert.RawData, 'InsertLineBreaks')
        $pem = "`n# Apple Root CA (added for gsa.apple.com GrandSlam auth)`n-----BEGIN CERTIFICATE-----`n$b64`n-----END CERTIFICATE-----`n"
        [System.IO.File]::AppendAllText($bundle, $pem, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host '   appended' -ForegroundColor Green
    }
}

Write-Host '== 4. verify ==' -ForegroundColor Cyan
$leafDer = Join-Path $work 'gsa.der'
& curl.exe -sS --max-time 30 -o $leafDer 'https://gsa.apple.com/' 2>$null
$chain = New-Object System.Security.Cryptography.X509Certificates.X509Chain
$chain.ChainPolicy.RevocationMode = 'NoCheck'
$probe = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2
try {
    $tcp = New-Object System.Net.Sockets.TcpClient('gsa.apple.com', 443)
    $ssl = New-Object System.Net.Security.SslStream($tcp.GetStream(), $false, ({ param($a,$b,$c,$d) $true }))
    $ssl.AuthenticateAsClient('gsa.apple.com')
    $probe = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($ssl.RemoteCertificate)
    $ssl.Close(); $tcp.Close()
} catch { Write-Host "   TLS probe failed: $($_.Exception.Message)" -ForegroundColor Red }

if ($probe.Subject) {
    $ok = $chain.Build($probe)
    Write-Host ("   chain build = {0}" -f $ok) -ForegroundColor $(if ($ok) { 'Green' } else { 'Red' })
    $i = 0
    foreach ($e in $chain.ChainElements) { Write-Host ("     [{0}] {1}" -f $i, $e.Certificate.Subject); $i++ }
    if (-not $ok) { Write-Host ("   status: {0}" -f (($chain.ChainStatus | ForEach-Object { $_.Status }) -join ', ')) -ForegroundColor Red }
}

$code = & curl.exe -sS -o NUL -w '%{http_code}' --max-time 30 -X POST 'https://gsa.apple.com/grandslam/GsService2' -H 'Content-Type: application/json' -d '{}' 2>&1
Write-Host ("   POST /grandslam/GsService2 -> HTTP {0}  (401/400 = reachable and trusted; TLS error = still broken)" -f $code)

Write-Host ''
Write-Host 'Done. Restart Sideloadly before retrying the login.' -ForegroundColor Cyan