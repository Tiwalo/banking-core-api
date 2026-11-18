"""Validate the v1 contract and compare it with the version at a Git revision."""

import argparse
import shutil
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = "api/openapi/banking-v1.yaml"
REDOCLY = "redocly/cli@sha256:1dfa3cff764815579004e203d3ded757f51f3d40afaf09b34985f4ba1ea4f95b"
OASDIFF = "tufin/oasdiff@sha256:5f508bc8091fe0221783c56b4e375e17b58bc9dc770c3225e081af52829cb651"


def run_validator(directory: Path, image: str, *arguments: str) -> None:
    subprocess.run(
        [
            "docker", "run", "--rm", "--network=none",
            "--mount", f"type=bind,source={directory},target=/spec,readonly",
            "--workdir", "/spec", image, *arguments,
        ],
        check=True,
    )


def git(*arguments: str) -> bytes:
    return subprocess.check_output(["git", *arguments], cwd=ROOT)


def verify(base_revision: str) -> None:
    run_validator(ROOT / "api", REDOCLY, "lint", "--config", "redocly.yaml", "banking-v1", "money-cases")

    if base_revision == "0" * 40:  # GitHub's before value for a newly created ref.
        print("Initial publication on this ref.")
        return
    base = git("rev-parse", "--verify", "--end-of-options", f"{base_revision}^{{commit}}").decode().strip()
    if not git("ls-tree", "--name-only", base, "--", CONTRACT).strip():
        print(f"No v1 contract at {base}. This is the first contract on this baseline.")
        return

    with TemporaryDirectory() as temporary:
        directory = Path(temporary).resolve()
        shutil.copyfile(ROOT / CONTRACT, directory / "current.yaml")
        (directory / "previous.yaml").write_bytes(git("show", f"{base}:{CONTRACT}"))
        run_validator(
            directory, OASDIFF, "breaking", "--fail-on", "WARN",
            "--allow-external-refs=false", "previous.yaml", "current.yaml",
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_revision", nargs="?", default="origin/main")
    try:
        verify(parser.parse_args().base_revision)
    except subprocess.CalledProcessError as failure:
        raise SystemExit(failure.returncode)
