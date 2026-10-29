import "./style.css";
import type { JellyfinServer, SetupKey, SetupProgress } from "@vela/shared";
import { ApiError, countdown, createClient, LockedError, PinError, pinFromHash, UnreachableError, waitForCommit } from "./api.js";
import { h, svg } from "./dom.js";

type View = "loading" | "unreachable" | "pin" | "media" | "ssh" | "done" | "committing" | "finished" | "stuck";

let pin: string | null = pinFromHash(location.hash);
let progress: SetupProgress | null = null;
let view: View = "loading";
/** A step the owner went back to from the summary; cleared once they answer it. */
let editing: "media" | "ssh" | null = null;
/** Shown on the PIN screen: why the PIN is asked for again, or the lockout. */
let pinNote: string | null = null;
let lockedFor = 0;
let stopLock: (() => void) | undefined;

const client = createClient(() => pin);
const app = document.getElementById("app")!;
const nav = document.querySelector<HTMLElement>(".progress")!;

// ---------- Rendering ----------

function screenFor(p: SetupProgress): View {
  if (p.step === "committing") return "committing";
  return editing ?? p.step;
}

function show(next: View) {
  const moved = next !== view;
  view = next;
  app.replaceChildren(SCREENS[view]());
  renderNav();
  if (moved) {
    scrollTo({ top: 0 });
    app.focus({ preventScroll: true });
  }
}

/** Media → SSH access → Finish, as three stars like the TV's. Answered steps can be tapped to change them. */
function renderNav() {
  const order = ["media", "ssh", "done"] as const;
  const labels = { media: "Media", ssh: "SSH access", done: "Finish" };
  const hidden = view === "loading" || view === "unreachable" || view === "pin";
  nav.hidden = hidden;
  if (hidden) return nav.replaceChildren();
  const at = view === "media" ? 0 : view === "ssh" ? 1 : 2;
  const locked = view === "committing" || view === "finished";
  nav.replaceChildren(
    h(
      "ol",
      {},
      order.map((step, i) => {
        const state = view === "finished" || i < at ? "done" : i === at ? "current" : "pending";
        const label = [h("span", { class: "star", "aria-hidden": "true" }), h("span", { class: "label" }, labels[step])];
        const answered = progress && (step === "media" ? progress.media : step === "ssh" ? progress.keys.length || progress.sshSkipped : progress.step === "done");
        const canGo = !locked && state !== "current" && answered && step !== "done";
        return h(
          "li",
          { class: state, "aria-current": state === "current" ? "step" : undefined },
          canGo ? h("button", { type: "button", onclick: () => edit(step as "media" | "ssh") }, label) : h("span", { class: "step" }, label),
        );
      }),
    ),
  );
}

function edit(step: "media" | "ssh") {
  editing = step;
  show(step);
}

// ---------- Talking to the box ----------

/** Loads the wizard's progress and shows where it stands; PIN problems go to the PIN screen. */
async function load() {
  try {
    progress = await client.state();
    show(screenFor(progress));
    if (progress.step === "committing") void afterFinish();
  } catch (e) {
    if (!handlePinProblem(e)) show("unreachable");
  }
}

/** True when `e` was about the PIN: the PIN screen takes over. */
function handlePinProblem(e: unknown): boolean {
  if (e instanceof LockedError) {
    // Locking out also replaced the PIN on the TV, so the one we had is stale.
    pin = null;
    stopLock?.();
    pinNote = e.message;
    lockedFor = e.seconds;
    show("pin");
    stopLock = countdown(
      e.seconds,
      (left) => {
        lockedFor = left;
        if (view === "pin") document.querySelector(".lock-count")?.replaceChildren(String(left));
      },
      () => {
        lockedFor = 0;
        pinNote = "Enter the new PIN shown on the TV.";
        if (view === "pin") show("pin");
      },
    );
    return true;
  }
  if (e instanceof PinError) {
    pin = null;
    pinNote = e.message;
    show("pin");
    return true;
  }
  return false;
}

