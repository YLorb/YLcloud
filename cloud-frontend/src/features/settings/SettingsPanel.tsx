import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  Database,
  Eye,
  EyeOff,
  FileSliders,
  Globe2,
  HardDrive,
  KeyRound,
  Loader2,
  LockKeyhole,
  RefreshCw,
  RotateCcw,
  Save,
  ServerCog,
  ShieldCheck
} from "lucide-react";
import { api } from "../../api";
import type { Notice } from "../../appTypes";
import { formatSize, formatTime } from "../../fileUtils";
import type { SiteSetting } from "../../types";

type SettingsPanelProps = {
  onNotice: (notice: Notice) => void;
  onNavigationGuardChange: (guard: (() => boolean) | null) => void;
  onSaved: () => Promise<void>;
};

type GroupMeta = {
  label: string;
  description: string;
  icon: React.ReactNode;
};

const groupMeta: Record<string, GroupMeta> = {
  site: {
    label: "站点与访问",
    description: "控制应用名称、公开地址和账号注册入口。",
    icon: <Globe2 size={18} />
  },
  file: {
    label: "文件与分享",
    description: "管理上传边界和对外分享链接。",
    icon: <HardDrive size={18} />
  },
  ai: {
    label: "AI 与知识库",
    description: "配置问答模型、解析服务和向量数据库。",
    icon: <Bot size={18} />
  }
};

const requiredKeys = new Set(["site.name", "site.publicUrl", "llm.provider", "llm.model"]);
const urlKeys = new Set([
  "site.logoUrl",
  "site.publicUrl",
  "share.publicBaseUrl",
  "llm.baseUrl",
  "rag.modelServiceBaseUrl",
  "rag.parserServiceBaseUrl"
]);
const secretReferencePrefixes = ["env:", "docker-secret:", "vault:", "azure-key-vault:", "aws-secrets-manager:"];

function isEnabled(value?: string) {
  return ["true", "1", "yes"].includes((value || "").toLowerCase());
}

function metaFor(group: string): GroupMeta {
  return groupMeta[group] || {
    label: group,
    description: "应用运行参数。",
    icon: <ServerCog size={18} />
  };
}

function validateSetting(item: SiteSetting, value: string) {
  const normalized = value.trim();
  if (requiredKeys.has(item.key) && !normalized) return "此项不能为空";
  if (item.valueType === "number") {
    const number = Number(normalized);
    if (!normalized || !Number.isFinite(number) || number < 0) return "请输入不小于 0 的有效数字";
    if (item.key === "upload.maxFileSize" && (!Number.isInteger(number) || number <= 0)) return "单文件大小上限必须是正整数";
  }
  if (urlKeys.has(item.key) && normalized) {
    try {
      const url = new URL(normalized);
      if (url.protocol !== "http:" && url.protocol !== "https:") return "地址必须使用 HTTP 或 HTTPS";
    } catch {
      return "请输入完整的 URL 地址";
    }
  }
  if (item.key === "llm.apiKeyRef" && normalized && !secretReferencePrefixes.some((prefix) => normalized.startsWith(prefix))) {
    return "请输入 env:、docker-secret:、vault:、azure-key-vault: 或 aws-secrets-manager: 引用";
  }
  return "";
}

