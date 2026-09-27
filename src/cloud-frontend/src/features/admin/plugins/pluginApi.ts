import { request } from "../../../api";

export type RuntimeMode = "LOCAL" | "DOCKER";
export type RuntimeState = "STARTING" | "RUNNING" | "STOPPING" | "STOP_FAILED" | "STOPPED";
export type HealthState = "UNCHECKED" | "CHECKING" | "HEALTHY" | "UNHEALTHY" | "ISOLATED" | "INACTIVE";
export interface PluginView {
  id: string; name: string; version: string; providerIds: string[];
  declaredModes: RuntimeMode[]; configuredModes: RuntimeMode[];
  managementState: "DISABLED" | "ENABLED"; selectedMode: RuntimeMode | null; revision: number;
  runtimeState: RuntimeState | null;
  health: { runtimeId: string; status: HealthState; consecutiveFailures: number; checkedAt: string | null } | null;
  installationKind: "MANIFEST_REGISTRATION"; persistence: "PROCESS_MEMORY";
}
export interface DisableResult { plugin: PluginView; stopped: boolean }
const root = "/api/admin/plugins";
const path = (id: string) => `${root}/${encodeURIComponent(id)}`;
export const pluginApi = {
  list: (signal?: AbortSignal) => request<PluginView[]>(root, { signal }),
  // 原文提交：不可 JSON.parse 后再 stringify，否则重复字段会被静默合并。
  install: (manifest: string) => request<PluginView>(root, { method: "POST", body: manifest }, [201]),
  enable: (id: string, mode: RuntimeMode) => request<PluginView>(`${path(id)}/enable?mode=${mode}`, { method: "POST" }),
  disable: (id: string) => request<DisableResult>(`${path(id)}/disable?waitMillis=1000`, { method: "POST" }, [200, 202]),
  uninstall: (id: string) => request<void>(path(id), { method: "DELETE" }),
  check: (id: string) => request<PluginView>(`${path(id)}/health-check`, { method: "POST" }),
};
export const pluginQueryKey = ["admin-plugins"] as const;
export const errorText = (error: unknown) => error instanceof Error ? error.message : "请求失败，请刷新实际状态后重试";
