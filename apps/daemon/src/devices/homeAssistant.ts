export type TvCommand = "power_on" | "power_off" | "volume_up" | "volume_down" | "mute";
export const TV_COMMANDS: TvCommand[] = ["power_on", "power_off", "volume_up", "volume_down", "mute"];

export interface TvController {
  command(cmd: TvCommand): Promise<void>;
  /** Home Assistant's state string for the entity, e.g. "on", "off", "unavailable". */
  state(): Promise<string>;
}

export interface HaOptions {
  url: string;
  token: string;
  fetch?: typeof fetch;
}

export const TV_UNREACHABLE_MESSAGE = "The TV didn't respond. Check that it's on the network.";

export class TvUnreachable extends Error {
  constructor(detail?: string) {
    super(TV_UNREACHABLE_MESSAGE);
    this.name = "TvUnreachable";
    if (detail) this.cause = detail;
  }
}

const SERVICES: Record<TvCommand, { service: string; extra?: Record<string, unknown> }> = {
  power_on: { service: "turn_on" },
  power_off: { service: "turn_off" },
  volume_up: { service: "volume_up" },
  volume_down: { service: "volume_down" },
  // is_volume_muted is filled in per call: mute toggles.
  mute: { service: "volume_mute" },
};

async function call(ha: HaOptions, path: string, init: RequestInit): Promise<Response> {
  const f = ha.fetch ?? fetch;
  let res: Response;
  try {
    res = await f(`${ha.url.replace(/\/+$/, "")}${path}`, {
      ...init,
      headers: { Authorization: `Bearer ${ha.token}`, "Content-Type": "application/json" },
      signal: AbortSignal.timeout(5000),
    });
  } catch (e) {
    throw new TvUnreachable((e as Error).message);
  }
  if (!res.ok) throw new TvUnreachable(`HTTP ${res.status}`);
  return res;
}

export function haTv(entity: string, ha: HaOptions): TvController {
  const read = async () => {
    const res = await call(ha, `/api/states/${encodeURIComponent(entity)}`, { method: "GET" });
    return (await res.json()) as { state?: unknown; attributes?: { is_volume_muted?: unknown } };
  };
  return {
    async command(cmd) {
      const { service, extra } = SERVICES[cmd];
      let body: Record<string, unknown> = { entity_id: entity, ...extra };
      if (cmd === "mute") body = { ...body, is_volume_muted: (await read()).attributes?.is_volume_muted !== true };
      await call(ha, `/api/services/media_player/${service}`, { method: "POST", body: JSON.stringify(body) });
    },
    async state() {
      return String((await read()).state ?? "unavailable");
    },
  };
}

/** "<state><unit>" for a temperature sensor, or undefined when the sensor has no usable reading. */
export async function haInsideTemp(entity: string, ha: HaOptions): Promise<string | undefined> {
  const res = await call(ha, `/api/states/${encodeURIComponent(entity)}`, { method: "GET" });
  const j = (await res.json()) as { state?: string; attributes?: { unit_of_measurement?: string } };
  if (!j.state || j.state === "unavailable" || j.state === "unknown") return undefined;
  return `${j.state}${j.attributes?.unit_of_measurement ?? ""}`;
}
