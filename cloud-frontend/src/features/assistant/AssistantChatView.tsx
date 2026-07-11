import { Folder, LogOut, PanelLeft, ShieldCheck, Sparkles } from "lucide-react";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../api";
import type { Notice } from "../../appTypes";
import type { Space } from "../../types";
import { AssistantComposer } from "./AssistantComposer";
import { AssistantSidebar } from "./AssistantSidebar";
import { AssistantThread } from "./AssistantThread";
import {
  conversationFromSession,
  loadLocalConversations,
  mergeRemoteConversations,
  saveLocalConversations,
  type AssistantConversation,
  type AssistantMessage
} from "./assistantChat";

const quickPrompts = [
  { label: "总结文档", prompt: "总结当前知识库的核心内容，并列出关键结论。" },
  { label: "梳理要点", prompt: "梳理当前知识库中最重要的概念和它们之间的关系。" },
  { label: "查找资料", prompt: "查找与这个问题相关的资料：" }
];

export function AssistantChatView({
  onLogout,
  onNavigate,
  showNotice,
  userName
}: {
  onLogout: () => void;
  onNavigate: (path: string) => void;
  showNotice: (notice: Notice) => void;
  userName: string;
}) {
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [conversations, setConversations] = useState<AssistantConversation[]>(() => loadLocalConversations());
  const [activeConversationId, setActiveConversationId] = useState<number | string>("");
  const [selectedSpaceIds, setSelectedSpaceIds] = useState<number[]>([]);
  const [input, setInput] = useState("");
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [chatLoading, setChatLoading] = useState(false);
  const [scopeSaving, setScopeSaving] = useState(false);
  const [sourcePickerOpen, setSourcePickerOpen] = useState(false);
  const [mobileSessionsOpen, setMobileSessionsOpen] = useState(false);
  const scopeSavingRef = useRef(false);
  const chatLoadingRef = useRef(false);
  const detailRequestRef = useRef(0);

  const activeConversation = useMemo<AssistantConversation>(() => {
    const existing = conversations.find((conversation) => conversation.id === activeConversationId);
    if (existing) return existing;
    return {
      id: "draft",
      title: "新对话",
      messages: [],
      spaceIds: selectedSpaceIds,
      updatedAt: Date.now(),
      loaded: true
    };
  }, [activeConversationId, conversations, selectedSpaceIds]);
  const selectedSpaces = spaces.filter((space) => selectedSpaceIds.includes(space.id));

  useEffect(() => {
    api.listSpaces()
      .then((items) => {
        const next = items || [];
        setSpaces(next);
        setSelectedSpaceIds((current) => {
          if (current.length) return current;
          const preferred = next.find((space) => space.role === "OWNER") || next[0];
          return preferred ? [preferred.id] : [];
        });
      })
      .catch((error) => showNotice({ type: "error", text: error instanceof Error ? error.message : "知识库列表加载失败" }));
  }, []);

  useEffect(() => {
    let active = true;
    api.listKnowledgeChatSessions(undefined, 50)
      .then((sessions) => {
        if (!active) return;
        setConversations((current) => mergeRemoteConversations(sessions || [], current));
      })
      .catch((error) => {
        if (!active) return;
        showNotice({
          type: "error",
          text: error instanceof Error ? `${error.message}，已保留本地会话` : "会话恢复失败，已保留本地会话"
        });
      })
      .finally(() => {
        if (active) setSessionsLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    saveLocalConversations(conversations);
  }, [conversations]);

  function updateConversation(next: AssistantConversation, previousId?: number | string) {
    setConversations((current) => {
      const rest = current.filter((item) => item.id !== next.id && item.id !== previousId);
      return [next, ...rest];
    });
    setActiveConversationId(next.id);
  }

  function newChat() {
    detailRequestRef.current += 1;
    setActiveConversationId("");
    const preferred = spaces.find((space) => space.role === "OWNER") || spaces[0];
    setSelectedSpaceIds(preferred ? [preferred.id] : []);
    setInput("");
    setSourcePickerOpen(false);
    setMobileSessionsOpen(false);
  }

  async function pickConversation(id: number | string) {
    const selected = conversations.find((conversation) => conversation.id === id);
    if (!selected) return;
    const requestId = ++detailRequestRef.current;
    setActiveConversationId(id);
    setSelectedSpaceIds(selected.spaceIds);
    setSourcePickerOpen(false);
    setMobileSessionsOpen(false);
    if (typeof id !== "number" || selected.loaded) return;

    setSessionsLoading(true);
    try {
      const detailed = conversationFromSession(await api.getKnowledgeChatSession(id));
      if (requestId !== detailRequestRef.current) return;
      updateConversation(detailed);
      setSelectedSpaceIds(detailed.spaceIds);
    } catch (error) {
      if (requestId !== detailRequestRef.current) return;
      showNotice({ type: "error", text: error instanceof Error ? error.message : "会话内容加载失败" });
    } finally {
      if (requestId === detailRequestRef.current) setSessionsLoading(false);
    }
  }

  async function renameConversation(id: number | string, title: string) {
    const selected = conversations.find((conversation) => conversation.id === id);
    if (!selected || selected.title === title) return;
    try {
      if (typeof id === "number") await api.updateKnowledgeChatSessionTitle(id, title);
      setConversations((current) => current.map((conversation) => conversation.id === id ? { ...conversation, title } : conversation));
      showNotice({ type: "success", text: "会话已重命名" });
    } catch (error) {
      showNotice({ type: "error", text: error instanceof Error ? error.message : "会话重命名失败" });
    }
  }

  async function deleteConversation(id: number | string) {
    const selected = conversations.find((conversation) => conversation.id === id);
    if (!selected || !window.confirm(`删除会话“${selected.title}”？`)) return;
    try {
      if (typeof id === "number") await api.deleteKnowledgeChatSession(id);
      setConversations((current) => current.filter((conversation) => conversation.id !== id));
      if (activeConversationId === id) newChat();
      showNotice({ type: "success", text: "会话已删除" });
    } catch (error) {
      showNotice({ type: "error", text: error instanceof Error ? error.message : "会话删除失败" });
    }
  }

  async function toggleSpace(spaceId: number) {
    if (scopeSavingRef.current) return;
    const previous = selectedSpaceIds;
    const next = previous.includes(spaceId)
      ? previous.filter((id) => id !== spaceId)
      : [...previous, spaceId].slice(0, 5);
    if (!next.length) {
      showNotice({ type: "info", text: "至少保留一个知识库" });
      return;
    }
    setSelectedSpaceIds(next);
    if (activeConversation.id === "draft") return;

    const updated = { ...activeConversation, spaceIds: next, updatedAt: Date.now() };
    updateConversation(updated);
    if (typeof activeConversation.id !== "number") return;
    scopeSavingRef.current = true;
    setScopeSaving(true);
    try {
      await api.updateKnowledgeChatSessionScope(activeConversation.id, next);
    } catch (error) {
      setSelectedSpaceIds(previous);
      updateConversation({ ...activeConversation, spaceIds: previous });
      showNotice({ type: "error", text: error instanceof Error ? error.message : "知识库范围更新失败" });
    } finally {
      scopeSavingRef.current = false;
      setScopeSaving(false);
    }
  }

  async function flushPendingMessages(sessionId: number, conversation: AssistantConversation) {
    let changed = false;
    const messages: AssistantMessage[] = [];
    for (const message of conversation.messages) {
      if (!message.pendingSync) {
        messages.push(message);
        continue;
      }
      const saved = await api.appendKnowledgeChatMessage(sessionId, {
        role: message.role,
        content: message.content,
        citationsJson: message.citations?.length ? JSON.stringify(message.citations) : undefined
      });
      messages.push({ ...message, id: String(saved.id), pendingSync: false });
      changed = true;
    }
    const next = changed ? { ...conversation, messages, updatedAt: Date.now() } : conversation;
    if (changed) updateConversation(next);
    return next;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const question = input.trim();
    if (!question || !selectedSpaceIds.length || scopeSavingRef.current || chatLoadingRef.current) return;

    chatLoadingRef.current = true;
    setChatLoading(true);
    setInput("");
    const previousId = activeConversation.id;
    let userSaved = false;
    try {
      let sessionId: number;
      let persistedBase = activeConversation;
      if (typeof activeConversation.id === "number") {
        sessionId = activeConversation.id;
        persistedBase = await flushPendingMessages(sessionId, activeConversation);
      } else {
        const created = await api.createKnowledgeChatSession({
          title: activeConversation.title === "新对话" ? question.slice(0, 24) : activeConversation.title,
          scopeMode: selectedSpaceIds.length > 1 ? "multi-space" : "single-space",
          spaceIds: selectedSpaceIds
        });
        sessionId = created.id;
        for (const message of activeConversation.messages) {
          await api.appendKnowledgeChatMessage(sessionId, {
            role: message.role,
            content: message.content,
            citationsJson: message.citations?.length ? JSON.stringify(message.citations) : undefined
          });
        }
        persistedBase = { ...activeConversation, id: sessionId, spaceIds: selectedSpaceIds, loaded: true };
      }

      const savedUser = await api.appendKnowledgeChatMessage(sessionId, { role: "user", content: question });
      userSaved = true;
      const pending: AssistantConversation = {
        ...persistedBase,
        id: sessionId,
        title: persistedBase.title === "新对话" ? question.slice(0, 24) : persistedBase.title,
        spaceIds: selectedSpaceIds,
        messages: [...persistedBase.messages, { id: String(savedUser.id), role: "user", content: question }],
        updatedAt: Date.now(),
        loaded: true
      };
      updateConversation(pending, previousId);

      const history = persistedBase.messages
        .slice(-8)
        .filter((message) => message.content.trim())
        .map((message) => ({ role: message.role, content: message.content.trim() }));
      const answer = await api.queryKnowledgeRag({ spaceIds: selectedSpaceIds, question, history });
      const answerText = answer.answer || "暂无回答";
      const localAssistant: AssistantMessage = {
        id: `pending-${Date.now()}`,
        role: "assistant",
        content: answerText,
        citations: answer.citations || [],
        pendingSync: true
      };
      const answered = { ...pending, messages: [...pending.messages, localAssistant], updatedAt: Date.now() };
      updateConversation(answered);

      try {
        const savedAssistant = await api.appendKnowledgeChatMessage(sessionId, {
          role: "assistant",
          content: answerText,
          citationsJson: answer.citations?.length ? JSON.stringify(answer.citations) : undefined
        });
        updateConversation({
          ...answered,
          messages: answered.messages.map((message) => message.id === localAssistant.id
            ? { ...message, id: String(savedAssistant.id), pendingSync: false }
            : message),
          updatedAt: Date.now()
        });
      } catch (error) {
        showNotice({
          type: "error",
          text: error instanceof Error ? `回答已保留在本机，云端同步失败：${error.message}` : "回答已保留在本机，云端同步失败"
        });
      }
    } catch (error) {
      if (!userSaved) setInput(question);
      showNotice({ type: "error", text: error instanceof Error ? error.message : "知识库问答失败" });
    } finally {
      chatLoadingRef.current = false;
      setChatLoading(false);
    }
  }

  const hasMessages = activeConversation.messages.length > 0;

  return (
    <section className="assistant-workspace">
      <AssistantSidebar
        activeId={activeConversation.id}
        conversations={conversations}
        loading={sessionsLoading}
        mobileOpen={mobileSessionsOpen}
        onCloseMobile={() => setMobileSessionsOpen(false)}
        onDelete={(id) => void deleteConversation(id)}
        onNew={newChat}
        onPick={(id) => void pickConversation(id)}
        onRename={renameConversation}
      />

      <main className="assistant-main">
        <header className="assistant-topbar">
          <div className="assistant-topbar-left">
            <button type="button" className="assistant-menu-button" aria-label="打开会话列表" onClick={() => setMobileSessionsOpen(true)}>
              <PanelLeft size={19} />
            </button>
            <div>
              <h1>{hasMessages ? activeConversation.title : "智能知识助手"}</h1>
              <span><ShieldCheck size={13} />基于已选知识库回答</span>
            </div>
          </div>
          <div className="assistant-topbar-actions">
            <button type="button" title="返回我的文件" aria-label="返回我的文件" onClick={() => onNavigate("/files")}>
              <Folder size={18} />
            </button>
            <span>{userName}</span>
            <button type="button" title="退出登录" aria-label="退出登录" onClick={onLogout}>
              <LogOut size={18} />
            </button>
          </div>
        </header>

        <div className={`assistant-stage ${hasMessages ? "has-thread" : "empty"}`}>
          {hasMessages ? (
            <AssistantThread conversation={activeConversation} loading={chatLoading} />
          ) : (
            <div className="assistant-welcome">
              <span className="assistant-welcome-mark"><Sparkles size={24} /></span>
              <h1>今天想从知识库了解什么？</h1>
              <p>
                {selectedSpaces.length
                  ? `正在使用：${selectedSpaces.map((space) => space.name).join("、")}`
                  : "选择知识来源后，即可基于文档内容提问。"}
              </p>
              <div className="assistant-quick-prompts">
                {quickPrompts.map((item) => (
                  <button type="button" key={item.label} onClick={() => setInput(item.prompt)}>
                    <strong>{item.label}</strong>
                    <span>{item.prompt}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="assistant-composer-dock">
            <AssistantComposer
              input={input}
              loading={chatLoading}
              pickerOpen={sourcePickerOpen}
              scopeSaving={scopeSaving}
              selectedSpaceIds={selectedSpaceIds}
              spaces={spaces}
              onInput={setInput}
              onSubmit={(event) => void submit(event)}
              onTogglePicker={() => setSourcePickerOpen((current) => !current)}
              onToggleSpace={(id) => void toggleSpace(id)}
            />
          </div>
        </div>
      </main>
    </section>
  );
}
