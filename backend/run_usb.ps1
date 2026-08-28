param(
    [int]$Port = 8765,
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"
$backendRoot = $PSScriptRoot
$pythonPath = Join-Path $backendRoot ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $pythonPath -PathType Leaf)) {
    throw "Backend virtual environment is missing: $pythonPath"
}

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $sdkAdb -PathType Leaf)) {
        throw "adb was not found. Install Android platform-tools first."
    }
    $adbPath = $sdkAdb
} else {
    $adbPath = $adbCommand.Source
}

$deviceArgs = @()
if ($DeviceSerial.Trim()) {
    $deviceArgs = @("-s", $DeviceSerial.Trim())
}

$deviceState = & $adbPath @deviceArgs get-state 2>$null
if ($LASTEXITCODE -ne 0 -or $deviceState.Trim() -ne "device") {
    throw "No authorized Android device is available over USB."
}

& $adbPath @deviceArgs reverse "tcp:$Port" "tcp:$Port"
if ($LASTEXITCODE -ne 0) {
    throw "Could not create the adb reverse tunnel."
}

$env:JUGGLUCO_ALLOWED_HOSTS = "127.0.0.1,localhost,testserver"
$env:OPENROUTER_AUDIO_MODEL = "openai/whisper-large-v3-turbo"
Write-Host "USB tunnel ready. Use http://127.0.0.1:$Port in the Android app."
& $pythonPath -m uvicorn app.main:app --app-dir $backendRoot `
    --host 127.0.0.1 --port $Port
