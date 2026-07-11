import { BookOpen, Check, ChevronDown, Loader2, Send, X } from "lucide-react";
import { FormEvent, KeyboardEvent, useEffect, useRef } from "react";
import type { Space } from "../../types";

export function AssistantComposer({
  input,
  loading,
  pickerOpen,
  scopeSaving,
  selectedSpaceIds,
  spaces,
  onInput,
  onSubmit,
  onTogglePicker,
  onToggleSpace
}: {
  input: string;
  loading: boolean;
  pickerOpen: boolean;
  scopeSaving: boolean;
  selectedSpaceIds: number[];
  spaces: Space[];
  onInput: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onTogglePicker: () => void;
  onToggleSpace: (id: number) => void;
}) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const selectedSpaces = spaces.filter((space) => selectedSpaceIds.includes(space.id));

  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 176)}px`;
  }, [input]);

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      event.currentTarget.form?.requestSubmit();
    }
  }

  return (
    <form className="assistant-composer" onSubmit={onSubmit}>
      <textarea
        ref={textareaRef}
        rows={1}
        value={input}
        placeholder="向知识库提问"
        aria-label="向知识库提问"
        onChange={(event) => onInput(event.target.value)}
        onKeyDown={handleKeyDown}
      />
      <div className="assistant-composer-footer">
        <div className="assistant-source-control">
          <button
            className="assistant-source-trigger"
            type="button"
            aria-expanded={pickerOpen}
            onClick={onTogglePicker}
          >
            <BookOpen size={16} />
            <span>{selectedSpaces.length === 1 ? selectedSpaces[0].name : `${selectedSpaces.length} 个知识库`}</span>
            <ChevronDown size={14} />
          </button>
          {pickerOpen && (
            <div className="assistant-source-popover">
              <header>
                <div>
                  <strong>知识来源</strong>
                  <small>最多选择 5 个知识库</small>
                </div>
                <button type="button" aria-label="关闭知识库选择" onClick={onTogglePicker}><X size={16} /></button>
              </header>
              <div>
                {spaces.map((space) => {
                  const checked = selectedSpaceIds.includes(space.id);
                  return (
                    <button
                      className={checked ? "selected" : ""}
                      type="button"
                      key={space.id}
                      disabled={scopeSaving || (!checked && selectedSpaceIds.length >= 5)}
                      onClick={() => onToggleSpace(space.id)}
                    >
                      <span><BookOpen size={16} />{space.name}</span>
                      {checked && <Check size={16} />}
                    </button>
                  );
                })}
                {!spaces.length && <p>暂无可访问的知识库</p>}
              </div>
            </div>
          )}
        </div>

        <div className="assistant-source-tags" aria-label="已选择的知识库">
          {selectedSpaces.slice(0, 2).map((space) => <span key={space.id}>{space.name}</span>)}
          {selectedSpaces.length > 2 && <span>+{selectedSpaces.length - 2}</span>}
        </div>

        <button
          className="assistant-send"
          type="submit"
          disabled={loading || scopeSaving || !input.trim() || !selectedSpaceIds.length}
          aria-label="发送问题"
        >
          {loading ? <Loader2 className="spin" size={18} /> : <Send size={18} />}
        </button>
      </div>
      <p className="assistant-composer-hint">Enter 发送，Shift + Enter 换行</p>
    </form>
  );
}
