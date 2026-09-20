import { afterEach, describe, expect, it, vi } from "vitest";
import { api, request } from "./api";
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });
const response = (status=200) => new Response(JSON.stringify({code:status,message:"result",data:{id:1,username:"alice"}}), {
  status,headers:{"Content-Type":"application/json"}
});
describe("Cookie Session API", () => {
  it("uses cookies and CSRF header, never a legacy bearer token", async () => {
    localStorage.setItem("ylcloud_token","legacy");
    const fetch = vi.fn().mockResolvedValue(response());vi.stubGlobal("fetch",fetch);
    await api.login("alice","password",true);
    const init=fetch.mock.calls[0][1];
    expect(init.credentials).toBe("same-origin");
    expect(init.headers.get("Authorization")).toBeNull();
    expect(init.headers.get("X-YLCloud-Request")).toBe("1");
    expect(JSON.parse(init.body).rememberMe).toBe(true);
  });
  it.each([401,403,503])("only invalidates identity for 401, status=%s", async (status) => {
    vi.stubGlobal("fetch",vi.fn().mockResolvedValue(response(status)));
    const expired=vi.fn();window.addEventListener("ylcloud-session-expired",expired);
    try {
      await expect(request("/api/session")).rejects.toMatchObject({status});
      expect(expired).toHaveBeenCalledTimes(status===401?1:0);
    } finally {window.removeEventListener("ylcloud-session-expired",expired);}
  });
  it("incorrect login does not invalidate another existing session", async () => {
    vi.stubGlobal("fetch",vi.fn().mockResolvedValue(response(401)));
    const expired=vi.fn();window.addEventListener("ylcloud-session-expired",expired);
    try {await expect(api.login("alice","wrong")).rejects.toThrow();expect(expired).not.toHaveBeenCalled();}
    finally {window.removeEventListener("ylcloud-session-expired",expired);}
  });
});
