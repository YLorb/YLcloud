import { createMD5, createSHA1 } from "hash-wasm";

type HashRequest = {
  chunkSize: number;
  file: File;
};

self.onmessage = async (event: MessageEvent<HashRequest>) => {
  const { file, chunkSize } = event.data;
  const md5 = await createMD5();
  const sha1 = await createSHA1();
  let offset = 0;

  while (offset < file.size) {
    const chunk = file.slice(offset, Math.min(file.size, offset + chunkSize));
    const buffer = await chunk.arrayBuffer();
    md5.update(new Uint8Array(buffer));
    sha1.update(new Uint8Array(buffer));
    offset += chunkSize;

    self.postMessage({
      type: "progress",
      loaded: Math.min(offset, file.size),
      total: file.size
    });
  }

  self.postMessage({
    type: "done",
    md5: md5.digest(),
    sha1: sha1.digest()
  });
};
