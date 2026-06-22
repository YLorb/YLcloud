import { FormEvent, useEffect, useMemo, useState } from "react";
import { Loader2, RefreshCw } from "lucide-react";
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
          <h2>系统设置</h2>
          <p>管理员可在这里维护站点信息、访问地址、注册开关和模型服务配置。</p>
        </div>
        <button className="icon-button" type="button" onClick={() => void loadSettings()} title="刷新设置">
          <RefreshCw size={18} />
        </button>
      </div>

      {loading ? (
        <div className="loading-state">
          <Loader2 className="spin" size={24} />
          正在加载系统设置
        </div>
      ) : error && !settings.length ? (
        <div className="error-state">
          <strong>加载失败</strong>
          <span>{error}</span>
          <button type="button" onClick={() => void loadSettings()}>
            重试
          </button>
        </div>
      ) : (
        <div className="settings-layout">
          <nav className="settings-tabs" aria-label="设置分组">
            {groups.map((group) => (
              <button
                className={activeGroup === group ? "active" : ""}
                key={group}
                type="button"
                onClick={() => setActiveGroup(group)}
              >
                {settingGroupLabels[group] || group}
              </button>
            ))}
          </nav>

          <form className="settings-form" onSubmit={saveSettings}>
            {error && <div className="form-error">{error}</div>}
            {visibleSettings.map((item) => (
              <label className="setting-field" key={item.key}>
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
      )}
    </section>
  );
}
