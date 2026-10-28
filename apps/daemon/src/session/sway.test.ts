import { describe, expect, it } from "vitest";
import { parseTree } from "./sway.js";

// Trimmed from `swaymsg -t get_tree -r`.
const tree = {
  id: 1, type: "root", name: "root",
  nodes: [
    { id: 2, type: "output", name: "__i3", nodes: [
      { id: 3, type: "con", name: "__i3_scratch", nodes: [
        { id: 4, type: "workspace", name: "__i3_scratch", nodes: [], floating_nodes: [] },
      ] },
    ] },
    { id: 10, type: "output", name: "HDMI-A-1", nodes: [
      { id: 11, type: "workspace", name: "home", nodes: [
        { id: 20, type: "con", app_id: "chromium", pid: 100, window_properties: undefined, nodes: [], floating_nodes: [] },
      ], floating_nodes: [] },
      { id: 12, type: "workspace", name: "app-steam", nodes: [
        { id: 21, type: "con", name: null, nodes: [
          { id: 22, type: "con", app_id: null, pid: 200, window_properties: { class: "steam", instance: "steam" }, nodes: [], floating_nodes: [] },
        ], floating_nodes: [] },
      ], floating_nodes: [
        { id: 23, type: "floating_con", app_id: "tv.plex.PlexHTPC", pid: 300, nodes: [], floating_nodes: [] },
      ] },
      { id: 13, type: "workspace", name: "app-empty", nodes: [], floating_nodes: [] },
    ] },
  ],
};

describe("parseTree", () => {
  it("extracts Wayland, XWayland, nested and floating windows with workspace names", () => {
    expect(parseTree(tree)).toEqual([
      { id: 20, appId: "chromium", cls: null, workspace: "home", pid: 100 },
      { id: 22, appId: null, cls: "steam", workspace: "app-steam", pid: 200 },
      { id: 23, appId: "tv.plex.PlexHTPC", cls: null, workspace: "app-steam", pid: 300 },
    ]);
  });
});