/** Runs `fn` with `button` busy; a PIN problem moves to the PIN screen, anything else lands in `note`. */
async function act(button: HTMLButtonElement, note: HTMLElement, fn: () => Promise<void>) {
  if (button.getAttribute("aria-busy") === "true") return;
  button.setAttribute("aria-busy", "true");
  button.disabled = true;
  note.replaceChildren();
  try {
    await fn();
  } catch (e) {
    if (handlePinProblem(e)) return;
    const message = e instanceof ApiError || e instanceof UnreachableError ? e.message : "Something went wrong on this page. Reload it and try again.";
    note.replaceChildren(h("p", { class: "note error", role: "alert" }, message));
  } finally {
    button.removeAttribute("aria-busy");
    button.disabled = false;
  }
}

async function answered(p: SetupProgress) {
  progress = p;
  editing = null;
  show(screenFor(p));
}

async function afterFinish() {
  const r = await waitForCommit(client).catch((e: unknown) => {
    if (handlePinProblem(e)) return null;
    return { kind: "stuck" as const };
  });
  if (!r) return;
  if (r.kind === "gone") show("finished");
  else if (r.kind === "stuck") show("stuck");
  else void answered(r.progress);
}

// ---------- Pieces ----------

const title = (text: string) => h("h1", {}, text);
const lede = (...text: (string | Node)[]) => h("p", { class: "lede" }, ...text);
const primary = (label: string, attrs: Record<string, unknown> = {}) => h("button", { class: "primary", type: "submit", ...attrs }, h("span", {}, label));
const quiet = (label: string, onclick: () => void) => h("button", { class: "quiet", type: "button", onclick }, label);
const noteSlot = () => h("div", { class: "note-slot", "aria-live": "polite" });

function field(label: string, input: HTMLInputElement | HTMLTextAreaElement, hint?: string) {
  return h("label", { class: "field" }, h("span", { class: "field-label" }, label), input, hint ? h("span", { class: "hint" }, hint) : null);
}

/** "192.168.1.5:8096" → "http://192.168.1.5:8096", as the box reads it; shown in full before the password. */
function normalizeUrl(text: string): string {
  const t = text.trim().replace(/\/+$/, "");
  if (!t) return "";
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(t) ? t : `http://${t}`;
}

function keyCard(k: SetupKey, control?: Node) {
  return h(
    "div",
    { class: "key" },
    control ?? null,
    h("span", { class: "key-type" }, k.type),
    h("span", { class: "key-fp" }, k.fingerprint),
    k.comment ? h("span", { class: "key-comment" }, k.comment) : null,
  );
}

// ---------- Screens ----------

