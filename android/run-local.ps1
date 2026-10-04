<#
.SYNOPSIS
  Builds the debug app against the LOCAL backend, installs it on the connected device and starts it.

.DESCRIPTION
  Every time: checks the local backend answers, opens the adb reverse tunnel (it is lost whenever the
  device reconnects), builds with -Pezpz.apiUrl pointing at 127.0.0.1:5000, installs, and launches.
  A phone on USB and an emulator both work, because adb reverse maps the device's 127.0.0.1:5000 to this PC.

  Run from anywhere:  .\android\run-local.ps1
  Another device:     .\android\run-local.ps1 -Device R52Y305YXYL
  Skip the build:     .\android\run-local.ps1 -NoBuild      (just the tunnel and a launch)
  Wipe app data:      .\android\run-local.ps1 -ClearData    (signs the app out and drops its local plans)
#>
param(
    [string]$Device = "",
    [int]$Port = 5000,
    [switch]$NoBuild,
    [switch]$ClearData
)

$ErrorActionPreference = "Stop"
$appId = "app.ezpztac.unreleased.debug"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# 1. Is the backend there? A warning only: it may be started a moment later.
try {
    Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 3 | Out-Null
    Write-Host "Backend answers on 127.0.0.1:$Port"
} catch {
    Write-Warning "Nothing answers on http://127.0.0.1:$Port/. Start the backend (cd backend; python app.py) or sign-in will fail."
}

# 2. Which device. One connected is used; several need -Device.
$listed = @(& adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } | ForEach-Object { ($_ -split "\s+")[0] })
if ($Device -eq "") {
    if ($listed.Count -eq 0) { throw "No device connected. Plug in the tablet (USB debugging on) or start an emulator." }
    if ($listed.Count -gt 1) { throw "Several devices connected ($($listed -join ', ')). Pass one with -Device." }
    $Device = $listed[0]
}
Write-Host "Device: $Device"

# 3. The tunnel: the app's 127.0.0.1:<port> becomes this PC's. Lost on every reconnect, so it is made every run.
& adb -s $Device reverse "tcp:$Port" "tcp:$Port" | Out-Null
Write-Host "adb reverse tcp:$Port -> this PC"

# 4. Build and install against the local backend. The URL is the server root, as the typed calls carry their own /api paths.
if (-not $NoBuild) {
    Push-Location $root
    try {
        $env:ANDROID_SERIAL = $Device
        & .\gradlew.bat installDebug "-Pezpz.apiUrl=http://127.0.0.1:$Port/"
        if ($LASTEXITCODE -ne 0) { throw "The build failed." }
    } finally { Pop-Location }
}

# 5. Optionally start clean, then launch.
if ($ClearData) { & adb -s $Device shell pm clear $appId | Out-Null; Write-Host "App data cleared" }
& adb -s $Device shell monkey -p $appId -c android.intent.category.LAUNCHER 1 2>$null | Out-Null
Write-Host "Started $appId against http://127.0.0.1:$Port/"
