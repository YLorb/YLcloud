import { FormEvent, useEffect, useMemo, useState } from "react";
import { AlertCircle, Clock3, Cpu, Database, Loader2, RotateCcw, Save, SlidersHorizontal } from "lucide-react";
import { api } from "../../api";
import type { Notice } from "../../appTypes";
import type { RagConfig, RagConfigLog, Space } from "../../types";
import { formatTime } from "../../fileUtils";

type ConfigDraft = {
  enabled: boolean;
  knowledgeProfileEnabled: boolean;
  topK: string;
  temperature: string;
  scoreThreshold: string;
  chunkSize: string;
  chunkOverlap: string;
};

const emptyDraft: ConfigDraft = {
  enabled: false,
  knowledgeProfileEnabled: true,
  topK: "5",
  temperature: "0.2",
  scoreThreshold: "0",
  chunkSize: "1000",
  chunkOverlap: "100"
};

function draftFromConfig(config: RagConfig): ConfigDraft {
  return {
    enabled: Boolean(config.enabled),
    knowledgeProfileEnabled: config.knowledgeProfileEnabled !== 0,
    topK: String(config.topK ?? 5),
    temperature: String(config.temperature ?? 0.2),
    scoreThreshold: String(config.scoreThreshold ?? 0),
    chunkSize: String(config.chunkSize ?? 1000),
    chunkOverlap: String(config.chunkOverlap ?? 100)
  };
}

function configSummary(raw?: string) {
  if (!raw) return "无快照";
  try {
    const value = JSON.parse(raw) as Partial<RagConfig>;
    return [
      value.topK === undefined ? "" : `Top K ${value.topK}`,
      value.temperature === undefined ? "" : `温度 ${value.temperature}`,
      value.scoreThreshold === undefined ? "" : `阈值 ${value.scoreThreshold}`,
      value.enabled === undefined ? "" : value.enabled ? "已启用" : "已停用",
      value.knowledgeProfileEnabled === undefined ? "" : value.knowledgeProfileEnabled ? "知识画像已开启" : "知识画像已关闭"
    ].filter(Boolean).join(" · ") || "配置快照";
  } catch {
    return "配置快照";
  }
}

