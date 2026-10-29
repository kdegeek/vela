// Validates a vela.toml against the daemon's schema, for vela-setup-commit (root) before it installs a staged file.
// Usage: node /usr/lib/vela/daemon/check-config.js <file>. Exit 0 if valid; else prints the problem and exits 1.
import { checkConfigFile } from "./config.js";

const file = process.argv[2];
if (!file) {
  console.error("usage: check-config <vela.toml>");
  process.exit(2);
}
const problem = checkConfigFile(file);
if (problem) {
  console.error(problem);
  process.exit(1);
}
