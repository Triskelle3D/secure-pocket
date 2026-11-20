"""Assemble a per-platform download containing the executable and launchers."""
import argparse
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument("--name", required=True)
parser.add_argument("--executable", required=True)
args = parser.parse_args()
executable = root / "dist" / args.executable
archive = root / "dist" / f"SecurePocketLink-{args.name}.zip"
if not executable.is_file():
    raise SystemExit(f"Missing built executable: {executable}")

with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
    bundle.write(executable, f"dist/{executable.name}")
    bundle.write(root / "link-server" / "README.md", "link-server/README.md")
    scripts = ("start-link.sh", "stop-link.sh", "start-link.ps1", "stop-link.ps1")
    for script in scripts:
        bundle.write(root / "link-server" / script, f"link-server/{script}")
print(archive)
