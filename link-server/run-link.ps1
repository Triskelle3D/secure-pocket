$ErrorActionPreference = 'Stop'
$Repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Repo
$VenvPython = Join-Path $Repo '.venv\Scripts\python.exe'
if (Test-Path $VenvPython) {
  & $VenvPython link-server/secure_pocket_link.py @args
} else {
  python link-server/secure_pocket_link.py @args
}