export function KnowledgeSettingsView({
  space,
  reloadKey,
  showNotice
}: {
  space: Space | null;
  reloadKey: number;
  showNotice: (notice: Notice) => void;
}) {
  const [config, setConfig] = useState<RagConfig | null>(null);
  const [draft, setDraft] = useState<ConfigDraft>(emptyDraft);
  const [logs, setLogs] = useState<RagConfigLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [validationError, setValidationError] = useState("");

  const baseline = useMemo(() => config ? draftFromConfig(config) : emptyDraft, [config]);
  const dirty = JSON.stringify(draft) !== JSON.stringify(baseline);

  async function load() {
    if (!space) {
      setConfig(null);
      setLogs([]);
      setDraft(emptyDraft);
      return;
    }
    setLoading(true);
    setError("");
    try {
      const [nextConfig, nextLogs] = await Promise.all([
        api.ragConfig(space.id),
        api.ragAnalyticsConfigLogs(space.id, 20)
      ]);
      setConfig(nextConfig);
      setDraft(draftFromConfig(nextConfig));
      setLogs(nextLogs || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : "知识库设置加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [space?.id, reloadKey]);

  function update<K extends keyof ConfigDraft>(key: K, value: ConfigDraft[K]) {
    setValidationError("");
    setDraft((current) => ({ ...current, [key]: value }));
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!space || !config || !dirty) return;

    const chunkSize = Number(draft.chunkSize);
    const chunkOverlap = Number(draft.chunkOverlap);
    const topK = Number(draft.topK);
    const temperature = Number(draft.temperature);
    const scoreThreshold = Number(draft.scoreThreshold);
    if (!Number.isInteger(chunkSize) || chunkSize < 1) return setValidationError("分块大小必须是大于 0 的整数");
    if (!Number.isInteger(chunkOverlap) || chunkOverlap < 0 || chunkOverlap >= chunkSize) {
      return setValidationError("重叠长度必须是非负整数，并且小于分块大小");
    }
    if (!Number.isInteger(topK) || topK < 1) return setValidationError("召回数量必须是大于 0 的整数");
    if (!Number.isFinite(temperature) || temperature < 0 || temperature > 1) return setValidationError("温度必须在 0 到 1 之间");
    if (!Number.isFinite(scoreThreshold) || scoreThreshold < 0 || scoreThreshold > 1) return setValidationError("分数阈值必须在 0 到 1 之间");

    setSaving(true);
    setValidationError("");
    try {
      const next = await api.updateRagConfig(space.id, {
        enabled: draft.enabled ? 1 : 0,
        knowledgeProfileEnabled: draft.knowledgeProfileEnabled ? 1 : 0,
        topK,
        temperature,
        scoreThreshold,
        chunkSize,
        chunkOverlap
      });
      setConfig(next);
      setDraft(draftFromConfig(next));
      setLogs(await api.ragAnalyticsConfigLogs(space.id, 20));
      showNotice({ type: "success", text: "知识库检索设置已保存" });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库设置保存失败" });
    } finally {
      setSaving(false);
    }
  }

  if (!space) {
    return <div className="kb-settings-empty"><SlidersHorizontal size={28} /><h3>选择一个知识库</h3><p>选择后可调整检索参数并查看配置变更记录。</p></div>;
  }

  if (loading && !config) {
    return <div className="loading-state" aria-busy="true"><Loader2 className="spin" size={22} />正在读取知识库设置</div>;
  }

  if (error && !config) {
    return <div className="error-state" role="alert"><AlertCircle size={22} /><strong>设置加载失败</strong><span>{error}</span><button type="button" onClick={() => void load()}>重新加载</button></div>;
  }

  return (
    <div className="kb-settings-layout">
      <form className="kb-settings-form" onSubmit={save} aria-busy={saving}>
        <header className="kb-settings-section-head">
          <div><SlidersHorizontal size={19} /><span><h3>检索与生成</h3><p>这些参数直接影响召回范围和回答风格。</p></span></div>
          <label className="kb-switch">
            <input type="checkbox" checked={draft.enabled} onChange={(event) => update("enabled", event.target.checked)} />
            <span>{draft.enabled ? "RAG 已启用" : "RAG 已停用"}</span>
          </label>
        </header>

        <div className="kb-setting-fields">
          <label htmlFor="kb-top-k"><span><b>召回数量 Top K</b><small>每次查询进入重排和回答阶段的候选片段数。</small></span><input id="kb-top-k" type="number" min="1" step="1" value={draft.topK} onChange={(event) => update("topK", event.target.value)} /></label>
          <label htmlFor="kb-score-threshold"><span><b>相关性阈值</b><small>过滤低相关片段，降低无依据回答和错误引用。</small></span><input id="kb-score-threshold" type="number" min="0" max="1" step="0.01" value={draft.scoreThreshold} onChange={(event) => update("scoreThreshold", event.target.value)} /></label>
          <label className="kb-range-field" htmlFor="kb-temperature"><span><b>回答温度</b><small>低温更忠于知识库，高温表达更灵活。</small></span><div><input id="kb-temperature" type="range" min="0" max="1" step="0.05" value={draft.temperature} onChange={(event) => update("temperature", event.target.value)} /><output htmlFor="kb-temperature">{Number(draft.temperature || 0).toFixed(2)}</output></div></label>
        </div>

        <div className="kb-settings-divider" />
        <header className="kb-settings-section-head compact">
          <div>
            <Cpu size={19} />
            <span>
              <h3>知识画像增强</h3>
              <p>RAG 索引完成后可选执行。关闭后文档仍可检索，只跳过画像生成。</p>
            </span>
          </div>
          <label className="kb-switch">
            <input
              type="checkbox"
              checked={draft.knowledgeProfileEnabled}
              onChange={(event) => update("knowledgeProfileEnabled", event.target.checked)}
            />
            <span>{draft.knowledgeProfileEnabled ? "知识画像已开启" : "知识画像已关闭"}</span>
          </label>
        </header>

        <div className="kb-settings-divider" />
        <header className="kb-settings-section-head compact"><div><Database size={19} /><span><h3>文档分块</h3><p>新建索引任务时使用；修改后建议重建知识库索引。</p></span></div></header>
        <div className="kb-setting-fields two-col">
          <label htmlFor="kb-chunk-size"><span><b>分块大小</b><small>单个知识片段的目标长度。</small></span><input id="kb-chunk-size" type="number" min="1" step="1" value={draft.chunkSize} onChange={(event) => update("chunkSize", event.target.value)} /></label>
          <label htmlFor="kb-chunk-overlap"><span><b>重叠长度</b><small>相邻片段保留的上下文长度。</small></span><input id="kb-chunk-overlap" type="number" min="0" step="1" value={draft.chunkOverlap} onChange={(event) => update("chunkOverlap", event.target.value)} /></label>
        </div>

        {validationError && <div className="form-error" role="alert">{validationError}</div>}
        <footer className="kb-settings-actions">
          <span>{dirty ? "有尚未保存的修改" : `最近更新 ${formatTime(config?.updatetime)}`}</span>
          <div>
            <button className="soft-button" type="button" disabled={!dirty || saving} onClick={() => { setDraft(baseline); setValidationError(""); }}><RotateCcw size={16} />撤销修改</button>
            <button className="primary-button" type="submit" disabled={!dirty || saving}>{saving ? <Loader2 className="spin" size={16} /> : <Save size={16} />}保存设置</button>
          </div>
        </footer>
      </form>

      <aside className="kb-settings-aside">
        <section className="kb-runtime-card">
          <header><Cpu size={18} /><h3>当前运行配置</h3></header>
          <dl>
            <div><dt>向量模型</dt><dd>{config?.embeddingModel || "未记录"}</dd></div>
            <div><dt>回答模型</dt><dd>{config?.chatModel || "未记录"}</dd></div>
            <div><dt>向量集合</dt><dd>{config?.vectorCollection || "未创建"}</dd></div>
            <div><dt>知识画像</dt><dd>{config?.knowledgeProfileEnabled === 0 ? "已关闭" : "已开启"}</dd></div>
            <div><dt>运行状态</dt><dd>{config?.status === 1 ? "可用" : "未就绪"}</dd></div>
          </dl>
        </section>

        <section className="kb-config-history">
          <header><Clock3 size={18} /><span><h3>配置变更</h3><p>最近 {logs.length} 条审计记录</p></span></header>
          <div>
            {logs.slice(0, 10).map((log) => (
              <details key={log.id}>
                <summary><span><b>{log.changedFields || "RAG 配置"}</b><small>{formatTime(log.createtime)} · 操作人 {log.operatorId || "-"}</small></span></summary>
                <p><span>修改前</span>{configSummary(log.beforeJson)}</p>
                <p><span>修改后</span>{configSummary(log.afterJson)}</p>
              </details>
            ))}
            {!logs.length && <p className="muted-line">还没有配置变更记录</p>}
          </div>
        </section>
      </aside>
    </div>
  );
}
