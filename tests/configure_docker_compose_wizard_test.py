import os
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "configure-docker-compose.sh"


class ConfigureDockerComposeWizardTest(unittest.TestCase):
    def run_wizard(self, inputs, args, extra_env=None):
        env = os.environ.copy()
        if extra_env:
            env.update(extra_env)
        with tempfile.NamedTemporaryFile() as stack_env_file:
            env["STACK_ENV_FILE"] = stack_env_file.name
            command = "printf %s {} | script -qec {} /dev/null".format(
                shlex.quote(inputs),
                shlex.quote(" ".join([shlex.quote(str(SCRIPT_PATH)), *[shlex.quote(str(arg)) for arg in args]])),
            )
            return subprocess.run(
                command,
                shell=True,
                cwd=REPO_ROOT,
                env=env,
                text=True,
                capture_output=True,
                timeout=120,
            )

    def test_interactive_self_signed_mode_generates_requested_sans(self):
        with tempfile.TemporaryDirectory() as td:
            temp_dir = Path(td)
            env_file = temp_dir / "out.env"
            override_file = temp_dir / "docker-compose.override.yml"
            cert_path = temp_dir / "ssl" / "frontend.crt"
            key_path = temp_dir / "ssl" / "frontend.key"

            result = self.run_wizard(
                "2\nn\n127.0.0.1\n9443\n127.0.0.1\n18089\nlocalhost,127.0.0.1,app.example.com\n",
                [
                    "--env-file", env_file,
                    "--compose-override", override_file,
                    "--cert-path", cert_path,
                    "--key-path", key_path,
                    "--force",
                ],
            )

            self.assertEqual(result.returncode, 0, msg=result.stdout + result.stderr)
            self.assertTrue(env_file.exists())
            self.assertTrue(override_file.exists())
            self.assertTrue(cert_path.exists())
            self.assertTrue(key_path.exists())
            env_text = env_file.read_text()
            override_text = override_file.read_text()
            self.assertIn("ADMIN_FRONTEND_TLS_ENABLED='true'", env_text)
            self.assertIn("ADMIN_FRONTEND_TLS_SELF_SIGNED='true'", env_text)
            self.assertIn("ADMIN_FRONTEND_TLS_PORT='9443'", env_text)
            self.assertIn("ADMIN_FRONTEND_TLS_SAN_NAMES='localhost,127.0.0.1,app.example.com'", env_text)
            self.assertIn(str(cert_path), env_text)
            self.assertIn(str(key_path), env_text)
            self.assertIn(str(cert_path), override_text)
            self.assertIn(str(key_path), override_text)
            self.assertNotIn("admin_spring", override_text)

            san_dump = subprocess.run(
                ["openssl", "x509", "-in", str(cert_path), "-text", "-noout"],
                text=True,
                capture_output=True,
                check=True,
            ).stdout
            self.assertIn("DNS:localhost", san_dump)
            self.assertIn("IP Address:127.0.0.1", san_dump)
            self.assertIn("DNS:app.example.com", san_dump)

    def test_interactive_third_party_mode_prompts_for_cert_paths_and_backend_exposure(self):
        with tempfile.TemporaryDirectory() as td:
            temp_dir = Path(td)
            env_file = temp_dir / "out.env"
            override_file = temp_dir / "docker-compose.override.yml"
            cert_path = temp_dir / "third-party.crt"
            key_path = temp_dir / "third-party.key"
            cert_path.write_text("cert")
            key_path.write_text("key")

            result = self.run_wizard(
                f"3\ny\n127.0.0.1\n9443\n0.0.0.0\n18080\n{cert_path}\n{key_path}\n",
                ["--env-file", env_file, "--compose-override", override_file, "--force"],
                extra_env={
                    "ADMIN_FRONTEND_TLS_CERT_PATH": "",
                    "ADMIN_FRONTEND_TLS_KEY_PATH": "",
                },
            )

            self.assertEqual(result.returncode, 0, msg=result.stdout + result.stderr)
            self.assertIn("Certificate path:", result.stdout)
            self.assertIn("Private key path:", result.stdout)
            env_text = env_file.read_text()
            override_text = override_file.read_text()
            self.assertIn("ADMIN_FRONTEND_TLS_ENABLED='true'", env_text)
            self.assertIn("ADMIN_FRONTEND_TLS_SELF_SIGNED='false'", env_text)
            self.assertIn(f"ADMIN_FRONTEND_TLS_CERT_PATH='{cert_path}'", env_text)
            self.assertIn(f"ADMIN_FRONTEND_TLS_KEY_PATH='{key_path}'", env_text)
            self.assertIn('      - "0.0.0.0:18080:8080"', override_text)

    def test_interactive_self_signed_mode_respects_overwrite_rejection(self):
        with tempfile.TemporaryDirectory() as td:
            temp_dir = Path(td)
            env_file = temp_dir / "out.env"
            override_file = temp_dir / "docker-compose.override.yml"
            cert_path = temp_dir / "ssl" / "frontend.crt"
            key_path = temp_dir / "ssl" / "frontend.key"
            cert_path.parent.mkdir(parents=True, exist_ok=True)
            cert_path.write_text("old cert")
            key_path.write_text("old key")

            result = self.run_wizard(
                "2\nn\n127.0.0.1\n9443\n127.0.0.1\n18089\nlocalhost,127.0.0.1\nn\n",
                [
                    "--env-file", env_file,
                    "--compose-override", override_file,
                    "--cert-path", cert_path,
                    "--key-path", key_path,
                ],
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Overwrite them? [y/N]: Aborted without changing existing TLS certificate files.", result.stdout)
            self.assertEqual(cert_path.read_text(), "old cert")
            self.assertEqual(key_path.read_text(), "old key")
            self.assertFalse(env_file.exists())
            self.assertFalse(override_file.exists())


if __name__ == "__main__":
    unittest.main()