function SettingField({
  item,
  value,
  dirty,
  error,
  onChange
}: {
  item: SiteSetting;
  value: string;
  dirty: boolean;
  error?: string;
  onChange: (value: string) => void;
}) {
  const [revealed, setRevealed] = useState(false);
  const id = `setting-${item.key.replace(/[^a-zA-Z0-9]/g, "-")}`;
  const providerOptions = item.key === "llm.provider"
    ? Array.from(new Set([value, "openai-compatible", "deepseek", "openai", "ollama"].filter(Boolean)))
    : [];
  const sizeHint = item.key === "upload.maxFileSize" && Number(value) >= 0 ? formatSize(Number(value)) : "";

  if (item.valueType === "boolean") {
    const enabled = isEnabled(value);
    return (
      <label className={`admin-setting-field boolean ${dirty ? "dirty" : ""}`} htmlFor={id}>
        <span className="admin-setting-copy">
          <span>
            <strong>{item.label || item.key}</strong>
            {dirty && <em>已修改</em>}
          </span>
          <small>{item.description || item.key}</small>
          <code>{item.key}</code>
        </span>
        <span className="admin-setting-switch">
          <input
            id={id}
            type="checkbox"
            checked={enabled}
            disabled={!item.editable}
            onChange={(event) => onChange(event.target.checked ? "true" : "false")}
          />
          <span aria-hidden="true" />
          <b>{enabled ? "已开启" : "已关闭"}</b>
        </span>
      </label>
    );
  }

  return (
    <div className={`admin-setting-field ${dirty ? "dirty" : ""}`}>
      <label className="admin-setting-copy" htmlFor={id}>
        <span>
          <strong>{item.label || item.key}</strong>
          {item.secret && <LockKeyhole size={14} aria-label="敏感配置" />}
          {dirty && <em>已修改</em>}
        </span>
        <small>{item.description || item.key}</small>
        <code>{item.key}</code>
      </label>
      <div className="admin-setting-control">
        {providerOptions.length ? (
          <select id={id} value={value} disabled={!item.editable} onChange={(event) => onChange(event.target.value)}>
            {providerOptions.map((option) => <option key={option} value={option}>{option}</option>)}
          </select>
        ) : (
          <div className="admin-setting-input-wrap">
            <input
              id={id}
              type={item.secret && !revealed ? "password" : item.valueType === "number" ? "number" : urlKeys.has(item.key) ? "url" : "text"}
              value={value}
              min={item.valueType === "number" ? 0 : undefined}
              disabled={!item.editable}
              placeholder={item.secret ? `${item.maskedValue || "未配置"}，输入新值才会替换` : "请输入配置值"}
              aria-invalid={Boolean(error)}
              aria-describedby={error ? `${id}-error` : undefined}
              onChange={(event) => onChange(event.target.value)}
            />
            {item.secret && (
              <button type="button" onClick={() => setRevealed((current) => !current)} aria-label={revealed ? "隐藏密钥" : "显示密钥"}>
                {revealed ? <EyeOff size={17} /> : <Eye size={17} />}
              </button>
            )}
          </div>
        )}
        <div className="admin-setting-meta">
          {item.secret && <span><KeyRound size={13} />当前值：{item.maskedValue || "未配置"}</span>}
          {sizeHint && <span>约 {sizeHint}</span>}
          {!item.editable && <span>只读配置</span>}
        </div>
        {error && <small className="admin-field-error" id={`${id}-error`}>{error}</small>}
      </div>
    </div>
  );
}

function ControlStrip({ settings, values, dirtyCount }: { settings: SiteSetting[]; values: Record<string, string>; dirtyCount: number }) {
  const registerOpen = isEnabled(values["site.allowRegister"]);
  const aiEnabled = isEnabled(values["llm.enabled"]);
  return (
    <div className="admin-control-strip" role="list" aria-label="应用控制状态">
      <div role="listitem">
        <CheckCircle2 size={18} />
        <span><small>配置服务</small><strong>运行正常</strong></span>
      </div>
      <div role="listitem" className={registerOpen ? "" : "muted"}>
        <ShieldCheck size={18} />
        <span><small>用户注册</small><strong>{registerOpen ? "允许注册" : "已关闭"}</strong></span>
      </div>
      <div role="listitem" className={aiEnabled ? "" : "muted"}>
        <Bot size={18} />
        <span><small>AI 问答</small><strong>{aiEnabled ? "已启用" : "已停用"}</strong></span>
      </div>
      <div role="listitem" className={dirtyCount ? "attention" : ""}>
        {dirtyCount ? <AlertTriangle size={18} /> : <Database size={18} />}
        <span><small>配置状态</small><strong>{dirtyCount ? `${dirtyCount} 项未保存` : `${settings.length} 项已同步`}</strong></span>
      </div>
    </div>
  );
}

