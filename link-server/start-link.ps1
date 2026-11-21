$ErrorActionPreference = 'Stop'
$Base = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$State = Join-Path $env:APPDATA 'secure-pocket-link'
New-Item -ItemType Directory -Force -Path $State | Out-Null
$PidFile = Join-Path $State 'link.pid'
$LogFile = Join-Path $State 'link.log'
$ErrorFile = Join-Path $State 'link-error.log'
if (Test-Path $PidFile) {
  $ExistingId = Get-Content $PidFile -ErrorAction SilentlyContinue
  if ($ExistingId -and (Get-Process -Id $ExistingId -ErrorAction SilentlyContinue)) {
    Write-Output "Secure Pocket Link is already running (PID $ExistingId)."
    Start-Process 'http://127.0.0.1:8733/admin/'
    exit 0
  }
  Remove-Item $PidFile -Force
}
$Exe = Join-Path $Base 'dist\SecurePocketLink.exe'
if (Test-Path $Exe) {
  $Process = Start-Process -FilePath $Exe -WorkingDirectory $Base -WindowStyle Hidden -PassThru -RedirectStandardOutput $LogFile -RedirectStandardError $ErrorFile
} else {
  $Script = Join-Path $Base 'link-server\secure_pocket_link.py'
  $VenvPython = Join-Path $Base '.venv\Scripts\python.exe'
  $Python = if (Test-Path $VenvPython) { $VenvPython } else { 'python' }
  $Process = Start-Process -FilePath $Python -ArgumentList ('-u "{0}"' -f $Script) -WorkingDirectory $Base -WindowStyle Hidden -PassThru -RedirectStandardOutput $LogFile -RedirectStandardError $ErrorFile
}
$Process.Id | Set-Content $PidFile
$Ready = $false
for ($Attempt = 0; $Attempt -lt 20; $Attempt++) {
  if (-not (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue)) { break }
  try {
    Invoke-WebRequest 'http://127.0.0.1:8733/admin/' -UseBasicParsing -TimeoutSec 1 | Out-Null
    $Ready = $true
    break
  } catch {
    Start-Sleep -Seconds 1
  }
}
if (-not $Ready) {
  Stop-Process -Id $Process.Id -ErrorAction SilentlyContinue
  Get-Content $ErrorFile -ErrorAction SilentlyContinue
  Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
  throw 'Secure Pocket Link did not start. See the error above.'
}
Start-Process 'http://127.0.0.1:8733/admin/'
Write-Output "Secure Pocket Link started in the background (PID $($Process.Id))."
Write-Output "Stop it with link-server\stop-link.ps1. Log: $LogFile"
