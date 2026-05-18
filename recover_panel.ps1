# Recover a QBIC TD-1070 panel stuck in a boot loop after commissioning.
#
# Cause: Android 12's PackageManagerService crashes on boot when it finds a
# signature mismatch between the new priv-app and an old /data/app entry.
# This removes the conflicting entry and clears the package database so Android
# rebuilds it clean on the next boot.
#
# After recovery, re-run commission_panel.ps1 to restore permissions.
#
# Usage:
#   .\recover_panel.cmd                        # single USB device
#   .\recover_panel.ps1 -DeviceId 14Z270X400016  # specific device serial

param(
    [string]$DeviceId = ""
)

$ErrorActionPreference = 'Stop'
$Package = "au.com.theavitgroup.qbiccontrol"

function Step { param([string]$Msg); Write-Host ""; Write-Host "==> $Msg" -ForegroundColor Cyan }
function AdbS { param([string[]]$A); if ($DeviceId) { & adb.exe -s $DeviceId @A } else { & adb.exe @A } }

# ── Detect device ─────────────────────────────────────────────────────────────

Step "Checking ADB device"
$deviceLines = & adb devices | Select-String -Pattern "^\S+\s+device$"
if (-not $deviceLines) {
    Write-Host "No device found. Check USB connection and that USB debugging is enabled." -ForegroundColor Red
    exit 1
}
if ($deviceLines.Count -gt 1 -and -not $DeviceId) {
    Write-Host "Multiple devices connected. Specify one with -DeviceId:" -ForegroundColor Yellow
    $deviceLines | ForEach-Object { Write-Host "  $($_.Line.Split()[0])" }
    exit 1
}
if (-not $DeviceId) {
    $DeviceId = $deviceLines[0].Line.Split()[0]
}
Write-Host "Device: $DeviceId"

# ── Confirm the panel is in the expected boot-loop state ──────────────────────

Step "Checking boot state"
$bootCompleted = AdbS "shell","getprop","sys.boot_completed"
$bootAnim      = AdbS "shell","getprop","init.svc.bootanim"
if ($bootCompleted -match "1") {
    Write-Host "Device reports boot_completed=1 — panel does not appear to be boot-looping." -ForegroundColor Yellow
    Write-Host "Continuing anyway in case you want to force a clean recovery."
} else {
    Write-Host "boot_completed=$bootCompleted  bootanim=$bootAnim — boot loop confirmed."
}

# ── Gain root ─────────────────────────────────────────────────────────────────

Step "Gaining root access"
$rootOut = AdbS "root"
if ($rootOut -match "cannot run as root") {
    Write-Host "adb root unavailable on this build." -ForegroundColor Red
    exit 1
}
Start-Sleep -Seconds 3
AdbS "wait-for-device" | Out-Null
Write-Host "Root OK."

# ── Remove conflicting /data/app entry ────────────────────────────────────────

Step "Locating user-installed APK in /data/app"
$appDir = AdbS "shell","find","/data/app","-name","${Package}*","-maxdepth","3","2>/dev/null"
if ($appDir) {
    Write-Host "Found: $appDir"
    AdbS "shell","rm","-rf",$appDir.Trim()
    Write-Host "Deleted."
} else {
    Write-Host "No /data/app entry found — may have already been removed."
}

# ── Clear package database ────────────────────────────────────────────────────

Step "Clearing package database (packages.xml)"
# packages.xml on this device is binary protobuf — not directly editable.
# Deleting it causes Android to rebuild it clean from a full package scan on
# next boot; system priv-apps are re-registered with no signature conflict.
$xmlExists = AdbS "shell","ls","/data/system/packages.xml","2>/dev/null"
if ($xmlExists) {
    AdbS "shell","rm","/data/system/packages.xml"
    Write-Host "Deleted packages.xml."
} else {
    Write-Host "packages.xml not found — skipping."
}

# ── Reboot and wait ───────────────────────────────────────────────────────────

Step "Rebooting"
AdbS "reboot"
Write-Host "Waiting for device to come back online..."
AdbS "wait-for-device"

Write-Host "Waiting for boot to complete (up to 4 min)..."
$booted = $false
for ($i = 0; $i -lt 80; $i++) {
    Start-Sleep -Seconds 3
    $val = AdbS "shell","getprop","sys.boot_completed"
    if ($val -match "1") { $booted = $true; break }
    if ($i % 5 -eq 0) { Write-Host "  ...still booting ($($i * 3)s elapsed)" }
}
if (-not $booted) {
    Write-Host "Boot timed out — the panel may need more time or the issue persists." -ForegroundColor Yellow
    exit 1
}

# ── Verify ────────────────────────────────────────────────────────────────────

Step "Verifying"
$pmPath   = AdbS "shell","pm","path",$Package
$bootAnim = AdbS "shell","getprop","init.svc.bootanim"

Write-Host "Package path : $pmPath"
Write-Host "Boot anim    : $bootAnim"

if ($pmPath -match "/system/priv-app" -and $bootAnim -eq "stopped") {
    Write-Host ""
    Write-Host "================================================" -ForegroundColor Green
    Write-Host " Recovery complete." -ForegroundColor Green
    Write-Host " Re-run commission_panel.ps1 to restore permissions." -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green
} else {
    Write-Host "Unexpected state after reboot — check logcat for further errors." -ForegroundColor Yellow
    AdbS "shell","logcat","-d","-t","100" | Select-String -Pattern "FATAL|signature mismatch" | Select-Object -Last 10
}