export function SettingsPanel({ onNotice, onNavigationGuardChange, onSaved }: SettingsPanelProps) {
  const [settings, setSettings] = useState<SiteSetting[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [baseline, setBaseline] = useState<Record<string, string>>({});
  const [validationErrors, setValidationErrors] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [activeGroup, setActiveGroup] = useState("site");
  const [error, setError] = useState("");

  const groups = useMemo(() => Array.from(new Set(settings.map((item) => item.groupName))), [settings]);
  const visibleSettings = settings.filter((item) => item.groupName === activeGroup);
  const dirtyKeys = useMemo(
    () => settings.filter((item) => item.editable && (values[item.key] ?? "") !== (baseline[item.key] ?? "")).map((item) => item.key),
    [settings, values, baseline]
  );
  const activeDirtyCount = visibleSettings.filter((item) => dirtyKeys.includes(item.key)).length;
  const activeMeta = metaFor(activeGroup);
  const updateTimes = visibleSettings
    .map((item) => item.updateTime)
    .filter(Boolean)
    .sort();
  const latestUpdate = updateTimes[updateTimes.length - 1];

  async function loadSettings() {
    setLoading(true);
    setError("");
    try {
      const data = await api.adminSettings();
      const nextValues: Record<string, string> = {};
      data.forEach((item) => {
        nextValues[item.key] = item.secret ? "" : item.value ?? "";
      });
      setSettings(data);
      setValues(nextValues);
      setBaseline(nextValues);
      setValidationErrors({});
      setActiveGroup((current) => data.some((item) => item.groupName === current) ? current : data[0]?.groupName || "site");
    } catch (err) {
      setError(err instanceof Error ? err.message : "设置加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadSettings();
  }, []);

  useEffect(() => {
    const warnBeforeLeave = (event: BeforeUnloadEvent) => {
      if (!dirtyKeys.length) return;
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warnBeforeLeave);
    return () => window.removeEventListener("beforeunload", warnBeforeLeave);
  }, [dirtyKeys.length]);

  useEffect(() => {
    if (!dirtyKeys.length) {
      onNavigationGuardChange(null);
      return;
    }
    const guard = () => window.confirm("尚有未保存的应用设置，确定要离开吗？");
    onNavigationGuardChange(guard);
    return () => onNavigationGuardChange(null);
  }, [dirtyKeys.length, onNavigationGuardChange]);

  function updateValue(item: SiteSetting, value: string) {
    setValues((current) => ({ ...current, [item.key]: value }));
    setValidationErrors((current) => ({ ...current, [item.key]: validateSetting(item, value) }));
  }

  function resetActiveGroup() {
    const keys = new Set(visibleSettings.map((item) => item.key));
    setValues((current) => {
      const next = { ...current };
      keys.forEach((key) => { next[key] = baseline[key] ?? ""; });
      return next;
    });
    setValidationErrors((current) => {
      const next = { ...current };
      keys.forEach((key) => { delete next[key]; });
      return next;
    });
  }

  async function refreshSettings() {
    if (dirtyKeys.length && !window.confirm("刷新会放弃尚未保存的修改，是否继续？")) return;
    await loadSettings();
  }

  async function saveSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!dirtyKeys.length) return;
    const changedSettings = settings.filter((item) => dirtyKeys.includes(item.key));
    const errors: Record<string, string> = {};
    changedSettings.forEach((item) => {
      const message = validateSetting(item, values[item.key] ?? "");
      if (message) errors[item.key] = message;
    });
    if (Object.keys(errors).length) {
      setValidationErrors((current) => ({ ...current, ...errors }));
      const firstInvalid = changedSettings.find((item) => errors[item.key]);
      if (firstInvalid) setActiveGroup(firstInvalid.groupName);
      setError("请先修正标记的配置项");
      return;
    }

    setSaving(true);
    setError("");
    try {
      await api.updateAdminSettings(changedSettings.map((item) => ({ key: item.key, value: values[item.key] ?? "" })));
      await Promise.all([loadSettings(), onSaved()]);
      onNotice({ type: "success", text: `已保存 ${changedSettings.length} 项应用设置` });
    } catch (err) {
      const message = err instanceof Error ? err.message : "保存设置失败";
      setError(message);
      onNotice({ type: "error", text: message });
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="settings-panel admin-settings-page">
      <header className="admin-settings-head">
        <div>
          <p>Administration</p>
          <h2>应用设置</h2>
          <span>统一管理公开站点、文件边界和知识库基础设施。设置保存后立即应用到可动态读取的服务。</span>
        </div>
        <div className="admin-settings-head-actions">
          <span className="settings-health"><ShieldCheck size={16} />仅管理员可访问</span>
          <button className="icon-button" type="button" onClick={() => void refreshSettings()} title="刷新设置" aria-label="刷新设置">
            <RefreshCw size={18} />
          </button>
        </div>
      </header>

      <ControlStrip settings={settings} values={values} dirtyCount={dirtyKeys.length} />

      {loading && !settings.length ? (
        <div className="loading-state settings-loading" aria-busy="true"><Loader2 className="spin" size={24} />正在读取应用设置</div>
      ) : error && !settings.length ? (
        <div className="error-state settings-error">
          <strong>无法加载管理员设置</strong><span>{error}</span>
          <button type="button" onClick={() => void loadSettings()}>重试</button>
        </div>
      ) : (
        <div className="admin-settings-workbench">
          <aside className="admin-settings-nav">
            <div className="admin-settings-nav-head">
              <FileSliders size={18} />
              <span><strong>控制范围</strong><small>{groups.length} 个配置域</small></span>
            </div>
            <nav aria-label="应用设置分组">
              {groups.map((group) => {
                const meta = metaFor(group);
                const count = settings.filter((item) => item.groupName === group).length;
                const changed = settings.filter((item) => item.groupName === group && dirtyKeys.includes(item.key)).length;
                return (
                  <button className={activeGroup === group ? "active" : ""} key={group} type="button" onClick={() => setActiveGroup(group)}>
                    {meta.icon}
                    <span><strong>{meta.label}</strong><small>{meta.description}</small></span>
                    <b>{changed || count}</b>
                  </button>
                );
              })}
            </nav>
            <div className="admin-runtime-note">
              <ServerCog size={17} />
              <p><strong>动态配置边界</strong><span>部署端口、数据库凭据等启动参数仍由服务端环境管理。</span></p>
            </div>
          </aside>

          <form className="admin-settings-form" onSubmit={saveSettings}>
            <header>
              <div className="admin-settings-section-icon">{activeMeta.icon}</div>
              <div>
                <h3>{activeMeta.label}</h3>
                <p>{activeMeta.description}</p>
              </div>
              <span>{visibleSettings.length} 项 · 更新于 {formatTime(latestUpdate)}</span>
            </header>
            {error && settings.length > 0 && <div className="form-error">{error}</div>}
            <div className="admin-setting-list">
              {visibleSettings.map((item) => (
                <SettingField
                  key={item.key}
                  item={item}
                  value={values[item.key] ?? ""}
                  dirty={dirtyKeys.includes(item.key)}
                  error={validationErrors[item.key]}
                  onChange={(value) => updateValue(item, value)}
                />
              ))}
              {!visibleSettings.length && <p className="muted-line">当前分组没有配置项</p>}
            </div>
            <footer className="admin-settings-actions">
              <span>{dirtyKeys.length ? `共有 ${dirtyKeys.length} 项修改尚未保存` : "所有配置均已同步"}</span>
              <div>
                <button className="soft-button" type="button" disabled={!activeDirtyCount || saving} onClick={resetActiveGroup}>
                  <RotateCcw size={16} />放弃本组修改
                </button>
                <button className="primary-button" type="submit" disabled={!dirtyKeys.length || saving}>
                  {saving ? <Loader2 className="spin" size={16} /> : <Save size={16} />}
                  {dirtyKeys.length ? `保存 ${dirtyKeys.length} 项修改` : "暂无修改"}
                </button>
              </div>
            </footer>
          </form>
        </div>
      )}
    </section>
  );
}
