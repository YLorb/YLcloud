import * as Tabs from "@radix-ui/react-tabs";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot, Check, Cpu, Eye, EyeOff, FileCog, Globe2, Lock, Save, ShieldCheck } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useBlocker } from "react-router-dom";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge } from "../../components/ui/StatusBadge";
import type { SiteSetting } from "../../types";
import { AccessManagementPanel } from "./AccessManagementPanel";

type SectionKey = "site" | "permissions" | "files" | "ai" | "embedding";
const sections: Array<{ key: SectionKey; label: string; description: string; icon: typeof Globe2 }> = [
  { key: "site", label: "站点信息", description: "品牌、公开地址和站点说明", icon: Globe2 },
  { key: "permissions", label: "权限系统", description: "注册策略、用户角色和账号状态", icon: ShieldCheck },
  { key: "files", label: "文件与存储", description: "上传限制、分享地址和用户配额", icon: FileCog },
  { key: "ai", label: "AI 与 RAG", description: "模型、解析、向量库和问答开关", icon: Bot },
  { key: "embedding", label: "Embedding 切换", description: "向量模型与维度迁移", icon: Cpu }
];

export function AdminSettingsPage() {
  const client = useQueryClient();
  const settings = useQuery({ queryKey: ["admin-settings"], queryFn: api.adminSettings });
  const [values, setValues] = useState<Record<string, string>>({});
  const [revealed, setRevealed] = useState<Set<string>>(new Set());
  useEffect(() => { if (settings.data) setValues(Object.fromEntries(settings.data.map((setting) => [setting.key, setting.value ?? ""]))); }, [settings.data]);
  const grouped = useMemo(() => groupSettings(settings.data || []), [settings.data]);
  const changed = useMemo(() => (settings.data || []).filter((setting) => values[setting.key] !== (setting.value ?? "")), [settings.data, values]);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => changed.length > 0 && currentLocation.pathname !== nextLocation.pathname);
  const save = useMutation({
    mutationFn: () => api.updateAdminSettings(changed.map((setting) => ({ key: setting.key, value: values[setting.key] ?? "" }))),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["admin-settings"] });
      client.invalidateQueries({ queryKey: ["public-settings"] });
      client.invalidateQueries({ queryKey: ["storage-quota"] });
      toast.success(`已保存 ${changed.length} 项系统配置`);
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "系统配置保存失败")
  });

  if (settings.isLoading) return <LoadingState label="正在加载管理员设置" />;
  if (settings.isError) return <ErrorState message={settings.error instanceof Error ? settings.error.message : "无法加载管理员设置"} onRetry={() => settings.refetch()} />;
  return <div className="admin-settings-page">
    <section className="admin-security-note"><ShieldCheck size={22} /><div><strong>Admin Settings</strong><p>更改会影响所有后续请求。角色调整和账号停用立即生效，敏感配置不会明文回显。</p></div><StatusBadge tone="success">ADMIN 权限已验证</StatusBadge></section>
    <Tabs.Root defaultValue="site" orientation="vertical" className="settings-tabs">
      <Tabs.List className="settings-tabs__list" aria-label="管理员设置分组">
        {sections.map(({ key, label, description, icon: Icon }) => <Tabs.Trigger key={key} value={key} className={key === "embedding" ? "settings-tab--unavailable" : undefined}><Icon size={17} /><span>{label}<small>{description}</small></span>{key === "embedding" && <Lock className="settings-tab__lock" size={12} aria-label="暂不可用" />}</Tabs.Trigger>)}
      </Tabs.List>
      <div className="settings-tabs__content">
        {sections.map((section) => <Tabs.Content key={section.key} value={section.key}>
          <header className="panel-header"><div><span className="section-eyebrow">Admin Settings</span><h2>{section.label}</h2><p>{section.description}。</p></div></header>
          {grouped[section.key].length > 0 && <div className="settings-field-list">{grouped[section.key].map((setting) => <SettingField key={setting.key} setting={setting} value={values[setting.key] ?? ""} revealed={revealed.has(setting.key)} onReveal={() => setRevealed((current) => { const next = new Set(current); next.has(setting.key) ? next.delete(setting.key) : next.add(setting.key); return next; })} onChange={(value) => setValues((current) => ({ ...current, [setting.key]: value }))} />)}</div>}
          {section.key === "permissions" && <AccessManagementPanel />}
          {section.key === "embedding" && <div className="embedding-placeholder" aria-disabled="true"><Cpu size={32} /><strong>Embedding 模型切换暂不可用</strong><p>这里将用于选择向量模型、查看维度兼容性并执行索引迁移。功能开放前不会影响当前知识库。</p><span><Lock size={13} />敬请期待</span></div>}
        </Tabs.Content>)}
      </div>
    </Tabs.Root>
    <div className="sticky-save"><span aria-live="polite">{changed.length ? `有 ${changed.length} 项未保存修改` : "所有站点配置均已保存"}</span><Button variant="confirm" disabled={!changed.length} loading={save.isPending} onClick={() => save.mutate()}>{changed.length ? <Save size={16} /> : <Check size={16} />}保存站点配置</Button></div>
    <Dialog open={blocker.state === "blocked"} onOpenChange={(open) => !open && blocker.reset?.()} title="放弃未保存的修改？" description="离开后，尚未保存的站点配置将丢失。" footer={<><Button onClick={() => blocker.reset?.()}>继续编辑</Button><Button variant="danger" onClick={() => blocker.proceed?.()}>放弃并离开</Button></>}><div className="danger-callout">共有 <strong>{changed.length}</strong> 项修改尚未保存。</div></Dialog>
  </div>;
}

