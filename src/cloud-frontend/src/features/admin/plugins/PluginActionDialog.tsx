import { useState } from "react";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";
import { actionLabels, type PluginAction } from "./PluginItem";
import type { PluginView, RuntimeMode } from "./pluginApi";
const descriptions: Record<PluginAction, string> = {
  enable: "启动后会验证全部声明能力，通过后才开放调用。此操作可能需要一些时间。",
  disable: "立即关闭新调用，等待已有任务结束后释放资源。未完成时需要再次继续清理。",
  uninstall: "删除当前插件登记。此操作不会删除镜像或插件文件。",
  check: "使用服务器配置的样例复查能力；检查期间暂不接收新的业务调用。",
};
export function PluginActionDialog({ plugin, action, pending, error, onClose, onConfirm }: {
  plugin: PluginView; action: PluginAction; pending: boolean; error: string | null;
  onClose: () => void; onConfirm: (mode?: RuntimeMode) => void;
}) {
  const [mode, setMode] = useState<RuntimeMode | "">("");
  const modes = plugin.configuredModes.filter(value => plugin.declaredModes.includes(value));
  return <Dialog open onOpenChange={open => { if (!open && !pending) onClose(); }} title={`${actionLabels[action]}：${plugin.name}`}
    description={descriptions[action]} footer={<><Button disabled={pending} onClick={onClose}>取消</Button>
      <Button variant={action === "uninstall" ? "danger" : "confirm"} loading={pending}
        disabled={action === "enable" && (!mode || !modes.includes(mode))} onClick={() => onConfirm(mode || undefined)}>确认{actionLabels[action]}</Button></>}>
    <p className="plugin-wrap">{plugin.id} · v{plugin.version}</p>
    {action === "enable" && <label className="plugin-mode-field">运行方式<select value={mode} disabled={pending} onChange={event => setMode(event.target.value as RuntimeMode | "")}>
      <option value="">请选择已配置的运行方式</option>{modes.map(value => <option key={value} value={value}>{value === "LOCAL" ? "本地运行" : "Docker"}</option>)}
    </select></label>}
    {pending && <p role="status">正在等待服务器结果，请勿重复提交。离开页面不会取消后端操作。</p>}
    {error && <p role="alert">{error}。请核对刷新后的状态，避免重复操作。</p>}
  </Dialog>;
}
