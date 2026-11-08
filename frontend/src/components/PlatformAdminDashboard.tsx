import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../services/api";
import "./PlatformAdminDashboard.css";

interface AdminAgency {
  id: string; businessName: string; websiteSlug?: string; status: string; subscriptionPlan: string; city?: string;
  ownerName?: string; ownerEmail?: string; propertyCount: number; publishedPropertyCount: number; leadCount: number; userCount: number; createdAt?: string;
}

export default function PlatformAdminDashboard({ onLogout }: { onLogout: () => void }) {
  const [agencies, setAgencies] = useState<AdminAgency[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  const load = async () => { setLoading(true); setError(""); try { setAgencies(await apiRequest<AdminAgency[]>("/admin/agencies")); } catch (err) { setError(err instanceof Error ? err.message : "Unable to load agencies."); } finally { setLoading(false); } };
  useEffect(() => {
    const timerId = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timerId);
  }, []);
  const visible = useMemo(() => { const term=search.toLowerCase().trim(); return agencies.filter((a) => !term || [a.businessName,a.ownerName,a.ownerEmail,a.city,a.status].filter(Boolean).some((value) => value!.toLowerCase().includes(term))); }, [agencies,search]);
  const totalProperties=agencies.reduce((sum,a)=>sum+a.propertyCount,0); const totalLeads=agencies.reduce((sum,a)=>sum+a.leadCount,0);
  return <main className="platform-admin"><header className="platform-header"><div><p>NEXZIAN PLATFORM</p><h1>Admin Portal</h1></div><button onClick={onLogout}>Sign out</button></header><section className="platform-content"><div className="platform-intro"><div><p className="platform-eyebrow">CROSS AGENCY OVERVIEW</p><h2>Agency operations at a glance</h2><span>Platform administration is separate from agency workspaces.</span></div><button onClick={() => void load()} disabled={loading}>↻ Refresh</button></div><div className="platform-stats"><div><span>Agencies</span><strong>{agencies.length}</strong></div><div><span>Properties</span><strong>{totalProperties}</strong></div><div><span>Published</span><strong>{agencies.reduce((sum,a)=>sum+a.publishedPropertyCount,0)}</strong></div><div><span>Leads</span><strong>{totalLeads}</strong></div></div><div className="platform-toolbar"><input value={search} onChange={(e)=>setSearch(e.target.value)} placeholder="Search agency, owner, city or status..." type="search" /></div>{error && <div className="platform-error">{error}</div>}<section className="platform-table-wrap"><div className="platform-table-heading"><h3>Registered agencies</h3><span>{visible.length} shown</span></div>{loading ? <div className="platform-empty">Loading agency data...</div> : <div className="platform-scroll"><table><thead><tr><th>Agency</th><th>Owner</th><th>Status</th><th>Plan</th><th>Properties</th><th>Published</th><th>Leads</th><th>Users</th></tr></thead><tbody>{visible.map((a)=><tr key={a.id}><td><strong>{a.businessName}</strong><span>{a.city || "—"}{a.websiteSlug ? ` · /${a.websiteSlug}` : ""}</span></td><td><strong>{a.ownerName || "No owner"}</strong><span>{a.ownerEmail || "—"}</span></td><td><span className={`platform-status ${a.status.toLowerCase()}`}>{a.status}</span></td><td>{a.subscriptionPlan}</td><td>{a.propertyCount}</td><td>{a.publishedPropertyCount}</td><td>{a.leadCount}</td><td>{a.userCount}</td></tr>)}</tbody></table></div>}</section></section></main>;
}
