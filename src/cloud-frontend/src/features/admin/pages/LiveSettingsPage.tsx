import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type Dispatch, type SetStateAction } from "react";
import { Eye, EyeOff } from "lucide-react";
import { toast } from "sonner";
import { api } from "../../../api";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../../components/ui/Tabs";
import { AdminField } from "../components/AdminDialogs";
import { AdminPage, AdminSection, AdminUnavailableHint } from "../components/AdminPage";

const errorText = (error: unknown) => error instanceof Error ? error.message : "请求失败";
export function LiveSettingsPage() {
  const client = useQueryClient();
  const settings = useQuery({ queryKey: ["admin-settings"], queryFn: api.adminSettings });
  const [changes, setChanges] = useState<Record<string, string>>({});
  const [secretValues, setSecretValues] = useState<Record<string, string>>({});
  const dirty = Object.keys(changes).length > 0 || Object.values(secretValues).some(Boolean);
  const [confirm, setConfirm] = useState(false);
  const save = useMutation({ mutationFn: () => api.updateAdminSettings(Object.entries(changes).map(([key, value]) => ({ key, value }))),
    onSuccess: async () => {
      setChanges({}); setConfirm(false); toast.success("配置已保存到服务器");
      await Promise.all([client.invalidateQueries({ queryKey: ["admin-settings"] }), client.invalidateQueries({ queryKey: ["public-settings"] })]);
    } });
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ""; } };
    window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);
  const groups = [...new Set((settings.data || []).map((setting) => setting.groupName || "其他"))];
  return <AdminPage eyebrow="系统配置" title="参数设置" description="仅展示后端声明的配置键；只读项不可修改，敏感项不回填原值。"
    actions={<div className="button-row"><Button disabled={dirty || settings.isFetching} onClick={() => void settings.refetch()}>刷新</Button><Button disabled={!dirty || save.isPending} onClick={() => setChanges({})}>放弃修改</Button><Button variant="primary" disabled={!dirty || save.isPending} onClick={() => { save.reset(); setConfirm(true); }}>保存配置</Button></div>}>
    <Tabs defaultValue="settings"><TabsList aria-label="设置分组"><TabsTrigger value="settings">服务端配置</TabsTrigger><TabsTrigger value="secrets">密钥与凭据</TabsTrigger><TabsTrigger value="maintenance">服务器与维护</TabsTrigger><TabsTrigger value="unavailable">未接入配置</TabsTrigger></TabsList>
      <TabsContent value="settings">
        {settings.isPending && <p role="status">正在读取配置…</p>}
        {settings.isError && <div role="alert">{errorText(settings.error)}<Button onClick={() => void settings.refetch()}>重试</Button></div>}
        {groups.map((group) => <AdminSection key={group} title={group}><div className="admin-demo-form">
          {(settings.data || []).filter((setting) => (setting.groupName || "其他") === group).map((setting) => <AdminField key={setting.key} label={setting.label || setting.key} hint={`${setting.key} · ${setting.description || ""}${setting.secret ? "（留空不替换；输入新值才提交）" : ""}`}>
            {setting.valueType.toLowerCase() === "boolean" ? <select aria-label={setting.label || setting.key} disabled={!setting.editable || save.isPending} value={changes[setting.key] ?? setting.value} onChange={(event) => setChanges((current) => ({ ...current, [setting.key]: event.target.value }))}><option value="true">开启</option><option value="false">关闭</option></select>
              : <input aria-label={setting.label || setting.key} disabled={!setting.editable || save.isPending} type={setting.secret ? "password" : "text"} autoComplete={setting.secret ? "new-password" : "off"} placeholder={setting.secret ? "已配置值不会回显" : undefined} value={changes[setting.key] ?? (setting.secret ? "" : setting.value || "")} onChange={(event) => setChanges((current) => { const next = { ...current }; if ((setting.secret && event.target.value === "") || (!setting.secret && event.target.value === setting.value)) delete next[setting.key]; else next[setting.key] = event.target.value; return next; })} />}
          </AdminField>)}
        </div></AdminSection>)}
        {!settings.isPending && !settings.isError && !groups.length && <p>服务器未提供配置项。</p>}
      </TabsContent>
      <TabsContent value="secrets"><DeploymentSecretsPanel values={secretValues} onValuesChange={setSecretValues} /></TabsContent>
      <TabsContent value="maintenance"><MaintenancePanel /></TabsContent>
      <TabsContent value="unavailable"><AdminUnavailableHint>未由后端返回的会话、验证码、增值服务、邮件和队列配置暂不可用，不使用本地模拟保存。存储策略与 Token 消耗统计按约定暂缓。</AdminUnavailableHint></TabsContent>
    </Tabs>
    <Dialog open={confirm} onOpenChange={(open) => !save.isPending && setConfirm(open)} title="保存系统配置？" description="修改会影响后续全站请求；敏感值不会在此展示。" footer={<><Button disabled={save.isPending} onClick={() => setConfirm(false)}>取消</Button><Button variant="confirm" loading={save.isPending} onClick={() => !save.isPending && save.mutate()}>确认保存到服务器</Button></>}>
      <ul>{Object.keys(changes).map((key) => <li key={key}>{key}</li>)}</ul>{save.isError && <p role="alert">{errorText(save.error)}</p>}
    </Dialog>
  </AdminPage>;
}

