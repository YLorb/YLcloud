/// <reference lib="webworker" />

import { createMD5, createSHA1, createSHA256 } from "hash-wasm";

type HashRequest = { file: File; sliceSize: number };

self.onmessage = async (event: MessageEvent<HashRequest>) => {
  const { file, sliceSize } = event.data;
  try {
    const [md5, sha1, sha256] = await Promise.all([createMD5(), createSHA1(), createSHA256()]);
    md5.init();
    sha1.init();
    sha256.init();

    for (let offset = 0; offset < file.size; offset += sliceSize) {
      const bytes = new Uint8Array(await file.slice(offset, Math.min(file.size, offset + sliceSize)).arrayBuffer());
      md5.update(bytes);
      sha1.update(bytes);
      sha256.update(bytes);
      self.postMessage({ type: "progress", loaded: Math.min(file.size, offset + bytes.byteLength), total: file.size });
    }

    self.postMessage({
      type: "result",
      md5: md5.digest(),
      sha1: sha1.digest(),
      sha256: sha256.digest()
    });
  } catch (error) {
    self.postMessage({ type: "error", message: error instanceof Error ? error.message : "文件指纹计算失败" });
  }
};

export {};
