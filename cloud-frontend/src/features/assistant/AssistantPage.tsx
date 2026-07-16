import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertCircle, Bot, Check, ChevronLeft, FileText, MessageSquarePlus, Plus, RotateCw, Search, Send, Sparkles, Trash2, User } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import type { KnowledgeChatMessage, KnowledgeChatSession, RagCitation, RagChatMessage } from "../../types";

export function AssistantPage() {
  const client = useQueryClient();
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [newSessionDraft, setNewSessionDraft] = useState(false);
  const [sessionSearch, setSessionSearch] = useState("");
  const [question, setQuestion] = useState("");
  const [selectedSpaces, setSelectedSpaces] = useState<number[]>([]);
  const [deleteTarget, setDeleteTarget] = useState<KnowledgeChatSession | null>(null);
  const messageEnd = useRef<HTMLDivElement>(null);
  const initialSessionHydrated = useRef(false);
  const spaces = useQuery({ queryKey: ["spaces"], queryFn: api.listSpaces });
  const sessions = useQuery({ queryKey: ["chat-sessions", sessionSearch], queryFn: () => api.listKnowledgeChatSessions(sessionSearch), staleTime: 5_000 });
  const activeSession = useQuery({ queryKey: ["chat-session", sessionId], queryFn: () => api.getKnowledgeChatSession(sessionId!), enabled: Boolean(sessionId), refetchInterval: (query) => query.state.data?.messages?.some((message) => ["QUEUED", "RUNNING"].includes(message.taskStatus || "")) ? 2_000 : false });
  useEffect(() => {
    if (initialSessionHydrated.current || newSessionDraft || !sessions.isSuccess) return;
    initialSessionHydrated.current = true;
    if (sessions.data?.[0]) setSessionId(sessions.data[0].id);
  }, [newSessionDraft, sessions.data, sessions.isSuccess]);
  useEffect(() => { if (!newSessionDraft && activeSession.data?.spaceIds?.length) setSelectedSpaces(activeSession.data.spaceIds); }, [activeSession.data, newSessionDraft]);
  useEffect(() => {
    messageEnd.current?.scrollIntoView({ behavior: "smooth" });
  }, [activeSession.data?.messages]);

  const send = useMutation({
    mutationFn: async () => {
      const content = question.trim();
      if (!content) throw new Error("请输入问题");
      if (!selectedSpaces.length) throw new Error("请至少选择一个知识库");
      let currentSessionId = sessionId;
      if (!currentSessionId) {
        const created = await api.createKnowledgeChatSession({ title: content.slice(0, 28), scopeMode: selectedSpaces.length > 1 ? "multi" : "single", spaceIds: selectedSpaces });
        currentSessionId = created.id;
        setSessionId(created.id);
        setNewSessionDraft(false);
      } else if (!sameNumbers(activeSession.data?.spaceIds || [], selectedSpaces)) {
        await api.updateKnowledgeChatSessionScope(currentSessionId, selectedSpaces);
      }
      const history = (activeSession.data?.messages || []).filter((message) => !["QUEUED", "RUNNING", "FAILED"].includes(message.taskStatus || "")).slice(-10).map<RagChatMessage>((message) => ({ role: message.role, content: message.content }));
      await api.submitKnowledgeChatQuery(currentSessionId, { spaceIds: selectedSpaces, question: content, retrievalMode: "balanced", history });
      return currentSessionId;
    },
    onSuccess: async (id) => { setQuestion(""); await client.invalidateQueries({ queryKey: ["chat-sessions"] }); await client.invalidateQueries({ queryKey: ["chat-session", id] }); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "发送失败")
  });
  const remove = useMutation({ mutationFn: (id: number) => api.deleteKnowledgeChatSession(id), onSuccess: () => { setDeleteTarget(null); if (deleteTarget?.id === sessionId) setSessionId(null); client.invalidateQueries({ queryKey: ["chat-sessions"] }); toast.success("会话已删除"); }, onError: (error) => toast.error(error instanceof Error ? error.message : "删除会话失败") });
  const retry = useMutation({ mutationFn: (messageId: number) => api.retryKnowledgeChatQuery(sessionId!, messageId), onSuccess: () => { client.invalidateQueries({ queryKey: ["chat-session", sessionId] }); toast.success("回答已重新排队"); }, onError: (error) => toast.error(error instanceof Error ? error.message : "重试失败") });

  const messages = activeSession.data?.messages || [];
  return <div className="assistant-page"><aside className="assistant-sessions"><Button variant="confirm" className="full-width" onClick={() => { setNewSessionDraft(true); setSessionId(null); setQuestion(""); setSelectedSpaces([]); }}><MessageSquarePlus size={16} />新建会话</Button><label className="search-box search-box--small"><Search size={15} /><input value={sessionSearch} onChange={(event) => setSessionSearch(event.target.value)} placeholder="搜索会话" /></label><div className="session-list">{sessions.isLoading ? <LoadingState label="加载会话" /> : (sessions.data || []).map((session) => <div className={session.id === sessionId ? "session-item session-item--active" : "session-item"} key={session.id}><button onClick={() => { setNewSessionDraft(false); setSessionId(session.id); }}><strong>{session.title || "新会话"}</strong><small>{session.messageCount || 0} 条消息</small></button><button aria-label={`删除会话 ${session.title}`} onClick={() => setDeleteTarget(session)}><Trash2 size={14} /></button></div>)}</div></aside><section className="assistant-workspace"><div className="assistant-scope"><div><span className="section-eyebrow">知识范围</span><strong>{selectedSpaces.length ? `已选择 ${selectedSpaces.length} 个知识库` : "选择回答所依据的知识库"}</strong></div><SpacePicker spaces={spaces.data || []} selected={selectedSpaces} onChange={setSelectedSpaces} /></div><div className="message-thread">{activeSession.isLoading ? <LoadingState label="正在加载会话" /> : activeSession.isError ? <ErrorState message={activeSession.error instanceof Error ? activeSession.error.message : "无法加载会话"} onRetry={() => activeSession.refetch()} /> : messages.length === 0 ? <div className="assistant-empty"><span><Sparkles size={28} /></span><h2>{newSessionDraft ? "开始一个新会话" : "从知识库中获得可信答案"}</h2><p>选择一个或多个知识库，然后询问文档内容、事实或流程。回答会附带可追溯引用。</p><div className="prompt-suggestions"><button onClick={() => setQuestion("概括这些知识库中最重要的主题")}>概括主要主题</button><button onClick={() => setQuestion("有哪些需要我关注的风险和待办？")}>查找风险与待办</button><button onClick={() => setQuestion("对比不同文档中的关键结论")}>对比关键结论</button></div></div> : messages.map((message) => <Message key={message.id} message={message} retrying={retry.isPending} onRetry={() => retry.mutate(message.id)} />)}{send.isPending && <div className="message-row message-row--assistant"><span className="message-avatar"><Bot size={17} /></span><div className="message-bubble message-bubble--thinking"><i /><i /><i /><span>正在安全提交问题…</span></div></div>}<div ref={messageEnd} /></div><form className="assistant-composer" onSubmit={(event) => { event.preventDefault(); send.mutate(); }}><textarea value={question} onChange={(event) => setQuestion(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); send.mutate(); } }} placeholder="询问知识库中的内容…" rows={2} disabled={send.isPending} /><Button variant="confirm" size="icon" type="submit" aria-label="发送问题" loading={send.isPending} disabled={!question.trim() || !selectedSpaces.length}><Send size={17} /></Button><small>问题和处理状态由后端持久化；刷新页面不会丢失正在生成的回答。</small></form></section><Dialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)} title="删除会话？" description="这将永久删除会话及其消息记录。" footer={<><Button onClick={() => setDeleteTarget(null)}>取消</Button><Button variant="danger" loading={remove.isPending} onClick={() => deleteTarget && remove.mutate(deleteTarget.id)}><Trash2 size={16} />确认删除</Button></>}><div className="danger-callout">将删除：<strong>{deleteTarget?.title}</strong></div></Dialog></div>;
}

