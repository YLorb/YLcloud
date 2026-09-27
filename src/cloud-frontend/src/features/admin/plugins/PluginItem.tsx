import { Button } from "../../../components/ui/Button";
import type { HealthState, PluginView, RuntimeState } from "./pluginApi";
export type PluginAction = "enable" | "disable" | "uninstall" | "check";
export const actionLabels: Record<PluginAction, string> = { enable: "启用", disable: "禁用并清理", uninstall: "卸载登记", check: "检查健康" };
const runtimeLabels: Record<RuntimeState, string> = { STARTING: "启动中", RUNNING: "运行中", STOPPING: "停止中", STOP_FAILED: "释放失败", STOPPED: "已停止" };
const healthLabels: Record<HealthState, string> = { UNCHECKED: "尚未验证", CHECKING: "检查中", HEALTHY: "验证通过", UNHEALTHY: "验证失败", ISOLATED: "已隔离", INACTIVE: "不接收调用" };
export function PluginItem({ plugin, busy, onAction }: { plugin: PluginView; busy: boolean; onAction: (action: PluginAction) => void }) {
  const stopped = !plugin.runtimeState || plugin.runtimeState === "STOPPED";
  const disabled = plugin.managementState === "DISABLED";
  const cleanup = plugin.runtimeState === "STOPPING" || plugin.runtimeState === "STOP_FAILED";
  const configured = plugin.configuredModes.some(mode => plugin.declaredModes.includes(mode));
  const health = plugin.health?.status;
  return <article className="plugin-item" aria-label={plugin.name}>
    <header className="plugin-item__heading"><div><h3>{plugin.name}</h3><p>{plugin.id} · v{plugin.version}</p></div>
      <span className="plugin-tag">{plugin.selectedMode === "DOCKER" ? "Docker" : plugin.selectedMode === "LOCAL" ? "本地运行" : "尚未选择运行方式"}</span></header>
    <dl className="plugin-states">
      <div><dt>启用状态</dt><dd>{disabled ? "已禁用" : "已启用"}</dd></div>
      <div><dt>运行状态</dt><dd>{plugin.runtimeState ? runtimeLabels[plugin.runtimeState] : "未启动"}</dd></div>
      <div><dt>健康状态</dt><dd className={health === "ISOLATED" || health === "UNHEALTHY" ? "plugin-text-error" : undefined}>{health ? healthLabels[health] : "尚未验证"}</dd></div>
    </dl>
    {!configured && <p className="plugin-hint">运行后端尚未配置，当前只能登记说明书，不能启用解析能力。</p>}
    {cleanup && <p className="plugin-hint">{plugin.runtimeState === "STOP_FAILED" ? "资源释放失败。" : "已关闭新调用，仍需完成资源释放。"}稍后点击“继续清理”；刷新列表不会执行清理。</p>}
    {health === "ISOLATED" && <p className="plugin-hint">连续故障已触发隔离。请先禁用并完成清理，再重新启用。</p>}
    <details><summary>查看能力与检查记录</summary><div className="plugin-details"><p>能力标识：{plugin.providerIds.join("、")}</p>
      <p>可配置方式：{plugin.declaredModes.join("、")}；已配置后端：{plugin.configuredModes.join("、") || "无"}</p>
      <p>最近检查：{plugin.health?.checkedAt ? new Date(plugin.health.checkedAt).toLocaleString() : "尚无记录"}；连续后端故障：{plugin.health?.consecutiveFailures ?? 0}</p>
    </div></details>
    <div className="plugin-actions">
      <Button variant="confirm" disabled={busy || !disabled || !stopped || !configured} onClick={() => onAction("enable")}>启用</Button>
      <Button disabled={busy || disabled || health === "ISOLATED" || health === "CHECKING" || plugin.runtimeState !== "RUNNING"} onClick={() => onAction("check")}>检查健康</Button>
      <Button disabled={busy || (disabled && stopped)} onClick={() => onAction("disable")}>{cleanup ? "继续清理" : "禁用并清理"}</Button>
      <Button variant="danger" disabled={busy || !disabled || !stopped} onClick={() => onAction("uninstall")}>卸载登记</Button>
    </div>
  </article>;
}
