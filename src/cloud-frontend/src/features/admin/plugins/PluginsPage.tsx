import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { PackagePlus, RefreshCw, Puzzle } from "lucide-react";
import { useSession } from "../../../app/session";
import { Button } from "../../../components/ui/Button";
import { AdminPage } from "../components/AdminPage";
import { ManifestDialog } from "./ManifestDialog";
import { PluginActionDialog } from "./PluginActionDialog";
import { PluginItem, type PluginAction } from "./PluginItem";
import { errorText, pluginApi, pluginQueryKey, type RuntimeMode } from "./pluginApi";
import "./plugins.css";

const notice = <div className="admin-inline-warning"><span>登记仅保存在当前服务进程，重启后会丢失。登记说明书不等于部署成功；启用需要服务器配置运行后端。</span></div>;
export function PluginsPage() {
  const { user } = useSession();
  if (!user?.deploymentOwner) return <AdminPage eyebrow="部署管理" title="插件管理" description="管理插件的登记、运行与健康状态。" notice={<span />}>
    <div role="alert">仅部署所有者可以管理插件。当前账号没有访问权限。</div>
  </AdminPage>;
  return <PluginManagerPage />;
}
function PluginManagerPage() {
  const client = useQueryClient();
  const list = useQuery({ queryKey: pluginQueryKey, queryFn: ({ signal }) => pluginApi.list(signal), retry: false });
  const [installOpen, setInstallOpen] = useState(false);
  const [selected, setSelected] = useState<{ id: string; action: PluginAction } | null>(null);
  const [message, setMessage] = useState("");
  const guard = useRef(false);
  const refresh = async () => { await client.invalidateQueries({ queryKey: pluginQueryKey }); };
  const mutation = useMutation({ retry: false,
    mutationFn: async ({ id, action, mode }: { id: string; action: PluginAction; mode?: RuntimeMode }) => {
      if (action === "enable") { if (!mode) throw new Error("请选择运行方式"); await pluginApi.enable(id, mode); return "插件已启用，能力验证已通过。"; }
      if (action === "disable") { const result = await pluginApi.disable(id); return result.stopped ? "已禁用，资源已释放。" : "已停止接收新调用，资源仍在释放中。请稍后点击“继续清理”。"; }
      if (action === "uninstall") { await pluginApi.uninstall(id); return "插件登记已卸载。"; }
      await pluginApi.check(id); return "本次健康验证通过。";
    },
    onSuccess: text => { setMessage(text); setSelected(null); },
    onError: () => { setMessage(""); },
    onSettled: async () => { try { await refresh(); } finally { guard.current = false; } },
  });
  const plugin = list.data?.find(item => item.id === selected?.id);
  return <AdminPage eyebrow="部署管理" title="插件管理" description="先登记说明书，再启用并验证能力。停用后可继续清理或卸载。" notice={notice}
    actions={<><Button disabled={list.isFetching || mutation.isPending} onClick={() => void list.refetch()}><RefreshCw size={16} aria-hidden="true" />刷新状态</Button>
      <Button variant="primary" disabled={mutation.isPending} onClick={() => setInstallOpen(true)}><PackagePlus size={16} aria-hidden="true" />登记说明书</Button></>}>
    {message && <p className="plugin-feedback" role="status">{message}</p>}
    {list.isPending && <div className="plugin-skeleton" role="status" aria-busy="true">正在读取插件登记…</div>}
    {list.isError && <div className="plugin-feedback plugin-text-error" role="alert">{errorText(list.error)}。请刷新重试；已有状态可能已过期。</div>}
    {!list.isPending && !list.isError && list.data?.length === 0 && <div className="plugin-empty"><Puzzle size={32} aria-hidden="true" /><h3>尚未登记插件</h3><p>从插件作者提供的 JSON 说明书开始。登记后可查看可用的运行方式。</p><Button onClick={() => setInstallOpen(true)}>登记第一个插件</Button></div>}
    <div className="plugin-list">{list.data?.map(item => <PluginItem key={item.id} plugin={item} busy={mutation.isPending || list.isFetching || list.isError}
      onAction={action => { mutation.reset(); setMessage(""); setSelected({ id: item.id, action }); }} />)}</div>
    {installOpen && <ManifestDialog onRefresh={refresh} onClose={() => setInstallOpen(false)} onInstalled={async () => { setMessage("说明书已登记，尚未启动解析引擎。"); await refresh(); }} />}
    {selected && plugin && <PluginActionDialog key={`${selected.id}:${selected.action}`} plugin={plugin} action={selected.action} pending={mutation.isPending}
      error={mutation.isError ? errorText(mutation.error) : null} onClose={() => setSelected(null)} onConfirm={mode => {
        if (guard.current || mutation.isPending) return;
        guard.current = true; mutation.mutate({ ...selected, mode });
      }} />}
    {selected && !plugin && !list.isPending && <p role="alert">插件登记已变化，请刷新列表后重新选择。<Button onClick={() => setSelected(null)}>关闭提示</Button></p>}
    {mutation.isError && !selected && <p role="alert">{errorText(mutation.error)}</p>}
  </AdminPage>;
}