const SCREENS: Record<View, () => Node> = {
  loading: () => h("section", { class: "screen center" }, h("span", { class: "pulse", "aria-hidden": "true" }), h("p", { class: "lede" }, "Connecting to your box")),

  unreachable: () => {
    const note = noteSlot();
    const retry = primary("Try again");
    return h(
      "form",
      { class: "screen", onsubmit: (e: Event) => (e.preventDefault(), void act(retry, note, load)) },
      title("Your phone can’t reach the box"),
      lede("Check that your phone is on the same Wi-Fi as the box and that the TV still shows the setup code, then try again."),
      note,
      h("div", { class: "actions" }, retry),
    );
  },

  pin: () => {
    const note = noteSlot();
    const input = h("input", {
      class: "pin-input",
      inputmode: "numeric",
      autocomplete: "one-time-code",
      pattern: "[0-9]{6}",
      maxlength: 6,
      required: true,
      "aria-label": "PIN",
      disabled: lockedFor > 0,
      placeholder: "000000",
    });
    input.addEventListener("input", () => (input.value = input.value.replace(/\D/g, "").slice(0, 6)));
    const go = primary("Continue", { disabled: lockedFor > 0 });
    const form = h(
      "form",
      {
        class: "screen",
        onsubmit: (e: Event) => {
          e.preventDefault();
          if (!/^\d{6}$/.test(input.value)) {
            note.replaceChildren(h("p", { class: "note error", role: "alert" }, "The PIN is the 6 digits on the TV."));
            return;
          }
          pin = input.value;
          pinNote = null;
          void act(go, note, load);
        },
      },
      title("Enter the PIN on your TV"),
      lede("It's the six digits under the address on the TV screen."),
      field("PIN", input),
      lockedFor > 0
        ? h("p", { class: "note error", role: "alert" }, pinNote ?? "", " ", h("span", { class: "lock-line" }, "Unlocks in ", h("span", { class: "lock-count" }, String(lockedFor)), " s."))
        : pinNote
          ? h("p", { class: "note", role: "status" }, pinNote)
          : null,
      note,
      h("div", { class: "actions" }, go),
    );
    if (lockedFor === 0) queueMicrotask(() => input.focus());
    return form;
  },

  media: () => {
    const current = progress?.media?.provider === "jellyfin" ? progress.media : null;
    let chosen: { url: string; name?: string } | null = current ? { url: current.url, name: current.server } : null;
    const note = noteSlot();

    const list = h("div", { class: "choices", role: "radiogroup", "aria-label": "Jellyfin servers on your network" });
    const manual = h("input", {
      // Text, not url: "192.168.1.5:8096" (no scheme) is a fine answer, and type=url would block the form on it.
      type: "text",
      inputmode: "url",
      autocapitalize: "off",
      autocomplete: "off",
      spellcheck: "false",
      placeholder: "http://192.168.1.5:8096",
      value: current && !current.server ? current.url : "",
    });
    const user = h("input", { autocomplete: "username", autocapitalize: "off", spellcheck: "false", required: true });
    const pass = h("input", { type: "password", autocomplete: "current-password" });
    const targetName = h("span", { class: "target-name" });
    const targetUrl = h("span", { class: "target-url" });
    const signIn = primary("Sign in");
    const signin = h(
      "fieldset",
      { class: "signin", hidden: !chosen },
      h("legend", {}, "Sign in"),
      h("p", { class: "target" }, h("span", { class: "target-label" }, "Your password goes to"), targetUrl, targetName),
      field("Username", user),
      field("Password", pass),
      note,
      h("div", { class: "actions" }, signIn),
    );

    const update = () => {
      signin.hidden = !chosen;
      targetUrl.textContent = chosen?.url ?? "";
      targetName.textContent = chosen?.name ? `Jellyfin server "${chosen.name}"` : "";
      targetName.hidden = !chosen?.name;
      for (const r of list.querySelectorAll<HTMLInputElement>("input[type=radio]")) r.checked = r.value === chosen?.url && !manual.value.trim();
    };
    manual.addEventListener("input", () => {
      const url = normalizeUrl(manual.value);
      chosen = url ? { url } : null;
      update();
    });

    const renderServers = (servers: JellyfinServer[] | null) => {
      if (servers === null) {
        list.replaceChildren(h("p", { class: "searching" }, h("span", { class: "pulse small", "aria-hidden": "true" }), "Looking for Jellyfin servers on your network"));
        return;
      }
      if (!servers.length) {
        list.replaceChildren(
          h("p", { class: "empty" }, "No Jellyfin servers answered. Type your server's address below, or ", h("button", { type: "button", class: "link", onclick: discover }, "look again"), "."),
        );
        return;
      }
      list.replaceChildren(
        ...servers.map((s) =>
          h(
            "label",
            { class: "choice" },
            h("input", {
              type: "radio",
              name: "server",
              value: s.url,
              checked: chosen?.url === s.url,
              onchange: () => {
                manual.value = "";
                chosen = { url: s.url, name: s.name };
                update();
                user.focus();
              },
            }),
            h("span", { class: "choice-name" }, s.name),
            h("span", { class: "choice-url" }, s.url),
          ),
        ),
      );
    };
    async function discover() {
      renderServers(null);
      try {
        renderServers(await client.discover());
      } catch (e) {
        if (!handlePinProblem(e)) renderServers([]);
      }
    }
    void discover();
    update();

    const skipNote = noteSlot();
    const skip = h("button", { class: "quiet", type: "button" }, "Skip for now");
    skip.addEventListener("click", () => void act(skip, skipNote, async () => answered(await client.skipMedia())));

    return h(
      "form",
      {
        class: "screen",
        onsubmit: (e: Event) => {
          e.preventDefault();
          if (!chosen) return;
          const { url } = chosen;
          void act(signIn, note, async () => {
            await client.signIn(url, user.value.trim(), pass.value);
            pass.value = "";
            await answered(await client.state());
          });
        },
      },
      title("Connect your Jellyfin server"),
      lede("Vela plays your films and shows from Jellyfin. Choose a server on your network, or type its address."),
      list,
      field("Another address", manual),
      signin,
      h("div", { class: "skip" }, h("p", {}, "No Jellyfin server? Vela shows a sample library, and you can connect one later with vela-ctl setup."), skipNote, skip),
    );
  },

  ssh: () => {
    let mode: "github" | "paste" = "github";
    const note = noteSlot();
    const body = h("div", { class: "ssh-body" });
    const tabs = h("div", { class: "segmented", role: "tablist", "aria-label": "Where your key comes from" });

    const renderTabs = () => {
      tabs.replaceChildren(
        ...(["github", "paste"] as const).map((m) =>
          h(
            "button",
            {
              type: "button",
              role: "tab",
              "aria-selected": String(m === mode),
              onclick: () => {
                mode = m;
                note.replaceChildren();
                renderTabs();
                renderBody();
              },
            },
            m === "github" ? "From GitHub" : "Paste a key",
          ),
        ),
      );
    };

    const renderBody = () => {
      if (mode === "paste") {
        const text = h("textarea", { rows: 4, autocapitalize: "off", autocomplete: "off", spellcheck: "false", placeholder: "ssh-ed25519 AAAAC3Nza… you@computer", required: true });
        const add = primary("Add key");
        body.replaceChildren(
          h(
            "form",
            {
              class: "stack",
              onsubmit: (e: Event) => {
                e.preventDefault();
                void act(add, note, async () => answered(await client.setKeys([text.value])));
              },
            },
            field("Public key", text, "One key per line. It's the .pub file, like ~/.ssh/id_ed25519.pub."),
            note,
            h("div", { class: "actions" }, add),
          ),
        );
        return;
      }
      const user = h("input", { autocapitalize: "off", autocomplete: "off", spellcheck: "false", required: true, placeholder: "octocat" });
      const find = primary("Find keys");
      const found = h("div", { class: "found" });
      body.replaceChildren(
        h(
          "form",
          {
            class: "stack",
            onsubmit: (e: Event) => {
              e.preventDefault();
              found.replaceChildren();
              void act(find, note, async () => {
                const name = user.value.trim();
                const keys = await client.githubKeys(name);
                found.replaceChildren(keyPicker(name, keys));
              });
            },
          },
          field("GitHub username", user),
          note,
          h("div", { class: "actions" }, find),
        ),
        found,
      );
    };

    const skipNote = noteSlot();
    const skip = h("button", { class: "quiet", type: "button" }, "Skip, I understand I can't change settings later");
    skip.addEventListener("click", () => void act(skip, skipNote, async () => answered(await client.skipSsh())));

    renderTabs();
    renderBody();
    return h(
      "section",
      { class: "screen" },
      title("Add your SSH key"),
      lede("With a key, you can sign in to the box from your computer to change its settings later. Check each key's fingerprint before you add it."),
      tabs,
      body,
      h(
        "div",
        { class: "skip" },
        h("p", {}, "Without a key, nobody can sign in to the box over SSH, so its settings can't be changed without reinstalling Vela."),
        skipNote,
        skip,
      ),
    );
  },

  done: () => summary(false),
  stuck: () => summary(true),

  committing: () =>
    h(
      "section",
      { class: "screen center" },
      h("span", { class: "pulse", "aria-hidden": "true" }),
      title("Finishing on your TV"),
      lede("The box is saving your settings. This takes a few seconds; keep this page open."),
    ),

  finished: () =>
    h(
      "section",
      { class: "screen center" },
      constellation(),
      title("Vela is finishing on your TV"),
      lede("Your TV goes to the home screen in a moment. You can close this page."),
    ),
};

