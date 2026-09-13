import type { AdminDataSource, AdminPageResult, AdminRecord, AdminRecordId, AdminResource } from "./AdminDataSource";

/** Live pages use typed API commands. Unadapted collections must never fall back to mock writes. */
export class LiveAdminDataSource implements AdminDataSource {
  readonly mode = "live" as const;
  async list<T extends AdminRecord>(_resource: AdminResource): Promise<AdminPageResult<T>> {
    return { items: [], total: 0, mode: this.mode };
  }
  async create<T extends AdminRecord>(_resource: AdminResource, _item: Omit<T, "id"> & Partial<Pick<T, "id">>): Promise<T> {
    throw new Error("此功能尚未接入后端，未保存任何数据");
  }
  async update<T extends AdminRecord>(_resource: AdminResource, _id: AdminRecordId, _patch: Partial<T>): Promise<T> {
    throw new Error("此功能尚未接入后端，未保存任何数据");
  }
  async remove(_resource: AdminResource, _id: AdminRecordId): Promise<void> {
    throw new Error("此功能尚未接入后端，未删除任何数据");
  }
}
