import { Bot, BookOpen, ExternalLink, Loader2, UserRound } from "lucide-react";
import { useEffect, useRef } from "react";
import type { AssistantConversation } from "./assistantChat";

export function AssistantThread({ conversation, loading }: { conversation: AssistantConversation; loading: boolean }) {
  const threadRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const thread = threadRef.current;
    if (thread) thread.scrollTo({ top: thread.scrollHeight, behavior: "smooth" });
  }, [conversation.id, conversation.messages.length, loading]);

  return (
    <div className="assistant-thread" ref={threadRef} aria-live="polite">
      <div className="assistant-thread-inner">
        {conversation.messages.map((message) => (
          <article className={`assistant-message ${message.role}`} key={message.id}>
            <div className="assistant-message-avatar" aria-hidden="true">
              {message.role === "assistant" ? <Bot size={18} /> : <UserRound size={18} />}
            </div>
            <div className="assistant-message-content">
              <strong>{message.role === "assistant" ? "知识助手" : "你"}</strong>
              <p>{message.content}</p>
              {message.pendingSync && <small className="assistant-pending-sync">等待同步</small>}
              {!!message.citations?.length && (
                <details className="assistant-sources">
                  <summary><BookOpen size={15} />{message.citations.length} 个来源</summary>
                  <div>
                    {message.citations.map((citation, index) => (
                      <article key={`${citation.spaceId}-${citation.chunkId}-${index}`}>
                        <span>{index + 1}</span>
                        <div>
                          <strong>{citation.fileName || `来源 ${index + 1}`}</strong>
                          <small>{citation.spaceName || "知识库"}</small>
                          {citation.contentSummary && <p>{citation.contentSummary}</p>}
                        </div>
                        {(citation.previewUrl || citation.downloadUrl) && (
                          <a href={citation.previewUrl || citation.downloadUrl} target="_blank" rel="noreferrer" aria-label={`打开 ${citation.fileName || "来源"}`}>
                            <ExternalLink size={15} />
                          </a>
                        )}
                      </article>
                    ))}
                  </div>
                </details>
              )}
            </div>
          </article>
        ))}
        {loading && (
          <article className="assistant-message assistant">
            <div className="assistant-message-avatar"><Bot size={18} /></div>
            <div className="assistant-message-content">
              <strong>知识助手</strong>
              <p className="assistant-thinking"><Loader2 className="spin" size={16} />正在检索并整理资料</p>
            </div>
          </article>
        )}
      </div>
    </div>
  );
}