/** The picker under a GitHub lookup: every key starts checked, the owner unticks any that aren't theirs. */
function keyPicker(user: string, keys: SetupKey[]): Node {
  if (!keys.length) return h("p", { class: "note" }, `GitHub has no public keys for ${user}. Add one on GitHub, or paste a key instead.`);
  const note = noteSlot();
  const boxes = keys.map((k) => h("input", { type: "checkbox", checked: true, value: k.key }));
  const add = primary(keys.length === 1 ? "Add this key" : `Add ${keys.length} keys`);
  const relabel = () => {
    const n = boxes.filter((b) => b.checked).length;
    add.disabled = n === 0;
    add.firstElementChild!.textContent = n === 1 ? (keys.length === 1 ? "Add this key" : "Add 1 key") : `Add ${n} keys`;
  };
  for (const b of boxes) b.addEventListener("change", relabel);
  return h(
    "form",
    {
      class: "stack",
      onsubmit: (e: Event) => {
        e.preventDefault();
        const picked = boxes.filter((b) => b.checked).map((b) => b.value);
        if (picked.length) void act(add, note, async () => answered(await client.setKeys(picked)));
      },
    },
    h("p", { class: "found-title" }, keys.length === 1 ? `1 key on GitHub for ${user}` : `${keys.length} keys on GitHub for ${user}`),
    h("div", { class: "keys" }, keys.map((k, i) => h("label", { class: "key-choice" }, keyCard(k, boxes[i])))),
    note,
    h("div", { class: "actions" }, add),
  );
}

