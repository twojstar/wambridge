"""Keep every shipped version mirror equal to the project version.

`version` in pyproject.toml is canonical. Runtime/package surfaces still need
small mirrored literals, so this test guards the Python package, Android fallback
and foobar component against drifting independently again.
"""

import re
from pathlib import Path
from unittest import TestCase

ROOT = Path(__file__).parents[1]
PYPROJECT = ROOT / "pyproject.toml"
PYTHON_PACKAGE = ROOT / "src" / "wambridge" / "__init__.py"
ANDROID_BUILD = ROOT / "mobile" / "app" / "build.gradle.kts"
COMPONENT = ROOT / "foobar" / "foo_out_wam.cpp"


def project_version() -> str:
    for line in PYPROJECT.read_text(encoding="utf-8").splitlines():
        if line.startswith("version"):
            return line.split('"')[1]
    raise AssertionError("pyproject.toml has no version line")


def declaration() -> list[str]:
    source = COMPONENT.read_text(encoding="utf-8")
    start = source.index("DECLARE_COMPONENT_VERSION(")
    block = source[start : source.index(");", start)]
    return re.findall('"([^"]*)"', block)


def python_package_version() -> str:
    source = PYTHON_PACKAGE.read_text(encoding="utf-8")
    match = re.search(r'^__version__ = "([^"]+)"$', source, re.MULTILINE)
    if match is None:
        raise AssertionError("wambridge.__version__ is missing")
    return match.group(1)


def android_fallback_version() -> str:
    source = ANDROID_BUILD.read_text(encoding="utf-8")
    start = source.index("val wamVersionName")
    block = source[start : source.index("val wamVersionCode", start)]
    match = re.search(r'\?:\s*"([^"]+)"', block)
    if match is None:
        raise AssertionError("Android wamVersionName fallback is missing")
    return match.group(1)


class ComponentVersionTests(TestCase):
    def test_component_version_matches_the_project(self) -> None:
        name, version, *about = declaration()
        self.assertEqual(name, "WAM Bridge Output")
        self.assertEqual(
            version,
            project_version(),
            "the component version drifted from pyproject.toml again",
        )

    def test_python_package_version_matches_the_project(self) -> None:
        self.assertEqual(python_package_version(), project_version())

    def test_android_fallback_version_matches_the_project(self) -> None:
        self.assertEqual(android_fallback_version(), project_version())

    def test_the_about_box_says_where_this_came_from(self) -> None:
        # Asked for directly: the repository is not discoverable from a DLL
        # sitting in a components folder unless the component says so.
        about = "".join(declaration()[2:])
        self.assertIn("https://github.com/twojstar/wambridge", about)
        self.assertIn("Copyright", about)
        self.assertIn("2026 trvny", about)
        self.assertIn("ISC", about)

    def test_the_source_stays_ascii(self) -> None:
        # The copyright sign is written as explicit UTF-8 bytes rather than as
        # a character, so the file does not depend on the compiler being told
        # what encoding it is in.
        raw = COMPONENT.read_bytes()
        self.assertTrue(all(byte < 128 for byte in raw))
