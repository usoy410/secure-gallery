"""Exercise the shell/adb transfer contract without claiming to emulate Android storage."""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

FAKE_ADB = r"""
import json
import os
import subprocess
import sys
from pathlib import Path

root = Path(os.environ["FAKE_REMOTE"])
args = sys.argv[1:]
if args[0] in ("shell", "exec-out"):
    args = args[1:]
if args[0] == "-T":
    args = args[1:]
if args[0] == "run-as":
    raise SystemExit(subprocess.run(args[2:], cwd=root).returncode)
assert args[:2] == ["am", "instrument"], args
assert "batch_private_storage" in args and "batch_required" in args
mode = "eval-d7" if any("exportD7VerificationResults" in a for a in args) else "eval"
if os.environ.get("FAKE_OUTCOME") != "skipped":
    images = [{"id": p.stem} for p in (root / "files" / (mode + "-input")).glob("*.jpg")]
    if os.environ.get("FAKE_OUTCOME") == "partial":
        images = []
    output = root / "files" / (mode + "-output")
    output.mkdir(parents=True)
    (output / "results.json").write_text(json.dumps({"images": images}))
print("OK (1 test)")
"""


@pytest.fixture
def helper(tmp_path, monkeypatch):
    project = tmp_path / "project"
    (project / "scripts").mkdir(parents=True)
    (project / "android").mkdir()
    source = Path(__file__).resolve().parents[1] / "scripts" / "batch_export.sh"
    script = project / "scripts" / "batch_export.sh"
    shutil.copy(source, script)
    gradle = project / "android" / "gradlew"
    gradle.write_text("#!/bin/sh\nexit 0\n")
    gradle.chmod(0o755)
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    adb = bin_dir / "adb"
    adb.write_text(f"#!{sys.executable}\n" + FAKE_ADB)
    adb.chmod(0o755)
    remote = tmp_path / "app-private"
    remote.mkdir()
    monkeypatch.setenv("PATH", str(bin_dir) + os.pathsep + os.environ["PATH"])
    monkeypatch.setenv("FAKE_REMOTE", str(remote))
    inputs = tmp_path / "input with spaces"
    inputs.mkdir()
    (inputs / "synthetic.jpg").write_bytes(b"synthetic fixture")
    return script, inputs, tmp_path / "output with spaces"


@pytest.mark.parametrize("mode", ["pipeline", "d7"])
def test_private_transfer_round_trip(helper, mode):
    script, inputs, output = helper
    result = subprocess.run(
        ["bash", str(script), str(inputs), str(output), mode], capture_output=True, text=True
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert json.loads((output / "results.json").read_text())["images"] == [{"id": "synthetic"}]


@pytest.mark.parametrize("outcome", ["skipped", "partial"])
def test_ok_line_does_not_prove_export_completion(helper, monkeypatch, outcome):
    script, inputs, output = helper
    monkeypatch.setenv("FAKE_OUTCOME", outcome)
    result = subprocess.run(
        ["bash", str(script), str(inputs), str(output)], capture_output=True, text=True
    )
    assert result.returncode != 0
    assert not output.exists()


def test_existing_output_is_preserved(helper):
    script, inputs, output = helper
    output.mkdir()
    previous = output / "results.json"
    previous.write_text("previous export")
    result = subprocess.run(
        ["bash", str(script), str(inputs), str(output)], capture_output=True, text=True
    )
    assert result.returncode != 0
    assert previous.read_text() == "previous export"