function summary(stuck: boolean): Node {
  const p = progress!;
  const note = noteSlot();
  const finish = primary(stuck ? "Finish again" : "Finish setup");
  const media =
    p.media?.provider === "jellyfin"
      ? [h("span", { class: "value" }, `Jellyfin server "${p.media.server}"`), h("span", { class: "sub" }, p.media.url)]
      : [h("span", { class: "value" }, "Sample library"), h("span", { class: "sub" }, "Connect Jellyfin later with vela-ctl setup.")];
  const ssh = p.keys.length
    ? [h("span", { class: "value" }, p.keys.length === 1 ? "1 key" : `${p.keys.length} keys`), h("div", { class: "keys compact" }, p.keys.map((k) => keyCard(k)))]
    : [h("span", { class: "value" }, "Skipped"), h("span", { class: "sub" }, "Nobody can sign in to the box over SSH.")];
  return h(
    "form",
    {
      class: "screen",
      onsubmit: (e: Event) => {
        e.preventDefault();
        void act(finish, note, async () => {
          progress = await client.finish();
          editing = null;
          show("committing");
          void afterFinish();
        });
      },
    },
    title(stuck ? "The box didn’t take the settings" : "Ready to finish"),
    stuck
      ? h(
          "p",
          { class: "note error", role: "alert" },
          "The box is still waiting after 20 seconds, so it most likely refused them. Check that the SSH keys are valid, change an answer if needed, then finish again.",
        )
      : lede("Check your answers. When you finish, the box saves them and your TV goes to the home screen."),
    h(
      "dl",
      { class: "summary" },
      h("div", {}, h("dt", {}, "Media"), h("dd", {}, ...media), quiet("Change", () => edit("media"))),
      h("div", {}, h("dt", {}, "SSH access"), h("dd", {}, ...ssh), quiet("Change", () => edit("ssh"))),
    ),
    note,
    h("div", { class: "actions" }, finish),
  );
}

/** The finished constellation: Vela's sail, every star lit. */
function constellation(): Node {
  const el = svg("svg", { class: "finale", viewBox: "0 0 24 30", "aria-hidden": "true" });
  el.append(
    svg("path", { d: "M4 22 L12 6 L20 18 L14 24 Z", fill: "none", stroke: "currentColor", "stroke-width": 0.9, "stroke-linejoin": "round", opacity: 0.55 }),
    ...[
      [4, 22, 1.4],
      [12, 6, 1.8],
      [20, 18, 1.3],
      [14, 24, 1.1],
    ].map(([cx, cy, r]) => svg("circle", { cx: cx!, cy: cy!, r: r!, fill: "currentColor" })),
  );
  return el;
}

/** A fixed, seeded scatter of faint stars behind everything, like the TV's. */
function paintSky() {
  const sky = document.querySelector<SVGSVGElement>("svg.sky")!;
  sky.setAttribute("viewBox", "0 0 400 860");
  sky.setAttribute("preserveAspectRatio", "xMidYMin slice");
  let seed = 11;
  const rand = () => ((seed = (seed * 16807) % 2147483647) - 1) / 2147483646;
  for (let i = 0; i < 70; i++) {
    sky.append(svg("circle", { cx: (rand() * 400).toFixed(1), cy: (rand() * 860).toFixed(1), r: (0.4 + rand() * 1.1).toFixed(2), fill: "currentColor", opacity: (0.1 + rand() * 0.4).toFixed(2) }));
  }
}

paintSky();
show(pin ? "loading" : "pin");
if (pin) void load();
