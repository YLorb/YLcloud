import { FormEvent, useEffect, useMemo, useState } from "react";
import { CheckCircle2, FileSliders, Loader2, RefreshCw, Settings2 } from "lucide-react";
import { api } from "../../api";
import type { Notice } from "../../appTypes";
import type { SiteSetting } from "../../types";

const settingGroupLabels: Record<string, string> = {
  site: "站点信息",
  file: "文件与分享",
  ai: "AI / RAG"
};

export function SettingsPanel({ onNotice }: { onNotice: (notice: Notice) => void }) {
  const [settings, setSettings] = useState<SiteSetting[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [activeGroup, setActiveGroup] = useState("site");
  const [error, setError] = useState("");

  async function loadSettings() {
    setLoading(true);
    setError("");
    try {
      const data = await api.adminSettings();
      setSettings(data);
      const nextValues: Record<string, string> = {};
      data.forEach((item) => {
        nextValues[item.key] = item.secret ? "" : item.value ?? "";
      });
      setValues(nextValues);
      if (data.length && !data.some((item) => item.groupName === activeGroup)) {
        setActiveGroup(data[0].groupName);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "设置加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadSettings();
  }, []);

  const groups = useMemo(() => Array.from(new Set(settings.map((item) => item.groupName))), [settings]);
  const visibleSettings = settings.filter((item) => item.groupName === activeGroup);
  const editableCount = settings.filter((item) => item.editable).length;
  const secretCount = settings.filter((item) => item.secret).length;
  const activeGroupLabel = settingGroupLabels[activeGroup] || activeGroup;

  function updateValue(key: string, value: string) {
    setValues((current) => ({ ...current, [key]: value }));
  }

  async function saveSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError("");
    try {
      await api.updateAdminSettings(settings.map((item) => ({ key: item.key, value: values[item.key] ?? "" })));
      await loadSettings();
      onNotice({ type: "success", text: "系统设置已保存" });
    } catch (err) {
      const message = err instanceof Error ? err.message : "保存设置失败";
      setError(message);
      onNotice({ type: "error", text: message });
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="settings-panel">
      <div className="settings-head">
        <div>
          <p>管理面板</p>
          <h2>参数设置</h2>
          <span>站点、文件与 AI / RAG 参数统一维护。敏感项留空时不会覆盖原值。</span>
        </div>
        <div className="settings-head-actions">
          <span className="settings-health">
            <CheckCircle2 size={16} />
            {settings.length ? `${settings.length} 项配置` : "等待加载"}
          </span>
          <button className="icon-button" type="button" onClick={() => void loadSettings()} title="刷新设置">
            <RefreshCw size={18} />
          </button>
        </div>
      </div>

      <div className="settings-summary-grid">
        <div>
          <small>配置分组</small>
          <strong>{groups.length}</strong>
          <span>按业务域维护</span>
        </div>
        <div>
          <small>可编辑项</small>
          <strong>{editableCount}</strong>
          <span>保存后立即生效</span>
        </div>
        <div>
          <small>敏感配置</small>
          <strong>{secretCount}</strong>
          <span>留空保留原值</span>
        </div>
      </div>

      {loading ? (
        <div className="loading-state settings-loading">
          <Loader2 className="spin" size={24} />
          正在加载系统设置
        </div>
      ) : error && !settings.length ? (
        <div className="error-state settings-error">
          <strong>加载失败</strong>
          <span>{error}</span>
          <button type="button" onClick={() => void loadSettings()}>
            重试
          </button>
        </div>
      ) : (
        <div className="settings-workbench">
          <nav className="settings-tabs" aria-label="设置分组">
            {groups.map((group) => {
              const count = settings.filter((item) => item.groupName === group).length;
              return (
                <button
                  className={activeGroup === group ? "active" : ""}
                  key={group}
                  type="button"
                  onClick={() => setActiveGroup(group)}
                >
                  <span>{settingGroupLabels[group] || group}</span>
                  <small>{count}</small>
                </button>
              );
            })}
          </nav>

          <div className="settings-layout">
            <aside className="settings-context">
              <div className="settings-context-icon">
                <Settings2 size={22} />
              </div>
              <h3>{activeGroupLabel}</h3>
              <p>
                当前分组包含 {visibleSettings.length} 项配置，其中 {visibleSettings.filter((item) => item.editable).length} 项可编辑。
              </p>
              <dl>
                <div>
                  <dt>分组 key</dt>
                  <dd>{activeGroup}</dd>
                </div>
                <div>
                  <dt>敏感项</dt>
                  <dd>{visibleSettings.filter((item) => item.secret).length}</dd>
                </div>
              </dl>
            </aside>

            <form className="settings-form" onSubmit={saveSettings}>
              <div className="settings-form-head">
                <div>
                  <FileSliders size={18} />
                  <h3>{activeGroupLabel}</h3>
                </div>
                <span>{visibleSettings.length} 项</span>
              </div>
              {error && <div className="form-error">{error}</div>}
              <div className="setting-field-list">
                {visibleSettings.map((item) => (
                  <label className={`setting-field ${item.valueType === "boolean" ? "boolean-field" : ""}`} key={item.key}>
                    <span>
                      <strong>{item.label || item.key}</strong>
                      <small>{item.description || item.key}</small>
                    </span>
                    {item.valueType === "boolean" ? (
                      <input
                        type="checkbox"
                        checked={(values[item.key] ?? "").toLowerCase() === "true"}
                        disabled={!item.editable}
                        onChange={(event) => updateValue(item.key, event.target.checked ? "true" : "false")}
                      />
                    ) : (
                      <input
                        type={item.secret ? "password" : item.valueType === "number" ? "number" : "text"}
                        value={values[item.key] ?? ""}
                        disabled={!item.editable}
                        placeholder={item.secret ? `${item.maskedValue || "未配置"}，留空则不修改` : item.key}
                        onChange={(event) => updateValue(item.key, event.target.value)}
                      />
                    )}
                  </label>
                ))}
              </div>

              <div className="settings-actions">
                <button className="soft-button" type="button" onClick={() => void loadSettings()} disabled={saving}>
                  重置
                </button>
                <button className="primary-button" type="submit" disabled={saving}>
                  {saving && <Loader2 className="spin" size={16} />}
                  保存设置
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </section>
  );
}