function SpacePicker({ spaces, selected, onChange }: { spaces: Array<{ id: number; name: string }>; selected: number[]; onChange: (spaces: number[]) => void }) {
  const [open, setOpen] = useState(false);
  return <div className="space-picker"><Button onClick={() => setOpen((value) => !value)} aria-expanded={open}>{open ? <ChevronLeft size={16} /> : <Plus size={16} />}{open ? "收起选择" : "选择知识库"}</Button>{open && <div className="space-picker__panel">{spaces.length ? spaces.map((space) => { const checked = selected.includes(space.id); return <label key={space.id}><input type="checkbox" checked={checked} onChange={() => onChange(checked ? selected.filter((id) => id !== space.id) : [...selected, space.id])} /><span>{checked && <Check size={14} />}</span>{space.name}</label>; }) : <small>暂无可用空间</small>}</div>}</div>;
}

function Message({ message, onRetry, retrying }: { message: KnowledgeChatMessage; onRetry: () => void; retrying: boolean }) {
  const citations = useMemo(() => parseCitations(message.citationsJson), [message.citationsJson]);
  const assistant = message.role === "assistant";
  const pending = ["QUEUED", "RUNNING"].includes(message.taskStatus || "");
  const failed = message.taskStatus === "FAILED";
  return <article className={assistant ? "message-row message-row--assistant" : "message-row message-row--user"}><span className="message-avatar">{assistant ? <Bot size={17} /> : <User size={17} />}</span><div>{pending ? <div className="message-bubble message-bubble--thinking" role="status"><i /><i /><i /><span>{message.taskStatus === "QUEUED" ? "已排队，等待生成回答" : "正在检索知识库并生成回答"}</span></div> : <div className="message-bubble">{message.content}</div>}{failed && <div className="assistant-error" role="alert"><AlertCircle size={17} /><div><strong>回答生成失败</strong><p>{message.errorMessage || "服务暂时不可用，请稍后重试。"}</p></div><Button size="sm" loading={retrying} onClick={onRetry}><RotateCw size={15} />重试</Button></div>}{assistant && !failed && citations.length > 0 && <div className="citation-list"><span>引用来源</span>{citations.map((citation, index) => <article key={`${citation.documentId}-${citation.chunkId}-${index}`}><FileText size={15} /><div><strong>{citation.fileName || `来源 ${index + 1}`}</strong><p>{citation.contentSummary || "相关文档片段"}</p></div>{citation.rerankScore != null && <small>{Math.round(citation.rerankScore * 100)}%</small>}</article>)}</div>}</div></article>;
}
function parseCitations(raw?: string): RagCitation[] { if (!raw) return []; try { const parsed = JSON.parse(raw); return Array.isArray(parsed) ? parsed : []; } catch { return []; } }
function sameNumbers(left: number[], right: number[]) { return left.length === right.length && [...left].sort((a, b) => a - b).every((value, index) => value === [...right].sort((a, b) => a - b)[index]); }
