import { useMutation } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";
import { errorText, pluginApi } from "./pluginApi";
const LIMIT = 64 * 1024;
export function ManifestDialog({ onClose, onInstalled, onRefresh }: { onClose: () => void; onInstalled: () => Promise<void>; onRefresh: () => Promise<void> }) {
  const [raw, setRaw] = useState("");
  const [fileError, setFileError] = useState("");
  const [reading, setReading] = useState(false);
  const readSequence = useRef(0);
  const submitGuard = useRef(false);
  const mutation = useMutation({ mutationFn: (raw: string) => pluginApi.install(raw), retry: false,
    onError: async () => { await onRefresh(); },
    onSuccess: async () => { await onInstalled(); onClose(); }, onSettled: () => { submitGuard.current = false; } });
  const size = new Blob([raw]).size;
  function submit() {
    if (submitGuard.current || reading || !!fileError || !raw.trim() || size > LIMIT) return;
    submitGuard.current = true;
    mutation.mutate(raw);
  }
  return <Dialog open onOpenChange={open => { if (!open && !mutation.isPending && !reading) onClose(); }} title="登记插件说明书"
    description="提交 Manifest JSON。登记不会下载插件或启动引擎，服务重启后登记会丢失。"
    footer={<><Button disabled={mutation.isPending || reading} onClick={onClose}>取消</Button><Button variant="confirm" loading={mutation.isPending} disabled={reading || !!fileError || !raw.trim() || size > LIMIT} onClick={submit}>确认登记</Button></>}>
    <div className="plugin-manifest-form">
      <label>选择 JSON 文件<input type="file" accept=".json,application/json" disabled={mutation.isPending || reading} onChange={async event => {
        const file = event.target.files?.[0]; if (!file) return;
        const sequence = ++readSequence.current; setFileError("");
        if (file.size > LIMIT) { setFileError("文件超过 64 KiB，请选择较小的说明书。"); return; }
        setReading(true);
        try { const text = await file.text(); if (sequence === readSequence.current) { setRaw(text); mutation.reset(); } }
        catch { setFileError("无法读取文件，请重新选择或粘贴原文。"); }
        finally { if (sequence === readSequence.current) setReading(false); }
      }} /></label>
      <label>Manifest JSON<textarea rows={13} value={raw} spellCheck={false} disabled={mutation.isPending || reading}
        onChange={event => { setRaw(event.target.value); setFileError(""); mutation.reset(); }} placeholder="粘贴插件作者提供的 JSON 说明书" /></label>
      <p className="plugin-muted">{size.toLocaleString()} / 65,536 字节。服务器会校验字段及版本。</p>
      {reading && <p role="status">正在读取文件…</p>}
      {size > LIMIT && <p role="alert">说明书超过 64 KiB，无法提交。</p>}
      {fileError && <p role="alert">{fileError}</p>}
      {mutation.isError && <p role="alert">{errorText(mutation.error)}</p>}
    </div>
  </Dialog>;
}
