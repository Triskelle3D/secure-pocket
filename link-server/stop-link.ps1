$ErrorActionPreference = 'Stop'
$State = Join-Path $env:APPDATA 'secure-pocket-link'
$PidFile = Join-Path $State 'link.pid'
if (-not (Test-Path $PidFile)) {
  Write-Output 'Secure Pocket Link is not running (no PID file).'
  exit 0
}
$Id = Get-Content $PidFile -ErrorAction SilentlyContinue
if ($Id -and (Get-Process -Id $Id -ErrorAction SilentlyContinue)) {
  Stop-Process -Id $Id
  Write-Output "Stopped Secure Pocket Link (PID $Id)."
} else {
  Write-Output 'Secure Pocket Link was already stopped.'
}
Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
