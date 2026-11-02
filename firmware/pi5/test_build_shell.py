"""Exercise the real wrapper's AOSP shell boundary with an optional-variable fixture."""
from pathlib import Path
import subprocess
import tempfile
import unittest


class BuildShellTests(unittest.TestCase):
    def run_aosp_boundary(self, make_status):
        wrapper = (Path(__file__).parent / 'build.sh').read_text()
        boundary = wrapper[wrapper.index('# AOSP\'s'):wrapper.index('# Upstream uses')]
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / 'build').mkdir()
            (source / 'out/target/product/rpi5').mkdir(parents=True)
            (source / 'build/envsetup.sh').write_text('''
realpath() { test "$1" = -m; test "$2" = --; printf '%s\\n' "$3"; }
lunch() {
    TARGET_PRODUCT=vela_rpi5_tv
    ANDROID_PRODUCT_OUT="$source_dir/out/target/product/rpi5"
}
make() {
    # Matches the pinned shell_utils.sh post-build logger's unset expansion.
    test -z "$SOONG_INCREMENTAL_ANALYSIS"
    printf 'MAKE_ARGS:%s\\n' "$*"
    return "$fixture_status"
}
''')
            return subprocess.run(
                ['bash', '-c', 'set -euo pipefail; source_dir=$1; fixture_status=$2; '
                 + boundary + '\ncase $- in *u*) ;; *) exit 99;; esac; echo ASSEMBLY_BOUNDARY_REACHED',
                 'pi5-shell-fixture', str(source), str(make_status)],
                cwd=source, capture_output=True, text=True)

    def test_optional_aosp_variable_does_not_abort_successful_make(self):
        result = self.run_aosp_boundary(0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('MAKE_ARGS:bootimage systemimage vendorimage -j4', result.stdout)
        self.assertIn('ASSEMBLY_BOUNDARY_REACHED', result.stdout)

    def test_failed_make_does_not_continue_to_assembly(self):
        result = self.run_aosp_boundary(13)
        self.assertEqual(result.returncode, 13, result.stderr)
        self.assertNotIn('ASSEMBLY_BOUNDARY_REACHED', result.stdout)


if __name__ == '__main__':
    unittest.main()
