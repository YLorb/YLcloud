import * as Tabs from "@radix-ui/react-tabs";
import { useMutation } from "@tanstack/react-query";
import { Lock, Search, FileText, Database, Clock, ChevronDown, ChevronUp } from "lucide-react";
import { useState } from "react";
import { api } from "../../../api";
import { Button } from "../../../components/ui/Button";
import { LoadingState, ErrorState } from "../../../components/ui/PageState";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import type { AdminFullTextSearchFileHit } from "../../../types";

const otherTabs = [
  { key: "params", label: "参数设置", available: false },
  { key: "icons", label: "文件图标", available: false },
  { key: "preview", label: "文件在线浏览应用", available: false }
];

const matchTypeLabels: Record<string, { label: string; tone: "success" | "info" | "warning" }> = {
  WORD_MATCH: { label: "词语匹配", tone: "success" },
  KEYWORD: { label: "关键词", tone: "info" },
  VECTOR: { label: "向量同义", tone: "warning" }
};

export function FileSystemPage() {
  return (
    <div className="admin-filesystem-page">
      <Tabs.Root defaultValue="search" className="admin-horizontal-tabs">
        <Tabs.List className="admin-tabs-list" aria-label="文件系统">
          <Tabs.Trigger value="search">全文搜索</Tabs.Trigger>
          {otherTabs.map(({ key, label }) => (
            <Tabs.Trigger key={key} value={key} className="admin-tab--unavailable">
              {label}
              <Lock size={12} />
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        <Tabs.Content value="search">
          <FullTextSearchPanel />
        </Tabs.Content>
        {otherTabs.map(({ key, label }) => (
          <Tabs.Content key={key} value={key}>
            <div className="admin-page-placeholder">
              <Lock size={36} />
              <strong>{label}</strong>
              <p>功能尚未开放，敬请期待</p>
            </div>
          </Tabs.Content>
        ))}
      </Tabs.Root>
    </div>
  );
}

function FullTextSearchPanel() {
  const [enabled, setEnabled] = useState(true);
  const [query, setQuery] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [expandedFiles, setExpandedFiles] = useState<Set<number>>(new Set());

  const search = useMutation({
    mutationFn: () => api.adminFullTextSearch({ query: searchQuery, pageSize: 50 }),
    onError: () => {}
  });

  function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) return;
    setSearchQuery(trimmed);
    search.mutate();
  }

  function toggleExpand(docId: number) {
    setExpandedFiles((prev) => {
      const next = new Set(prev);
      next.has(docId) ? next.delete(docId) : next.add(docId);
      return next;
    });
  }

  return (
    <div className="admin-fulltext-search">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">文件系统</span>
          <h2>全文搜索</h2>
          <p>基于 Qdrant 向量库的智能全文检索，支持词语匹配、关键词检索和向量同义检索</p>
        </div>
        <label className="admin-toggle-switch">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          <span className="admin-toggle-thumb" />
          <span>{enabled ? "已启用" : "已关闭"}</span>
        </label>
      </header>

      {!enabled && (
        <div className="admin-fulltext-disabled">
          <Database size={32} />
          <strong>全文搜索已关闭</strong>
          <p>开启后，管理员可以通过关键词或语义搜索所有已索引的文件内容。搜索基于 Qdrant 向量库，支持三种检索模式：</p>
          <ul>
            <li><StatusBadge tone="success">词语匹配</StatusBadge> 精确匹配搜索词在文件内容中的出现</li>
            <li><StatusBadge tone="info">关键词检索</StatusBadge> 基于关键词的文本搜索</li>
            <li><StatusBadge tone="warning">向量同义检索</StatusBadge> 基于语义相似度的智能检索</li>
          </ul>
        </div>
      )}

      {enabled && (
        <>
          <form className="admin-fulltext-search-bar" onSubmit={handleSearch}>
            <div className="search-box">
              <Search size={17} />
              <input
                placeholder="输入关键词搜索文件内容..."
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
            </div>
            <Button type="submit" variant="primary" loading={search.isPending}>
              搜索
            </Button>
          </form>

          {search.isPending && <LoadingState label="正在搜索..." />}

          {search.isError && (
            <ErrorState
              message={search.error instanceof Error ? search.error.message : "搜索失败"}
              onRetry={() => search.mutate()}
            />
          )}

          {search.isSuccess && search.data && (
            <div className="admin-fulltext-results">
              <div className="admin-fulltext-summary">
                <span>找到 <strong>{search.data.total}</strong> 个相关文件</span>
                <span className="muted-text"><Clock size={14} /> 耗时 {search.data.tookMs}ms</span>
              </div>

              {search.data.files.length === 0 && (
                <div className="admin-page-placeholder">
                  <Search size={36} />
                  <strong>没有找到匹配的文件</strong>
                  <p>请尝试使用其他关键词搜索</p>
                </div>
              )}

              {search.data.files.map((file) => (
                <FullTextSearchResultItem
                  key={file.documentId}
                  file={file}
                  expanded={expandedFiles.has(file.documentId)}
                  onToggle={() => toggleExpand(file.documentId)}
                />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function FullTextSearchResultItem({
  file,
  expanded,
  onToggle
}: {
  file: AdminFullTextSearchFileHit;
  expanded: boolean;
  onToggle: () => void;
}) {
  const matchInfo = matchTypeLabels[file.matchType] || { label: file.matchType, tone: "neutral" as const };

  return (
    <div className="admin-fulltext-result-item">
      <div className="admin-fulltext-result-header" onClick={onToggle}>
        <div className="admin-fulltext-result-info">
          <FileText size={18} className="admin-fulltext-result-icon" />
          <div>
            <strong>{file.fileName}</strong>
            <small>{file.spaceName ? `${file.spaceName} / ` : ""}{file.fileType || "未知类型"} · {file.chunkCount} 个分块</small>
          </div>
        </div>
        <div className="admin-fulltext-result-actions">
          <StatusBadge tone={matchInfo.tone}>{matchInfo.label}</StatusBadge>
          {expanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
        </div>
      </div>

      {expanded && file.evidences && file.evidences.length > 0 && (
        <div className="admin-fulltext-evidences">
          <span className="admin-fulltext-evidence-label">检索证据</span>
          {file.evidences.map((ev, i) => (
            <div key={i} className="admin-fulltext-evidence-item">
              <code className="admin-fulltext-evidence-content">{ev.content}</code>
              {ev.score != null && (
                <span className="admin-fulltext-evidence-score">相似度: {ev.score.toFixed(4)}</span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