function DeploymentSecretsPanel({ values, onValuesChange }: {
  values: Record<string, string>;
  onValuesChange: Dispatch<SetStateAction<Record<string, string>>>;
}) {
  const client = useQueryClient();
  const secrets = useQuery({ queryKey: ["deployment-secrets"], queryFn: api.deploymentSecrets, retry: false });
  const [visible, setVisible] = useState<Set<string>>(() => new Set());
  const [confirmKey, setConfirmKey] = useState<string | null>(null);
  const update = useMutation({
    mutationFn: ({ key, value }: { key: string; value: string }) => api.updateDeploymentSecret(key, value),
    onSuccess: async (_, variables) => {
      onValuesChange((current) => { const next = { ...current }; delete next[variables.key]; return next; });
      setConfirmKey(null);
      toast.success("Secret 已安全保存");
      await client.invalidateQueries({ queryKey: ["deployment-secrets"] });
    }
  });
  const selected = secrets.data?.find((item) => item.key === confirmKey);
  return <AdminSection title="部署密钥" description="真实值只写入本机 Secret 文件，页面和接口均不会回显。只有部署所有者可以访问。">
    {secrets.isPending && <p role="status">正在读取 Secret 配置状态…</p>}
    {secrets.isError && <p role="alert">{errorText(secrets.error)}</p>}
    <div className="admin-demo-form">
      {(secrets.data || []).map((secret) => <AdminField key={secret.key} label={secret.label}
        hint={`${secret.description} · ${secret.configured ? "已配置" : "未配置"} · ${secret.activation}`}>
        {secret.editable ? <div className="admin-secret-field">
          <input aria-label={secret.label} type={visible.has(secret.key) ? "text" : "password"} autoComplete="new-password"
            value={values[secret.key] || ""} disabled={update.isPending}
            placeholder={secret.configured ? "输入新值以替换" : "输入 Secret"}
            onChange={(event) => onValuesChange((current) => ({ ...current, [secret.key]: event.target.value }))} />
          <button type="button" disabled={update.isPending} onClick={() => setVisible((current) => {
            const next = new Set(current); next.has(secret.key) ? next.delete(secret.key) : next.add(secret.key); return next;
          })} aria-label={visible.has(secret.key) ? `隐藏${secret.label}` : `显示${secret.label}`}>
            {visible.has(secret.key) ? <EyeOff size={16} /> : <Eye size={16} />}
          </button>
          <Button variant="confirm" disabled={!values[secret.key]?.trim() || update.isPending}
            onClick={() => { update.reset(); setConfirmKey(secret.key); }}>保存</Button>
        </div> : <p className="admin-field-readonly">{secret.configured ? "已配置" : "未配置"}；请使用专用轮换流程。</p>}
      </AdminField>)}
    </div>
    <Dialog open={confirmKey !== null} onOpenChange={(open) => !open && !update.isPending && setConfirmKey(null)}
      title={`替换${selected?.label || "Secret"}？`} description="新值将写入本机 Secret 文件，原值无法从管理页面恢复。"
      footer={<><Button disabled={update.isPending} onClick={() => setConfirmKey(null)}>取消</Button><Button variant="confirm" loading={update.isPending}
        onClick={() => confirmKey && update.mutate({ key: confirmKey, value: values[confirmKey] || "" })}>确认保存</Button></>}>
      <p>{selected?.activation || "保存后按页面提示生效。"}</p>
      {update.isError && <p role="alert">{errorText(update.error)}</p>}
    </Dialog>
  </AdminSection>;
}

function MaintenancePanel() {
  const status = useQuery({ queryKey: ["admin-maintenance"], queryFn: api.maintenanceStatus });
  const [reason, setReason] = useState("");
  const [command, setCommand] = useState<"enable" | "disable" | null>(null);
  const change = useMutation({ mutationFn: (action: "enable" | "disable") => action === "enable" ? api.enableMaintenance({ reason }) : api.disableMaintenance(),
    onSuccess: async () => { setCommand(null); await status.refetch(); toast.success("维护命令已执行"); } });
  return <AdminSection title="维护模式" description="这是主机的真实维护模式，不是从机节点维护。">
    {status.isPending && <p role="status">正在读取维护状态…</p>}
    {status.isError && <p role="alert">{errorText(status.error)}</p>}
    {status.data && <><p>当前状态：{status.data.active ? "维护中" : "正常服务"}</p><p>{status.data.reason || "未填写原因"}</p>
      <AdminField label="维护原因"><input value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} /></AdminField>
      <Button variant="danger" disabled={change.isPending || status.isFetching} onClick={() => { change.reset(); setCommand(status.data.active ? "disable" : "enable"); }}>{status.data.active ? "退出维护" : "进入维护"}</Button></>}
    <Button disabled={change.isPending || status.isFetching} onClick={() => void status.refetch()}>刷新维护状态</Button>
    <Dialog open={command !== null} onOpenChange={(open) => !open && !change.isPending && setCommand(null)} title={command === "enable" ? "进入维护？" : "退出维护？"} description="此操作立即影响全站服务，请确认当前没有不宜中断的操作。" footer={<><Button disabled={change.isPending} onClick={() => setCommand(null)}>取消</Button><Button variant="danger" loading={change.isPending} onClick={() => command && !change.isPending && change.mutate(command)}>确认执行</Button></>}>
      {change.isError && <p role="alert">{errorText(change.error)}</p>}
    </Dialog>
  </AdminSection>;
}
