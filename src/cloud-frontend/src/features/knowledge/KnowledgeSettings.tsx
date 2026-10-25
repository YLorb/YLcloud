import * as Switch from "@radix-ui/react-switch";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Check } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { ErrorState, LoadingState } from "../../components/ui/PageState";
import type { RagConfig } from "../../types";

export function KnowledgeSettings({ spaceId, canManage, onSaved }: { spaceId: number; canManage: boolean; onSaved: () => void }) {
  const config = useQuery({ queryKey: ["rag-config", spaceId], queryFn: () => api.ragConfig(spaceId) });
  const [form, setForm] = useState<Partial<RagConfig>>({});
  const [error, setError] = useState("");
  const [checkingImpact, setCheckingImpact] = useState(false);
  const [impact, setImpact] = useState<{ affectedRevisions: number; currentlyAccessible: number; configRevision: number } | null>(null);
  const [proposed, setProposed] = useState<Partial<RagConfig> | null>(null);
  useEffect(() => {
    if (config.data) {
      setForm({ ...config.data, knowledgeProfileEnabled: config.data.knowledgeProfileEnabled ?? 1,
        allowStaleDuringReindex: config.data.allowStaleDuringReindex ?? false,
        historicalEvidenceEnabled: config.data.historicalEvidenceEnabled ?? false });
      setError("");
      setImpact(null); setProposed(null);
    }
  }, [config.data]);
  const save = useMutation({
    mutationFn: (payload: Partial<RagConfig>) => api.updateRagConfig(spaceId, payload),
    onSuccess: (saved) => { setForm(saved); setError(""); setImpact(null); setProposed(null); onSaved(); toast.success("知识库配置已保存"); },
    onError: (reason) => setError(reason instanceof Error ? reason.message : "保存失败，请稍后重试")
  });
  if (config.isLoading) return <LoadingState label="正在加载知识库配置" />;
  if (config.isError) return <ErrorState message={config.error instanceof Error ? config.error.message : "无法加载配置"} onRetry={() => config.refetch()} />;
  const days = form.historyRetentionDays;
  const invalidDays = form.historicalEvidenceEnabled && (!Number.isInteger(days) || Number(days) <= 0);
  const shortening = Boolean(config.data?.historicalEvidenceEnabled && form.historicalEvidenceEnabled
    && config.data.historyRetentionDays && days && days < config.data.historyRetentionDays);
  const reducingAccess = shortening || Boolean(config.data?.historicalEvidenceEnabled && !form.historicalEvidenceEnabled);
  const updateNumber = (key: keyof RagConfig, value: string) => setForm((current) => ({ ...current, [key]: Number(value) }));
  async function submit() {
    if (!canManage || invalidDays) return;
    setError("");
    if (!reducingAccess) { save.mutate(form); return; }
    setCheckingImpact(true);
    try {
      const preview = await api.retentionImpact(spaceId, Boolean(form.historicalEvidenceEnabled), days);
      if (preview.configRevision !== (form.configRevision ?? 0)) {
        setError("知识库配置已被其他管理员修改，请重新加载后确认影响。"); return;
      }
      setProposed({ ...form }); setImpact(preview);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法计算历史版本影响，配置未保存。"); }
    finally { setCheckingImpact(false); }
  }
  return <div className="settings-form">
    {!canManage && <p role="status">当前为只读配置，只有知识库所有者和管理员可以修改。</p>}
    <fieldset disabled={!canManage || save.isPending || checkingImpact} style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}>
      <section className="settings-section">
        <div><h2>切片与召回</h2><p>配置影响后续构建任务；已有索引需重建才能完全应用。</p></div>
        <div className="form-grid">
          <label className="field"><span>切片大小</span><input type="number" min={100} value={form.chunkSize ?? 500} onChange={(event) => updateNumber("chunkSize", event.target.value)} /></label>
          <label className="field"><span>切片重叠</span><input type="number" min={0} value={form.chunkOverlap ?? 50} onChange={(event) => updateNumber("chunkOverlap", event.target.value)} /></label>
          <label className="field"><span>Top K</span><input type="number" min={1} max={50} value={form.topK ?? 5} onChange={(event) => updateNumber("topK", event.target.value)} /></label>
          <label className="field"><span>相关度阈值</span><input type="number" min={0} max={1} step={0.05} value={form.scoreThreshold ?? 0.3} onChange={(event) => updateNumber("scoreThreshold", event.target.value)} /></label>
        </div>
      </section>
      <section className="settings-section">
        <div><h2>版本与回答依据</h2><p>配置对本知识库所有成员生效。删除文件或撤销权限后，不允许继续读取旧内容。</p></div>
        <div className="form-stack">
          <label className="switch-field"><Switch.Root className="switch-root" aria-label="更新期间允许使用旧版本" checked={Boolean(form.allowStaleDuringReindex)} onCheckedChange={(checked) => setForm((current) => ({ ...current, allowStaleDuringReindex: checked }))}><Switch.Thumb className="switch-thumb" /></Switch.Root><span>更新期间允许使用旧版本</span></label>
          <p>{form.allowStaleDuringReindex ? "新索引就绪前可使用上一次有效版本，回答会标明旧版本。" : "新版本提交后停止使用旧内容，新索引就绪前暂不可用于回答。"}</p>
          <label className="switch-field"><Switch.Root className="switch-root" aria-label="允许历史版本复核" checked={Boolean(form.historicalEvidenceEnabled)} onCheckedChange={(checked) => setForm((current) => ({ ...current, historicalEvidenceEnabled: checked }))}><Switch.Thumb className="switch-thumb" /></Switch.Root><span>允许历史版本复核</span></label>
          <p>开启后，仍有权限的成员可以在保留期内查看历史答案当时使用的版本。已清理的版本无法恢复。</p>
          {form.historicalEvidenceEnabled && <label className="field"><span>历史版本保留天数</span><input type="number" aria-label="历史版本保留天数" min={1} step={1} required aria-invalid={Boolean(invalidDays)} aria-describedby="history-retention-help" value={days ?? ""} onChange={(event) => setForm((current) => ({ ...current, historyRetentionDays: event.target.value === "" ? null : Number(event.target.value) }))} /><small id="history-retention-help">从版本被替代时开始计算。必须填写正整数，不默认无限保留。</small></label>}
          {invalidDays && <p role="alert" className="inline-error">开启历史版本复核前，请填写大于 0 的整数天数。</p>}
          {shortening && <p role="status">保留期限将缩短，超过新期限的历史引用将不可访问。已被其他文件或知识库使用的内容不会因此删除。</p>}
        </div>
      </section>
      <section className="settings-section">
        <div><h2>知识画像</h2><p>仅在索引成功且功能开启后运行。</p></div>
        <label className="switch-field"><Switch.Root className="switch-root" aria-label="启用知识画像" checked={(form.knowledgeProfileEnabled ?? 1) !== 0} onCheckedChange={(checked) => setForm((current) => ({ ...current, knowledgeProfileEnabled: checked ? 1 : 0 }))}><Switch.Thumb className="switch-thumb" /></Switch.Root><span>{(form.knowledgeProfileEnabled ?? 1) !== 0 ? "已开启" : "已关闭"}</span></label>
      </section>
    </fieldset>
    {error && <div role="alert" className="error-banner">{error}<Button variant="ghost" onClick={() => config.refetch()}>重新加载配置</Button></div>}
    {canManage && <div className="sticky-save"><span>版本策略保存后生效；切片参数影响新的构建任务。</span><Button variant="confirm" disabled={Boolean(invalidDays)} loading={save.isPending || checkingImpact} onClick={submit}><Check size={16} />保存配置</Button></div>}
    <Dialog open={Boolean(impact)} onOpenChange={(open) => { if (!open && !save.isPending) { setImpact(null); setProposed(null); } }} title="确认历史版本访问变化" description="此预览只计算本知识库的历史访问权益，不执行物理删除。">
      {impact && <div className="form-stack"><p>当前可复核的历史版本：{impact.currentlyAccessible} 个。保存后将有 <strong>{impact.affectedRevisions} 个</strong>历史版本不可访问。</p><p>当前版本和仍在过渡使用的版本不计入此数量。其他知识库及个人文件的保留权益不受影响。</p>{error && <p role="alert" className="inline-error">{error}</p>}<div className="dialog-footer-inline"><Button disabled={save.isPending} onClick={() => { setImpact(null); setProposed(null); }}>取消</Button><Button variant="danger" loading={save.isPending} onClick={() => proposed && save.mutate(proposed)}>确认变更</Button></div></div>}
    </Dialog>
  </div>;
}
