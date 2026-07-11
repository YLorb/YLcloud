import { Check, Cloud, Loader2, MessageSquare, Pencil, Plus, Search, Trash2, X } from "lucide-react";
import { FormEvent, useMemo, useState } from "react";
import { groupConversations, type AssistantConversation } from "./assistantChat";

export function AssistantSidebar({
  activeId,
  conversations,
  loading,
  mobileOpen,
  onCloseMobile,
  onDelete,
  onNew,
  onPick,
  onRename
}: {
  activeId: number | string;
  conversations: AssistantConversation[];
  loading: boolean;
  mobileOpen: boolean;
  onCloseMobile: () => void;
  onDelete: (id: number | string) => void;
  onNew: () => void;
  onPick: (id: number | string) => void;
  onRename: (id: number | string, title: string) => Promise<void>;
}) {
  const [query, setQuery] = useState("");
  const [editingId, setEditingId] = useState<number | string | null>(null);
  const [editingTitle, setEditingTitle] = useState("");
  const [saving, setSaving] = useState(false);
  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return normalized
      ? conversations.filter((conversation) => conversation.title.toLowerCase().includes(normalized))
      : conversations;
  }, [conversations, query]);

  function beginRename(conversation: AssistantConversation) {
    setEditingId(conversation.id);
    setEditingTitle(conversation.title);
  }

  async function submitRename(event: FormEvent) {
    event.preventDefault();
    const title = editingTitle.trim();
    if (editingId === null || !title) return;
    setSaving(true);
    try {
      await onRename(editingId, title);
      setEditingId(null);
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <button
        className={`assistant-drawer-backdrop ${mobileOpen ? "open" : ""}`}
        type="button"
        aria-label="关闭会话列表"
        onClick={onCloseMobile}
      />
      <aside className={`assistant-sidebar ${mobileOpen ? "mobile-open" : ""}`} aria-label="历史会话">
        <div className="assistant-sidebar-head">
          <div>
            <span className="assistant-mark"><MessageSquare size={17} /></span>
            <strong>AI Assistant</strong>
          </div>
          <button className="assistant-mobile-close" type="button" aria-label="关闭会话列表" onClick={onCloseMobile}>
            <X size={19} />
          </button>
        </div>

        <button className="assistant-new-chat" type="button" onClick={onNew}>
          <Plus size={18} />
          新建对话
        </button>

        <label className="assistant-session-search">
          <Search size={16} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索会话" aria-label="搜索会话" />
          {query && (
            <button type="button" aria-label="清除搜索" onClick={() => setQuery("")}>
              <X size={14} />
            </button>
          )}
        </label>

        <div className="assistant-session-list">
          {groupConversations(filtered).map((group) => (
            <section key={group.label}>
              <h2>{group.label}</h2>
              {group.items.map((conversation) => (
                <div className={`assistant-session-row ${conversation.id === activeId ? "active" : ""}`} key={conversation.id}>
                  {editingId === conversation.id ? (
                    <form onSubmit={(event) => void submitRename(event)}>
                      <input
                        autoFocus
                        value={editingTitle}
                        maxLength={120}
                        aria-label="会话标题"
                        onChange={(event) => setEditingTitle(event.target.value)}
                      />
                      <button type="submit" disabled={saving || !editingTitle.trim()} aria-label="保存标题">
                        {saving ? <Loader2 className="spin" size={14} /> : <Check size={14} />}
                      </button>
                      <button type="button" aria-label="取消重命名" onClick={() => setEditingId(null)}>
                        <X size={14} />
                      </button>
                    </form>
                  ) : (
                    <>
                      <button className="assistant-session-title" type="button" onClick={() => onPick(conversation.id)}>
                        {conversation.title}
                      </button>
                      <span className="assistant-session-actions">
                        <button type="button" title="重命名" aria-label={`重命名 ${conversation.title}`} onClick={() => beginRename(conversation)}>
                          <Pencil size={14} />
                        </button>
                        <button type="button" title="删除" aria-label={`删除 ${conversation.title}`} onClick={() => onDelete(conversation.id)}>
                          <Trash2 size={14} />
                        </button>
                      </span>
                    </>
                  )}
                </div>
              ))}
            </section>
          ))}
          {loading && (
            <div className="assistant-session-loading" aria-label="正在恢复会话">
              <span /><span /><span />
            </div>
          )}
          {!loading && !filtered.length && <p className="assistant-session-empty">{query ? "没有匹配的会话" : "开始一次新的知识问答"}</p>}
        </div>

        <div className="assistant-sync-note">
          <Cloud size={15} />
          会话已同步到云端
        </div>
      </aside>
    </>
  );
}
