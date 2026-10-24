"""Release 摘要生成的离线回归检查。"""

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import generate_release_body as release


class ReleaseBodyTest(unittest.TestCase):
    def test_groups_conventional_and_unrecognized_commits(self):
        self.assertEqual(
            release._group_commits(["fix(player): resume", "feat!: new", "maintenance"]),
            {"🐛 修复": ["resume"], "✨ 新功能": ["new"], "🔧 其他": ["maintenance"]},
        )

    def test_missing_summary_still_includes_commits_and_fork_compare_link(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "release.md"
            with (
                patch.dict(os.environ, GITHUB_REF_NAME="v1.1.0", GITHUB_REPOSITORY="katohawk/newBV"),
                patch.object(release.sys, "argv", ["generate_release_body.py", str(output)]),
                patch.object(release, "_previous_tag", return_value="v1.0.0"),
                patch.object(release, "_git", return_value="fix(player): resume\n"),
                patch.object(release.os.path, "isfile", return_value=False),
            ):
                release.main()
            body = output.read_text()
            self.assertIn("- resume", body)
            self.assertIn("https://github.com/katohawk/newBV/compare/v1.0.0...v1.1.0", body)


if __name__ == "__main__":
    unittest.main()
