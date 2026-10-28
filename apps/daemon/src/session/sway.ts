import { execFile, spawn } from "node:child_process";

export interface SwayWindow {
  id: number;
  appId: string | null;
  /** window_properties.class (XWayland: Steam, possibly Plex HTPC/JMP) */
  cls: string | null;
  workspace: string;
  pid: number;
}

export interface Sway {
  windows(): Promise<SwayWindow[]>;
  /** "home" | `app-${id}` */
  showWorkspace(name: string): Promise<void>;
  onWindowClosed(cb: (w: SwayWindow) => void): () => void;
  onWindowNew(cb: (w: SwayWindow) => void): () => void;
}

interface Node {
  id: number;
  type?: string;
  name?: string | null;
  app_id?: string | null;
  pid?: number;
  window_properties?: { class?: string | null };
  nodes?: Node[];
  floating_nodes?: Node[];
}

function toWindow(n: Node, workspace: string): SwayWindow {
  return { id: n.id, appId: n.app_id ?? null, cls: n.window_properties?.class ?? null, workspace, pid: n.pid ?? 0 };
}

function collect(n: Node, workspace: string, out: SwayWindow[]): void {
  const ws = n.type === "workspace" ? (n.name ?? workspace) : workspace;
  const kids = [...(n.nodes ?? []), ...(n.floating_nodes ?? [])];
  if (kids.length === 0 && (n.app_id !== undefined || n.window_properties) && n.type !== "workspace" && n.type !== "output" && n.type !== "root") {
    out.push(toWindow(n, ws));
    return;
  }
  for (const k of kids) collect(k, ws, out);
}

export function parseTree(tree: Node): SwayWindow[] {
  const out: SwayWindow[] = [];
  collect(tree, "", out);
  return out;
}

function swaymsg(args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile("swaymsg", args, { maxBuffer: 16 * 1024 * 1024 }, (err, stdout) => (err ? reject(err) : resolve(stdout)));
  });
}

export function createSway(): Sway {
  const newCbs = new Set<(w: SwayWindow) => void>();
  const closeCbs = new Set<(w: SwayWindow) => void>();
  let sub: ReturnType<typeof spawn> | null = null;

  function ensureSubscribed(): void {
    if (sub) return;
    const p = spawn("swaymsg", ["-t", "subscribe", "-m", '["window"]'], { stdio: ["ignore", "pipe", "inherit"] });
    sub = p;
    let buf = "";
    p.stdout!.on("data", (chunk: Buffer) => {
      buf += chunk.toString("utf8");
      let i: number;
      while ((i = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line) continue;
        try {
          const ev = JSON.parse(line) as { change: string; container: Node };
          const w = toWindow(ev.container, "");
          if (ev.change === "new") newCbs.forEach((cb) => cb(w));
          else if (ev.change === "close") closeCbs.forEach((cb) => cb(w));
        } catch (e) {
          console.error("vela: bad sway event", (e as Error).message);
        }
      }
    });
    p.on("exit", () => {
      sub = null;
      if (newCbs.size || closeCbs.size) setTimeout(ensureSubscribed, 1000); // sway restarted; resubscribe
    });
  }

  return {
    async windows() {
      return parseTree(JSON.parse(await swaymsg(["-t", "get_tree", "-r"])) as Node);
    },
    async showWorkspace(name) {
      await swaymsg([`workspace ${name}`]);
    },
    onWindowNew(cb) {
      newCbs.add(cb);
      ensureSubscribed();
      return () => void newCbs.delete(cb);
    },
    onWindowClosed(cb) {
      closeCbs.add(cb);
      ensureSubscribed();
      return () => void closeCbs.delete(cb);
    },
  };
}

/** For development without sway (e.g. macOS): no windows, workspace switches only log. */
export function createNoopSway(): Sway {
  return {
    async windows() {
      return [];
    },
    async showWorkspace(name) {
      console.log(`vela: [noop sway] workspace ${name}`);
    },
    onWindowNew: () => () => {},
    onWindowClosed: () => () => {},
  };
}
