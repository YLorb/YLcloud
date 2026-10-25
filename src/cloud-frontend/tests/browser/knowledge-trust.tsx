// Local browser fixture only. No API requests or production data are used.
import React, { useState } from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";
import { api } from "../../src/api";
import { KnowledgeSettings } from "../../src/features/knowledge/KnowledgeSettings";
import { EvidenceCitation } from "../../src/features/assistant/EvidenceCitation";
import type { RagConfig } from "../../src/types";
import "../../src/styles/app.css";

let saved: RagConfig = { chunkSize: 500, chunkOverlap: 50, topK: 10, scoreThreshold: 0.3,
  configRevision: 1, historicalEvidenceEnabled: true, historyRetentionDays: 90, allowStaleDuringReindex: false };
api.ragConfig = async () => ({ ...saved });
api.updateRagConfig = async (_id, payload) => { saved = { ...saved, ...payload, configRevision: (saved.configRevision || 0) + 1 }; return { ...saved }; };
api.retentionImpact = async () => ({ affectedRevisions: 2, currentlyAccessible: 5, configRevision: saved.configRevision! });
api.ragEvidence = async () => ({ citation: { spaceId: 1, evidenceId: "fixture-1", revisionId: 2, fileName: "退款说明.pdf", pageStart: 3 }, content: "申请退款需要保留购买凭证，商品未使用且包装完整。\n不适用于已完成交付的定制服务。" });
const client = new QueryClient();
function Fixture() {
  const [manage, setManage] = useState(true);
  return <QueryClientProvider client={client}><main style={{ maxWidth: 1040, padding: 24, margin: "0 auto" }}>
    <h1>知识库配置与引用核验</h1><p>本地合成数据验收，不会访问后端。</p>
    <button onClick={() => setManage(!manage)}>{manage ? "切换为普通成员" : "切换为管理员"}</button>
    <KnowledgeSettings spaceId={1} canManage={manage} onSaved={() => { void client.invalidateQueries({ queryKey: ["rag-config", 1] }); }} />
    <section className="citation-list" style={{ marginTop: 24 }}><h2>引用来源</h2><EvidenceCitation citation={{ spaceId: 1, evidenceId: "fixture-1", revisionId: 2, fileName: "退款说明.pdf", pageStart: 3, contentSummary: "申请退款的适用条件", stale: true }} index={0} /></section>
  </main><Toaster /></QueryClientProvider>;
}
ReactDOM.createRoot(document.getElementById("root")!).render(<Fixture />);
