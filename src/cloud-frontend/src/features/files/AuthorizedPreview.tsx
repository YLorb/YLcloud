import { useEffect, useState } from "react";
import { getToken } from "../../api";

export function AuthorizedPreview({ url, title }: { url: string; title: string }) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let objectUrl: string | null = null;
    setBlobUrl(null);
    setError(null);
    const token = getToken();
    fetch(url, { signal: controller.signal, headers: token ? { Authorization: token.startsWith("Bearer ") ? token : `Bearer ${token}` } : {} })
      .then(async (response) => {
        if ((response.headers.get("content-type") || "").includes("application/json")) {
          const result = await response.json() as { message?: string };
          throw new Error(result.message || "预览转换失败");
        }
        if (!response.ok) throw new Error(`预览加载失败（${response.status}）`);
        if (!(response.headers.get("content-type") || "").includes("application/pdf")) throw new Error("预览转换失败");
        return response.blob();
      })
      .then((blob) => {
        if (controller.signal.aborted) return;
        objectUrl = URL.createObjectURL(blob);
        setBlobUrl(objectUrl);
      })
      .catch((cause) => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "预览加载失败"); });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [url, attempt]);

  if (error) return <div className="file-preview-panel__state" role="alert"><span>{error}</span><button onClick={() => setAttempt((value) => value + 1)}>重试</button></div>;
  if (!blobUrl) return <div className="file-preview-panel__state" role="status">正在转换 Office 文档…</div>;
  return <div className="office-preview"><div className="office-preview__toolbar"><a href={blobUrl} target="_blank" rel="noopener noreferrer">在新窗口查看</a></div><iframe className="file-preview-panel__frame" src={blobUrl} title={title} /></div>;
}
