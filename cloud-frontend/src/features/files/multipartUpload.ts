import { md5 } from "hash-wasm";
import { api } from "../../api";
import type { FileItem } from "../../types";

const HASH_SLICE_SIZE = 4 * 1024 * 1024;
const DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;
const RESUME_PREFIX = "ylcloud_multipart:";

type FileHashes = { md5: string; sha1: string; sha256: string };

export type UploadProgress = {
  fileName: string;
  stage: "hashing" | "uploading" | "merging";
  percent: number;
  uploadedChunks?: number;
  totalChunks?: number;
};

export type UploadHooks = {
  onProgress?: (progress: UploadProgress) => void;
  waitIfPaused?: () => Promise<void>;
};

function calculateHashes(file: File, onProgress?: (fraction: number) => void): Promise<FileHashes> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./fileHash.worker.ts", import.meta.url), { type: "module" });
    worker.onmessage = (event: MessageEvent<Record<string, unknown>>) => {
      if (event.data.type === "progress") {
        const total = Number(event.data.total) || 1;
        onProgress?.(Number(event.data.loaded) / total);
        return;
      }
      worker.terminate();
      if (event.data.type === "result") {
        resolve({
          md5: String(event.data.md5),
          sha1: String(event.data.sha1),
          sha256: String(event.data.sha256)
        });
      } else {
        reject(new Error(String(event.data.message || "文件指纹计算失败")));
      }
    };
    worker.onerror = () => {
      worker.terminate();
      reject(new Error("文件指纹计算失败"));
    };
    worker.postMessage({ file, sliceSize: HASH_SLICE_SIZE });
  });
}

function resumeKey(file: File, parentId: number, hash: string) {
  return `${RESUME_PREFIX}${parentId}:${file.name}:${file.size}:${file.lastModified}:${hash}`;
}

async function retryRequest<T>(action: () => Promise<T>) {
  let lastError: unknown;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      return await action();
    } catch (error) {
      lastError = error;
      if (attempt < 2) await new Promise((resolve) => window.setTimeout(resolve, 500 * (attempt + 1)));
    }
  }
  throw lastError;
}

export async function uploadFileWithResume(
  file: File,
  parentId: number,
  thresholdBytes: number,
  hooks: UploadHooks = {}
): Promise<FileItem> {
  if (file.size < thresholdBytes || file.size === 0) {
    hooks.onProgress?.({ fileName: file.name, stage: "uploading", percent: 5 });
    const idempotencyKey = crypto.randomUUID();
    const result = await retryRequest(() => api.uploadFile(file, parentId, idempotencyKey));
    hooks.onProgress?.({ fileName: file.name, stage: "uploading", percent: 100 });
    return result;
  }

  const hashes = await calculateHashes(file, (fraction) => {
    hooks.onProgress?.({ fileName: file.name, stage: "hashing", percent: Math.round(fraction * 12) });
  });
  const key = resumeKey(file, parentId, hashes.sha256);
  const savedUploadId = localStorage.getItem(key) || crypto.randomUUID();
  const totalChunks = Math.ceil(file.size / DEFAULT_CHUNK_SIZE);
  const initialized = await api.initMultipartUpload({
    uploadId: savedUploadId,
    fileName: file.name,
    fileMd5: hashes.md5,
    fileSha1: hashes.sha1,
    fileHash: hashes.sha256,
    fileSize: file.size,
    chunkSize: DEFAULT_CHUNK_SIZE,
    totalChunks,
    parentId
  });

  if (initialized.instantUpload && initialized.file) {
    localStorage.removeItem(key);
    hooks.onProgress?.({ fileName: file.name, stage: "uploading", percent: 100 });
    return initialized.file;
  }
  if (!initialized.uploadId) throw new Error("服务器没有返回分片上传任务 ID");

  const uploadId = initialized.uploadId;
  localStorage.setItem(key, uploadId);
  const chunkSize = initialized.chunkSize || DEFAULT_CHUNK_SIZE;
  const confirmedTotal = initialized.totalChunks || Math.ceil(file.size / chunkSize);
  const status = await api.multipartStatus(uploadId);
  const uploaded = new Set(status.uploadedChunks || initialized.uploadedChunks || []);

  for (let index = 0; index < confirmedTotal; index += 1) {
    if (uploaded.has(index)) continue;
    await hooks.waitIfPaused?.();
    const chunk = file.slice(index * chunkSize, Math.min(file.size, (index + 1) * chunkSize));
    const chunkMd5 = await md5(new Uint8Array(await chunk.arrayBuffer()));
    await retryRequest(() => api.uploadChunk({ file: chunk, uploadId, chunkIndex: index, chunkMd5 }));
    uploaded.add(index);
    hooks.onProgress?.({
      fileName: file.name,
      stage: "uploading",
      percent: 12 + Math.round((uploaded.size / confirmedTotal) * 83),
      uploadedChunks: uploaded.size,
      totalChunks: confirmedTotal
    });
  }

  hooks.onProgress?.({ fileName: file.name, stage: "merging", percent: 96, uploadedChunks: confirmedTotal, totalChunks: confirmedTotal });
  const result = await api.mergeMultipartUpload({
    uploadId,
    fileName: file.name,
    partNames: Array.from({ length: confirmedTotal }, (_, index) => String(index))
  });
  localStorage.removeItem(key);
  hooks.onProgress?.({ fileName: file.name, stage: "merging", percent: 100, uploadedChunks: confirmedTotal, totalChunks: confirmedTotal });
  return result;
}
