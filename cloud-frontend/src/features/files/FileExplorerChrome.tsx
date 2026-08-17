import { ChevronRight, Search, X } from "lucide-react";

export type ExplorerCrumb = { id: number; name: string };

export function FileExplorerBreadcrumbs({ crumbs, label, onOpen }: { crumbs: ExplorerCrumb[]; label: string; onOpen: (crumb: ExplorerCrumb) => void }) {
  return <nav className="breadcrumbs" aria-label={label}>{crumbs.map((crumb,index) => <span key={`${crumb.id}-${index}`}><button onClick={() => onOpen(crumb)}>{crumb.name}</button>{index < crumbs.length - 1 && <ChevronRight size={15} />}</span>)}</nav>;
}

export function FileExplorerSearch({ value, placeholder, onChange }: { value: string; placeholder: string; onChange: (value: string) => void }) {
  return <label className="search-box"><Search size={17} /><input value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} aria-label={placeholder} />{value && <button onClick={() => onChange("")} aria-label="清空搜索"><X size={15} /></button>}</label>;
}
