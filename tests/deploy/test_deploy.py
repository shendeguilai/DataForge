import importlib.util
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("release", ROOT / "scripts/check-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
SHA = "a" * 40


class ReleaseGateTests(unittest.TestCase):
    def runs(self):
        return [dict(name=name, head_sha=SHA, head_branch="main", event="push",
                     status="completed", conclusion="success", id=i, run_number=1)
                for i, name in enumerate(("CI", "Publish production images"))]

    def test_requires_matching_successful_checks_and_actual_acr_mirror(self):
        release.validate(self.runs(), lambda _: [dict(name="mirror-acr", conclusion="success")], SHA)
        for change in ({"conclusion": "failure"}, {"status": "in_progress"},
                       {"head_sha": "b" * 40}, {"head_branch": "feature"}):
            runs = self.runs()
            runs[0].update(change)
            with self.assertRaises(ValueError):
                release.validate(runs, lambda _: [dict(name="mirror-acr", conclusion="success")], SHA)
        with self.assertRaises(ValueError):
            release.validate(self.runs(), lambda _: [dict(name="mirror-acr", conclusion="skipped")], SHA)

    def test_newer_failed_rerun_does_not_use_old_success(self):
        runs = self.runs()
        runs.append(dict(runs[0], run_attempt=2, conclusion="failure"))
        with self.assertRaises(ValueError):
            release.validate(runs, lambda _: [dict(name="mirror-acr", conclusion="success")], SHA)


class DeploymentScriptTests(unittest.TestCase):
    def shell(self, commands):
        return subprocess.run(["bash", "-c", 'source "$1"; ' + commands, "test", str(ROOT / "scripts/prod.sh")],
                              capture_output=True, text=True, timeout=10)

    def test_service_detection_consumes_all_output_and_pulls_both_generation_images(self):
        result = self.shell("""
            compose() { printf 'generation-runner\n'; for ((i=0;i<10000;i++)); do printf 'service-%s\n' "$i"; done; }
            uses_registry_images() { return 0; }
            compose_tagged() { printf '%s\n' "$*"; }
            CSP_SIM_ENABLED=true
            prepare_release_images testsha
        """)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("generation-runner generation-sandbox-image", result.stdout)
        self.assertIn("csp-judge-worker csp-sandbox-image", result.stdout)

    def test_invalid_compose_fails_before_pull(self):
        result = self.shell("""
            compose() { return 1; }
            compose_tagged() { echo UNEXPECTED_PULL; }
            CSP_SIM_ENABLED=false
            prepare_release_images testsha
        """)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("UNEXPECTED_PULL", result.stdout)

    def test_failed_database_dump_never_claims_backup_success(self):
        with tempfile.TemporaryDirectory(prefix="dataforge-backup-test-") as directory:
            result = self.shell("""
                DATAFORGE_BACKUP_DIR=$2
                POSTGRES_PASSWORD=test POSTGRES_USER=test POSTGRES_DB=test
                compose() { return 1; }
                backup_database test || exit 8
            """.replace("$2", "'" + directory + "'"))
            self.assertEqual(result.returncode, 8)
            self.assertNotIn("备份完成", result.stdout)
            self.assertFalse((pathlib.Path(directory) / "test/SHA256SUMS").exists())

    def test_backup_failure_restores_website_and_existing_judge(self):
        result = self.shell("""
            require_command() { :; }
            git() { case "$*" in *rev-parse*) printf '%040d\n' 1;; esac; }
            current_deployed_commit() { printf '%040d\n' 2; }
            ensure_execution_secret() { :; }
            prepare_release_images() { :; }
            start_database() { :; }
            wait_for_database() { :; }
            stop_optional_judge() { :; }
            backup_before_deploy() { return 1; }
            uses_registry_images() { return 0; }
            compose_tagged() { echo "tagged $*"; }
            compose() { echo "compose $*"; }
            wait_for_application() { :; }
            start_tagged_service() { echo UNEXPECTED_START; }
            CSP_SIM_ENABLED=true
            deploy
        """)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--no-deps app", result.stdout)
        self.assertIn("start csp-studio generation-runner", result.stdout)
        self.assertIn("start csp-judge-worker", result.stdout)
        self.assertNotIn("UNEXPECTED_START", result.stdout)

    def test_pinned_release_rejects_mismatched_checkout(self):
        result = self.shell("""
            require_command() { :; }
            git() { case "$*" in *rev-parse*) printf '%040d\n' 1;; *pull*) echo UNEXPECTED_PULL;; esac; }
            current_deployed_commit() { printf '%040d\n' 2; }
            deploy aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
        """)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("UNEXPECTED_PULL", result.stdout)

    def test_forced_ssh_entry_rejects_shell_and_injection(self):
        for command in ("", "bash", "deploy main", "deploy " + SHA + "; id", "deploy " + SHA + "\nwhoami"):
            result = subprocess.run(["bash", str(ROOT / "deploy/ssh-entry.sh")],
                                    env={"SSH_ORIGINAL_COMMAND": command}, capture_output=True, timeout=5)
            self.assertEqual(result.returncode, 64, command)


if __name__ == "__main__":
    unittest.main()
