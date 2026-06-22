import { FormEvent, useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../../api";
import type { ChatMessage, Notice } from "../../appTypes";
import type { Space } from "../../types";

export function ChatView({ showNotice }: { showNotice: (notice: Notice) => void }) {
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [spaceId, setSpaceId] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: "welcome",
      role: "assistant",
      content: "选择一个知识库后即可开始提问；也可以保持不选择，用于后续接入通用对话能力。"
    }
  ]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    api.listSpaces()
      .then((items) => setSpaces(items || []))
      .catch((err) => showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库列表加载失败" }));
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const question = input.trim();
    if (!question) return;
    setMessages((current) => [...current, { id: `u-${Date.now()}`, role: "user", content: question }]);
    setInput("");

    if (!spaceId) {
      setMessages((current) => [
        ...current,
        {
          id: `a-${Date.now()}`,
          role: "assistant",
          content: "当前未选择知识库。通用空上下文对话接口暂未接入，请先选择一个知识库后提问。"
        }
      ]);
      return;
    }

    setLoading(true);
    try {
      const history = messages
        .slice(-6)
        .filter((message) => !message.id.startsWith("welcome"))
        .filter((message) => message.content.trim())
        .map((message) => ({ role: message.role, content: message.content.trim() }));
      const answer = await api.queryRag(Number(spaceId), question, undefined, history);
      const citations =
        answer.citations?.length
          ? `\n\n引用：${answer.citations.map((citation) => citation.fileName || `片段 ${citation.chunkId}`).join("、")}`
          : "";
      setMessages((current) => [
        ...current,
        { id: `a-${Date.now()}`, role: "assistant", content: `${answer.answer || "暂无回答"}${citations}` }
      ]);
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库问答失败" });
      setMessages((current) => [...current, { id: `a-${Date.now()}`, role: "assistant", content: "这次查询失败了，请稍后重试。" }]);
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="chat-page">
      <aside className="chat-sidebar">
        <button
          className="primary-button full"
          type="button"
          onClick={() =>
            setMessages([
              {
                id: `welcome-${Date.now()}`,
                role: "assistant",
                content: "新对话已创建。请选择知识库，或保持不选择等待通用问答接口接入。"
              }
            ])
          }
        >
          新建对话
        </button>
        <label>
          知识库范围
          <select value={spaceId} onChange={(event) => setSpaceId(event.target.value)}>
            <option value="">不选择</option>
            {spaces.map((space) => (
              <option key={space.id} value={space.id}>
                {space.name}
              </option>
            ))}
          </select>
        </label>
        <div className="chat-scope-card">
          <strong>{spaceId ? spaces.find((space) => String(space.id) === spaceId)?.name : "空上下文"}</strong>
          <span>{spaceId ? "将调用当前知识库 RAG 接口回答。" : "通用对话接口暂未接入。"}</span>
        </div>
      </aside>

      <div className="chat-main">
        <div className="chat-messages">
          {messages.map((message) => (
            <article className={`chat-message ${message.role}`} key={message.id}>
              <span>{message.role === "user" ? "你" : "AI"}</span>
              <p>{message.content}</p>
            </article>
          ))}
          {loading && (
            <article className="chat-message assistant">
              <span>AI</span>
              <p>
                <Loader2 className="spin inline-spinner" size={16} />
                正在检索知识库
              </p>
            </article>
          )}
        </div>
        <form className="chat-composer" onSubmit={submit}>
          <input value={input} onChange={(event) => setInput(event.target.value)} placeholder="向知识库提问" />
          <button className="primary-button" type="submit" disabled={loading || !input.trim()}>
            发送
          </button>
        </form>
      </div>
    </section>
  );
}
