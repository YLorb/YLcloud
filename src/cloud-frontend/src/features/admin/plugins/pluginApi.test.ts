import { afterEach, expect, it, vi } from "vitest";
import { request } from "../../../api";
import { pluginApi } from "./pluginApi";
afterEach(() => vi.unstubAllGlobals());
function respond(code: number, data: unknown, status = code) {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code, message: "result", data }), { status, headers: { "content-type": "application/json" } }));
  vi.stubGlobal("fetch", fetchMock); return fetchMock;
}
it("登记接受 201，原始 JSON 不改写，并携带现有 CSRF 与会话配置", async () => {
  const fetchMock = respond(201, { id: "example" });
  const raw = '{"id":"a","id":"b"}';
  expect(await pluginApi.install(raw)).toEqual({ id: "example" });
  const init = fetchMock.mock.calls[0][1];
  expect(init.body).toBe(raw); expect(init.credentials).toBe("same-origin");
  expect(init.headers.get("X-YLCloud-Request")).toBe("1");
});
it("禁用接受 202 并保留 stopped=false", async () => {
  const fetchMock = respond(202, { stopped: false, plugin: { runtimeState: "STOPPING" } });
  expect((await pluginApi.disable("example")).stopped).toBe(false);
  expect(fetchMock.mock.calls[0][0]).toBe("/api/admin/plugins/example/disable?waitMillis=1000");
});
it("非插件请求仍使用原 200 契约，HTTP 错误不能因 body 成功而通过", async () => {
  respond(201, null); await expect(request("/api/other")).rejects.toThrow();
  respond(200, null, 503); await expect(pluginApi.list()).rejects.toThrow();
});
it("401 仍触发统一会话过期事件", async () => {
  const expire = vi.fn(); window.addEventListener("ylcloud-session-expired", expire);
  try { respond(401, null); await expect(pluginApi.list()).rejects.toThrow(); expect(expire).toHaveBeenCalledTimes(1); }
  finally { window.removeEventListener("ylcloud-session-expired", expire); }
});
