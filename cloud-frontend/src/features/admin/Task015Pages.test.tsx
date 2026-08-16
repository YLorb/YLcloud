import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AccountSettingsPage } from "../account/AccountSettingsPage";
import { BackupStatusPage } from "./BackupStatusPage";
import { SecurityAuditPage } from "./SecurityAuditPage";
import { SystemOperationsPage } from "./SystemOperationsPage";
import { api } from "../../api";

vi.mock("../../api", () => ({
  api: {
    accountStatus: vi.fn().mockResolvedValue({
      userId: 3, username: "alice", accountStatus: "ACTIVE",
      canRecover: false, isTeamOwner: false, ownedTeamCount: 0
    }),
    listDataExports: vi.fn().mockResolvedValue([{
      id: 17, status: "COMPLETED", exportScope: "FULL",
      downloadUrl: "/api/account/export/17/download",
      downloadExpiresAt: "2026-07-29T08:00:00", createdAt: "2026-07-28T08:00:00"
    }]),
    getDataExport: vi.fn().mockResolvedValue({
      id: 17, status: "COMPLETED", downloadUrl: "/api/account/export/17/download",
      decryptionKey: "base64-secret-key", downloadExpiresAt: "2026-07-29T08:00:00"
    }),
    cancelAccount: vi.fn(),
    requestDataExport: vi.fn(),
    queryAuditEvents: vi.fn().mockResolvedValue([{
      eventId: 41, eventType: "API_KEY", action: "REVOKE", result: "SUCCESS",
      subjectType: "USER", subjectId: 3, subjectName: "alice",
      targetType: "API_KEY", targetId: "9", retentionPolicy: "PERMANENT",
      occurredAt: "2026-07-28T08:00:00"
    }]),
    auditStats: vi.fn().mockResolvedValue({ recentEvents: 12, permanentEvents: 4 }),
    listRetentionConfigs: vi.fn().mockResolvedValue([{
      configKey: "SECURITY_CRITICAL", retentionDays: 365,
      permanent: true, description: "关键安全事件"
    }]),
    updateRetentionConfig: vi.fn(),
    backupStats: vi.fn().mockResolvedValue({
      readyBackups: 1,
      latestBackup: { exists: true, id: 7, sizeBytes: 2048, publishedAt: "2026-07-28T08:00:00" }
    }),
    listReadyBackups: vi.fn().mockResolvedValue([{
      id: 7, runKey: "backup-20260728", backupType: "FULL", status: "READY",
      archivePath: "/backup/7.tar.gz.enc", archiveSizeBytes: 2048,
      archiveHash: "abc123", finishedAt: "2026-07-28T07:50:00"
    }]),
    listRecentBackups: vi.fn().mockResolvedValue([]),
    getRestoreVerification: vi.fn().mockResolvedValue({
      exists: true, status: "SUCCESS", restoreEnvironment: "ISOLATED"
    }),
    maintenanceStatus: vi.fn().mockResolvedValue({
      active: true, reason: "版本升级", startedAt: "2026-07-28T08:00:00"
    }),
    enableMaintenance: vi.fn(),
    disableMaintenance: vi.fn()
  }
}));

function renderPage(page: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>{page}</QueryClientProvider>
    </MemoryRouter>,
  );
}

afterEach(() => cleanup());

describe("TASK-015 management pages use backend contracts", () => {
  it("loads the one-time export decryption credential before downloading", async () => {
    renderPage(<AccountSettingsPage />);

    expect(await screen.findByText("#17")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /获取下载凭据/ }));

    expect(await screen.findByDisplayValue("base64-secret-key")).toBeInTheDocument();
    expect(api.getDataExport).toHaveBeenCalledWith(17);
  });

  it("renders the audit list returned directly by the API", async () => {
    renderPage(<SecurityAuditPage />);

    expect(await screen.findByText("REVOKE")).toBeInTheDocument();
    expect(screen.getByText("alice")).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
    expect(screen.getAllByText("永久保留").length).toBeGreaterThan(0);
  });

  it("shows archive and isolated restore verification fields", async () => {
    renderPage(<BackupStatusPage />);

    expect(await screen.findByText("#7")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "详情" }));

    expect(await screen.findByText("/backup/7.tar.gz.enc")).toBeInTheDocument();
    expect(await screen.findByText("ISOLATED")).toBeInTheDocument();
    expect(api.getRestoreVerification).toHaveBeenCalledWith(7);
  });

  it("uses active and startedAt for maintenance state", async () => {
    renderPage(<SystemOperationsPage />);

    expect(await screen.findByText("系统正在维护中")).toBeInTheDocument();
    expect(screen.getAllByText("版本升级").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/启用时间/).length).toBeGreaterThan(0);
  });
});
