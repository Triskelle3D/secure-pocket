"""Build a self-contained Secure Pocket Link executable with PyInstaller."""
import os
from pathlib import Path

from PyInstaller.__main__ import run

here = Path(__file__).resolve().parent
root = here.parent
run([
    "--noconfirm",
    "--clean",
    "--onefile",
    "--name", "SecurePocketLink",
    "--add-data", f"{here / 'settings.html'}{os.pathsep}.",
    "--distpath", str(root / "dist"),
    "--workpath", str(root / "build" / "pyinstaller"),
    str(here / "secure_pocket_link.py"),
])
