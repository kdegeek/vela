import { loadConfig } from "./config.js";
import { loadManifests } from "./manifests.js";
import { buildServer } from "./server.js";

const PORT = 7300;
const config = loadConfig(process.env.VELA_CONFIG ?? "/etc/vela/vela.toml");
const manifests = loadManifests(config.manifestDirs);
const app = buildServer({ config, manifests });

app.listen({ host: config.server.listen, port: PORT }).then(
  () => console.log(`vela daemon listening on ${config.server.listen}:${PORT}`),
  (e) => {
    console.error(e);
    process.exit(1);
  },
);
