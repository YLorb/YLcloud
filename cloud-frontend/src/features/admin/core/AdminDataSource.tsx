import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode
} from "react";

export type AdminResource =
  | "dashboard"
  | "settings"
  | "file-system"
  | "storage"
  | "nodes"
  | "groups"
  | "users"
  | "files"
  | "shares"
  | "tasks"
  | "orders"
  | "events"
  | "reports"
  | "oauth";

export type AdminRecordId = string | number;
export type AdminRecord = { id: AdminRecordId };

export type AdminPageResult<T> = {
  items: T[];
  total: number;
  mode: "mock";
};

export interface AdminDataSource {
  readonly mode: "mock";
  list<T extends AdminRecord>(resource: AdminResource): Promise<AdminPageResult<T>>;
  create<T extends AdminRecord>(resource: AdminResource, item: Omit<T, "id"> & Partial<Pick<T, "id">>): Promise<T>;
  update<T extends AdminRecord>(resource: AdminResource, id: AdminRecordId, patch: Partial<T>): Promise<T>;
  remove(resource: AdminResource, id: AdminRecordId): Promise<void>;
}

type AdminSeed = Partial<Record<AdminResource, AdminRecord[]>>;

export class MockAdminDataSource implements AdminDataSource {
  readonly mode = "mock" as const;
  private readonly records = new Map<AdminResource, AdminRecord[]>();
  private sequence = 1;

  constructor(seed: AdminSeed = {}) {
    Object.entries(seed).forEach(([resource, items]) => {
      this.records.set(resource as AdminResource, (items || []).map((item) => ({ ...item })));
    });
  }

  async list<T extends AdminRecord>(resource: AdminResource): Promise<AdminPageResult<T>> {
    const items = (this.records.get(resource) || []).map((item) => ({ ...item })) as T[];
    return { items, total: items.length, mode: this.mode };
  }

  async create<T extends AdminRecord>(resource: AdminResource, item: Omit<T, "id"> & Partial<Pick<T, "id">>): Promise<T> {
    const created = { ...item, id: item.id ?? `${resource}-${this.sequence++}` } as T;
    this.records.set(resource, [...(this.records.get(resource) || []), created]);
    return { ...created };
  }

  async update<T extends AdminRecord>(resource: AdminResource, id: AdminRecordId, patch: Partial<T>): Promise<T> {
    let updated: T | undefined;
    const next = (this.records.get(resource) || []).map((item) => {
      if (item.id !== id) return item;
      updated = { ...item, ...patch, id } as T;
      return updated;
    });
    if (!updated) throw new Error("演示记录不存在或已被移除");
    this.records.set(resource, next);
    return { ...updated };
  }

  async remove(resource: AdminResource, id: AdminRecordId): Promise<void> {
    const current = this.records.get(resource) || [];
    this.records.set(resource, current.filter((item) => item.id !== id));
  }
}

const AdminDataSourceContext = createContext<AdminDataSource | null>(null);

export function AdminDataSourceProvider({ children, dataSource }: { children: ReactNode; dataSource?: AdminDataSource }) {
  const fallback = useRef<AdminDataSource>();
  if (!fallback.current) fallback.current = new MockAdminDataSource();
  return (
    <AdminDataSourceContext.Provider value={dataSource || fallback.current}>
      {children}
    </AdminDataSourceContext.Provider>
  );
}

export function useAdminDataSource(): AdminDataSource {
  const value = useContext(AdminDataSourceContext);
  if (!value) throw new Error("AdminDataSourceProvider is required for admin pages");
  return value;
}

export function useAdminCollection<T extends AdminRecord>(resource: AdminResource) {
  const source = useAdminDataSource();
  const [items, setItems] = useState<T[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await source.list<T>(resource);
      setItems(result.items);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "演示数据加载失败");
    } finally {
      setLoading(false);
    }
  }, [resource, source]);

  useEffect(() => { void refresh(); }, [refresh]);

  const commands = useMemo(() => ({
    create: async (item: Omit<T, "id"> & Partial<Pick<T, "id">>) => {
      const created = await source.create<T>(resource, item);
      setItems((current) => [...current, created]);
      return created;
    },
    update: async (id: AdminRecordId, patch: Partial<T>) => {
      const updated = await source.update<T>(resource, id, patch);
      setItems((current) => current.map((item) => item.id === id ? updated : item));
      return updated;
    },
    remove: async (id: AdminRecordId) => {
      await source.remove(resource, id);
      setItems((current) => current.filter((item) => item.id !== id));
    }
  }), [resource, source]);

  return { items, loading, error, refresh, ...commands, mode: source.mode };
}

