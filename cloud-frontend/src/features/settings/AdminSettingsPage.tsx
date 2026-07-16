import * as Tabs from "@radix-ui/react-tabs";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Eye, EyeOff, Save, ShieldCheck } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useBlocker } from "react-router-dom";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge } from "../../components/ui/StatusBadge";
import type { SiteSetting } from "../../types";

export function AdminSettingsPage() {
  const client = useQueryClient();
  const settings = useQuery({ queryKey: ["admin-settings"], queryFn: api.adminSettings });
  const [values, setValues] = useState<Record<string, string>>({});
  const [revealed, setRevealed] = useState<Set<string>>(new Set());
  useEffect(() => { if (settings.data) setValues(Object.fromEntries(settings.data.map((setting) => [setting.key, setting.value ?? ""]))); }, [settings.data]);
  const groups = useMemo(() => groupSettings(settings.data || []), [settings.data]);
  const changed = useMemo(() => (settings.data || []).filter((setting) => values[setting.key] !== (setting.value ?? "")), [settings.data, values]);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => changed.length > 0 && currentLocation.pathname !== nextLocation.pathname);
  const save = useMutation({ mutationFn: () => api.updateAdminSettings(changed.map((setting) => ({ key: setting.key, value: values[setting.key] ?? "" }))), onSuccess: () => { client.invalidateQueries({ queryKey: ["admin-settings"] }); client.invalidateQueries({ queryKey: ["public-settings"] }); toast.success(`已保存 ${changed.length} 项系统配置`); }, onError: (error) => toast.error(error instanceof Error ? error.message : "系统配置保存失败") });
  if (settings.isLoading) return <LoadingState label="正在加载系统配置" />;
  if (settings.isError) return <ErrorState message={settings.error instanceof Error ? settings.error.message : "无法加载系统配置"} onRetry={() => settings.refetch()} />;
  const firstGroup = Object.keys(groups)[0];
  return <div className="admin-settings-page"><section className="admin-security-note"><ShieldCheck size={22} /><div><strong>管理员配置</strong><p>敏感值默认隐藏。保存操作会立即影响后续请求，请在生产环境谨慎修改服务地址和密钥。</p></div><StatusBadge tone="success">权限已验证</StatusBadge></section>{firstGroup ? <Tabs.Root defaultValue={firstGroup} orientation="vertical" className="settings-tabs"><Tabs.List className="settings-tabs__list" aria-label="配置分组">{Object.keys(groups).map((group) => <Tabs.Trigger key={group} value={group}>{friendlyGroup(group)}<small>{groups[group].length} 项</small></Tabs.Trigger>)}</Tabs.List><div className="settings-tabs__content">{Object.entries(groups).map(([group, items]) => <Tabs.Content key={group} value={group}><header className="panel-header"><div><span className="section-eyebrow">系统配置</span><h2>{friendlyGroup(group)}</h2><p>修改后点击页面底部的保存按钮使配置生效。</p></div></header><div className="settings-field-list">{items.map((setting) => <SettingField key={setting.key} setting={setting} value={values[setting.key] ?? ""} revealed={revealed.has(setting.key)} onReveal={() => setRevealed((current) => { const next = new Set(current); next.has(setting.key) ? next.delete(setting.key) : next.add(setting.key); return next; })} onChange={(value) => setValues((current) => ({ ...current, [setting.key]: value }))} />)}</div></Tabs.Content>)}</div></Tabs.Root> : <div className="page-state">暂无可编辑配置</div>}<div className="sticky-save"><span>{changed.length ? `有 ${changed.length} 项未保存修改` : "所有修改均已保存"}</span><Button variant="confirm" disabled={!changed.length} loading={save.isPending} onClick={() => save.mutate()}>{changed.length ? <Save size={16} /> : <Check size={16} />}保存修改</Button></div><Dialog open={blocker.state === "blocked"} onOpenChange={(open) => !open && blocker.reset?.()} title="放弃未保存的修改？" description="离开此页面后，尚未保存的系统配置将丢失。" footer={<><Button onClick={() => blocker.reset?.()}>继续编辑</Button><Button variant="danger" onClick={() => blocker.proceed?.()}>放弃并离开</Button></>}><div className="danger-callout">共有 <strong>{changed.length}</strong> 项修改尚未保存。</div></Dialog></div>;
}

function SettingField({ setting, value, revealed, onReveal, onChange }: { setting: SiteSetting; value: string; revealed: boolean; onReveal: () => void; onChange: (value: string) => void }) {
  const boolean = setting.valueType?.toLowerCase() === "boolean" || ["true", "false"].includes(value.toLowerCase());
  return <div className="setting-field"><div><label htmlFor={`setting-${setting.key}`}>{setting.label || setting.key}</label><p>{setting.description || setting.key}</p><code>{setting.key}</code></div><div className="setting-control">{boolean ? <select id={`setting-${setting.key}`} value={value} disabled={!setting.editable} onChange={(event) => onChange(event.target.value)}><option value="true">开启</option><option value="false">关闭</option></select> : <div className="secret-input"><input id={`setting-${setting.key}`} type={setting.secret && !revealed ? "password" : "text"} value={value} disabled={!setting.editable} onChange={(event) => onChange(event.target.value)} autoComplete="off" />{setting.secret && <button type="button" onClick={onReveal} aria-label={revealed ? "隐藏敏感值" : "显示敏感值"}>{revealed ? <EyeOff size={16} /> : <Eye size={16} />}</button>}</div>}</div></div>;
}
function groupSettings(settings: SiteSetting[]) { return settings.reduce<Record<string, SiteSetting[]>>((groups, setting) => { const key = setting.groupName || "general"; (groups[key] ||= []).push(setting); return groups; }, {}); }
function friendlyGroup(group: string) { const labels: Record<string, string> = { general: "站点与通用", site: "站点信息", storage: "对象存储", minio: "对象存储", model: "模型服务", rag: "RAG 与检索", security: "安全", upload: "文件上传" }; return labels[group.toLowerCase()] || group; }
