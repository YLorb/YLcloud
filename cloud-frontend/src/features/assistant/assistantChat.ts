import type { KnowledgeChatSession, RagCitation } from "../../types";

export type AssistantMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  citations?: RagCitation[];
  pendingSync?: boolean;
};

export type AssistantConversation = {
  id: number | string;
  title: string;
  messages: AssistantMessage[];
  spaceIds: number[];
  updatedAt: number;
  loaded?: boolean;
};

export type ConversationGroup = {
  label: string;
  items: AssistantConversation[];
};

const CHAT_STORAGE_KEY = "ylcloud_knowledge_chats";

function parseCitations(raw?: string): RagCitation[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as RagCitation[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function conversationFromSession(session: KnowledgeChatSession): AssistantConversation {
  return {
    id: session.id,
    title: session.title,
    spaceIds: session.spaceIds || [],
    messages: (session.messages || [])
      .filter((message) => message.role === "user" || message.role === "assistant")
      .map((message) => ({
        id: String(message.id),
        role: message.role as "user" | "assistant",
        content: message.content,
        citations: parseCitations(message.citationsJson)
      })),
    updatedAt: session.updatetime ? new Date(session.updatetime).getTime() : Date.now(),
    loaded: Array.isArray(session.messages)
  };
}

export function loadLocalConversations(): AssistantConversation[] {
  const raw = localStorage.getItem(CHAT_STORAGE_KEY);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as Array<AssistantConversation & { spaceId?: number }>;
    if (!Array.isArray(parsed)) return [];
    return parsed.map((item) => ({
      ...item,
      spaceIds: item.spaceIds?.length ? item.spaceIds : item.spaceId ? [item.spaceId] : [],
      loaded: true
    }));
  } catch {
    return [];
  }
}

export function saveLocalConversations(items: AssistantConversation[]) {
  const loaded = items.filter((item) => item.loaded);
  if (items.length && !loaded.length) return;
  localStorage.setItem(CHAT_STORAGE_KEY, JSON.stringify(loaded.slice(0, 50)));
}

export function mergeRemoteConversations(
  sessions: KnowledgeChatSession[],
  local: AssistantConversation[]
): AssistantConversation[] {
  const localById = new Map(local.map((item) => [String(item.id), item]));
  const remote = sessions.map((session) => {
    const summary = conversationFromSession(session);
    const cached = localById.get(String(session.id));
    localById.delete(String(session.id));
    return cached?.messages.some((message) => message.pendingSync)
      ? { ...cached, title: summary.title, spaceIds: summary.spaceIds }
      : summary;
  });
  return [...remote, ...localById.values()].sort((a, b) => b.updatedAt - a.updatedAt);
}

export function groupConversations(items: AssistantConversation[]): ConversationGroup[] {
  const now = new Date();
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startYesterday = startToday - 24 * 60 * 60 * 1000;
  const startWeek = startToday - 7 * 24 * 60 * 60 * 1000;
  const groups: ConversationGroup[] = [
    { label: "今天", items: [] },
    { label: "昨天", items: [] },
    { label: "最近 7 天", items: [] },
    { label: "更早", items: [] }
  ];

  for (const conversation of items) {
    if (conversation.updatedAt >= startToday) groups[0].items.push(conversation);
    else if (conversation.updatedAt >= startYesterday) groups[1].items.push(conversation);
    else if (conversation.updatedAt >= startWeek) groups[2].items.push(conversation);
    else groups[3].items.push(conversation);
  }
  return groups.filter((group) => group.items.length);
}