function SettingField({ setting, value, revealed, onReveal, onChange }: { setting: SiteSetting; value: string; revealed: boolean; onReveal: () => void; onChange: (value: string) => void }) {
  const boolean = setting.valueType?.toLowerCase() === "boolean";
  const quota = ["storage.userQuotaBytes", "storage.adminQuotaBytes"].includes(setting.key);
  const uploadLimit = setting.key === "upload.maxFileSize";
  const inputId = `setting-${setting.key}`;
  return <div className="setting-field"><div><label htmlFor={inputId}>{setting.label || setting.key}</label><p>{setting.description || setting.key}</p><code>{setting.key}</code></div><div className="setting-control">
    {boolean ? <select id={inputId} value={value} disabled={!setting.editable} onChange={(event) => onChange(event.target.value)}><option value="true">开启</option><option value="false">关闭</option></select> : quota ? <label className="unit-input"><input id={inputId} type="number" min="0.1" step="0.1" value={bytesToGiB(value)} disabled={!setting.editable} onChange={(event) => onChange(String(Math.round(Number(event.target.value || 0) * 1024 ** 3)))} /><span>GB</span></label> : uploadLimit ? <label className="unit-input"><input id={inputId} type="number" min="0" step="1" value={bytesToMiB(value)} disabled={!setting.editable} onChange={(event) => onChange(String(Math.round(Number(event.target.value || 0) * 1024 ** 2)))} /><span>MB</span></label> : <div className="secret-input"><input id={inputId} type={setting.secret && !revealed ? "password" : setting.valueType === "number" ? "number" : "text"} value={value} disabled={!setting.editable} onChange={(event) => onChange(event.target.value)} autoComplete="off" />{setting.secret && <button type="button" onClick={onReveal} aria-label={revealed ? "隐藏敏感值" : "显示敏感值"}>{revealed ? <EyeOff size={16} /> : <Eye size={16} />}</button>}</div>}
  </div></div>;
}

function groupSettings(settings: SiteSetting[]) { const groups: Record<SectionKey, SiteSetting[]> = { site: [], permissions: [], files: [], ai: [], embedding: [] }; settings.forEach((setting) => groups[sectionFor(setting)].push(setting)); return groups; }
function sectionFor(setting: SiteSetting): SectionKey { if (setting.key === "site.allowRegister") return "permissions"; if (/^(upload|share|storage)\./.test(setting.key) || setting.groupName === "file") return "files"; if (/^(llm|rag)\./.test(setting.key) || setting.groupName === "ai") return "ai"; return "site"; }
function bytesToGiB(value: string) { const bytes = Number(value); return Number.isFinite(bytes) ? String(Math.round(bytes / 1024 ** 3 * 10) / 10) : "1"; }
function bytesToMiB(value: string) { const bytes = Number(value); return Number.isFinite(bytes) ? String(Math.round(bytes / 1024 ** 2)) : "0"; }
