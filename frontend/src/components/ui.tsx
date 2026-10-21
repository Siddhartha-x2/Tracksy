import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { errorMessage } from '../api';
export function Icon({ name, size = 20 }: { name: string; size?: number }) {
  const paths: Record<string, ReactNode> = {
    warning: <><path d="m12 3 10 18H2L12 3Z"/><path d="M12 9v5m0 3h.01"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
    chevron: <path d="m9 5 7 7-7 7"/>,
    filter: <><path d="M4 7h16M7 12h10M10 17h4"/><circle cx="8" cy="7" r="1" fill="currentColor"/></>,
    info: <><circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/></>,
    github: <><path d="M9 19c-4 1-4-2-6-2m12 4v-4c0-1 .1-2-.5-2.7 3-.3 5.5-1.4 5.5-6.3a5 5 0 0 0-1.4-3.5c.1-.4.6-2-.2-3.5 0 0-1.1-.3-3.6 1.4a12 12 0 0 0-6.6 0C5.7.7 4.6 1 4.6 1c-.8 1.5-.3 3.1-.2 3.5A5 5 0 0 0 3 8c0 4.9 2.5 6 5.5 6.3C8 15 8 16 8 17v4" transform="translate(1 1) scale(.9)"/></>,
    overview: <><rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><path d="M14 15h7M14 20h7"/></>,
    tasks: <><path d="m4 6 2 2 3-4M12 6h8M4 13l2 2 3-4M12 13h8M4 20h4M12 20h8"/></>,
    evidence: <><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M8 13h8M8 17h5"/></>,
    team: <><circle cx="9" cy="8" r="3"/><path d="M2 21v-3a5 5 0 0 1 10 0v3M16 5a3 3 0 0 1 0 6M16 15a5 5 0 0 1 6 5"/></>,
    reports: <><path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/></>,
    milestones: <><path d="M5 22V3M5 3h14l-3 5 3 5H5"/></>,
    logs: <><rect x="4" y="4" width="16" height="17" rx="2"/><path d="M8 2v4M16 2v4M4 10h16M8 14h3M8 17h7"/></>,
    peer: <><path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9z"/></>,
    activity: <><path d="M3 12h4l3-8 4 16 3-8h4"/></>,
    settings: <><circle cx="12" cy="12" r="3"/><path d="m12 2 2 3 4-1 1 4 3 2-1 4-3 1-1 4-4-1-3 3-3-3-4-1 1-4-3-2 2-3V6l4-1z"/></>,
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></>,
    plus: <path d="M12 5v14M5 12h14"/>, close: <path d="m6 6 12 12M6 18 18 6"/>, arrow: <path d="M4 12h16m-6-6 6 6-6 6"/>, search: <><circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/></>, logout: <><path d="M9 4H4v16h5M9 12h12m-5-5 5 5-5 5"/></>, menu: <path d="M4 6h16M4 12h16M4 18h16"/>, check: <path d="m4 12 5 5L20 6"/>, download: <><path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/></>, shield: <><path d="m12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6z"/><path d="m8 12 3 3 5-6"/></>, link: <><path d="m9 15 6-6M7 17l-2 2a4 4 0 0 1-6-6l6-6a4 4 0 0 1 6 0M17 7l2-2a4 4 0 0 1 6 6l-6 6a4 4 0 0 1-6 0" transform="translate(2 0) scale(.85)"/></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name] || paths.overview}</svg>;
}
export function initials(name: string) { return name.split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0]).join('').toUpperCase(); }
export function date(value?: string, time = false) { if (!value) return 'Not set'; const parsed = new Date(value.length === 10 ? `${value}T12:00:00` : value); return Number.isNaN(parsed.valueOf()) ? value : parsed.toLocaleDateString(undefined, { day: 'numeric', month: 'short', ...(time ? { hour: '2-digit', minute: '2-digit' } : {}) }); }
export function percent(value?: number) { return `${Number.isFinite(value) ? Math.round(value!) : 0}%`; }
type Tone = 'neutral' | 'blue' | 'green' | 'amber' | 'orange' | 'red' | 'purple';
const badgeTones: Record<string, Tone> = {
  'active':'green', 'approved':'green', 'done':'green', 'completed':'green', 'verified':'green', 'oauth verified':'green',
  'in progress':'blue', 'low':'blue', 'available':'blue', 'member':'neutral', 'team member':'neutral',
  'under review':'amber', 'pending':'amber', 'medium':'amber', 'attention':'amber', 'planning':'neutral', 'planned':'neutral',
  'changes requested':'orange', 'reconnection needed':'orange', 'high':'red', 'blocked':'red', 'rejected':'red', 'overdue':'red',
  'leader':'purple', 'team leader':'purple', 'faculty':'blue', 'faculty guide':'blue', 'admin':'purple',
  'inactive':'neutral', 'disabled':'neutral', 'archived':'neutral', 'to do':'neutral', 'not configured':'neutral',
};
export function Badge({ children, tone }: { children: ReactNode; tone?: Tone }) { const label = String(children || ''); return <span className={`badge ${tone || badgeTones[label.toLowerCase()] || 'neutral'}`}><i aria-hidden="true"/>{children}</span>; }
export function Avatar({ name, small = false }: { name: string; small?: boolean }) { return <span className={`avatar ${small ? 'small' : ''}`} aria-hidden="true">{initials(name)}</span>; }
export function Empty({ title, detail, action, icon = 'overview' }: { title: string; detail?: string; action?: ReactNode; icon?: string }) { return <div className="empty"><span className="empty-icon"><Icon name={icon} size={23}/></span><h3>{title}</h3>{detail && <p>{detail}</p>}{action}</div>; }
export function Loading({ label = 'Loading workspace…' }: { label?: string }) { return <div className="loading" role="status"><span className="spinner"/>{label}</div>; }
export function ErrorNotice({ message, retry }: { message: string; retry?: () => void }) { return <div className="error-notice" role="alert"><b>Couldn’t complete the request</b><p>{message}</p>{retry && <button className="button secondary small-button" onClick={retry}>Try again</button>}</div>; }
export function PageHeading({ eyebrow, title, description, actions }: { eyebrow?: string; title: string; description?: string; actions?: ReactNode }) { return <header className="page-heading"><div>{eyebrow && <span className="eyebrow">{eyebrow}</span>}<h1>{title}</h1>{description && <p>{description}</p>}</div>{actions && <div className="heading-actions">{actions}</div>}</header>; }
export function Metric({ label, value, detail, icon, tone = 'blue' }: { label: string; value: ReactNode; detail?: string; icon?: string; tone?: Tone }) { return <div className={`metric metric-${tone}`}><div className="metric-heading"><span>{label}</span>{icon && <span className="metric-icon"><Icon name={icon}/></span>}</div><strong>{value}</strong>{detail && <small>{detail}</small>}</div>; }
export function SectionHeader({ title, description, action }: { title: string; description?: string; action?: ReactNode }) { return <div className="panel-heading"><div><h2>{title}</h2>{description && <p>{description}</p>}</div>{action}</div>; }
export function FormSection({ title, description, children }: { title: string; description?: string; children: ReactNode }) { return <section className="form-section"><div className="form-section-heading"><h3>{title}</h3>{description && <p>{description}</p>}</div>{children}</section>; }
export function AttentionItem({ icon = 'warning', title, detail, action, onClick, tone = 'amber' }: { icon?: string; title: string; detail: string; action: string; onClick: () => void; tone?: Tone }) { return <button className={`attention-item attention-${tone}`} onClick={onClick}><span className="attention-icon"><Icon name={icon}/></span><span><b>{title}</b><small>{detail}</small></span><span className="attention-action">{action}<Icon name="arrow" size={16}/></span></button>; }
export function Progress({ value, label }: { value: number; label: string }) { return <div className="progress" role="progressbar" aria-label={label} aria-valuenow={Math.max(0, Math.min(100, value))} aria-valuemin={0} aria-valuemax={100}><span style={{ width: `${Math.max(0, Math.min(100, value))}%` }}/></div>; }
export function Modal({ title, subtitle, children, onClose }: { title: string; subtitle?: string; children: ReactNode; onClose: () => void }) {
  const titleId = useId(); const descriptionId = useId();
  const ref = useRef<HTMLDivElement>(null); const closeRef = useRef(onClose); closeRef.current = onClose;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const focusable = () => Array.from(ref.current?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]') || []);
    focusable()[0]?.focus();
    function keys(event: KeyboardEvent) { if (event.key === 'Escape') closeRef.current(); if (event.key === 'Tab') { const items = focusable(); if (!items.length) return; const first = items[0]; const last = items.at(-1); if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); } } }
    const old = document.body.style.overflow; document.body.style.overflow = 'hidden'; document.addEventListener('keydown', keys);
    return () => { document.body.style.overflow = old; document.removeEventListener('keydown', keys); previous?.focus(); };
  }, []);
  return <div className="modal-scrim" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}><div className="modal" ref={ref} role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={subtitle ? descriptionId : undefined}><div className="modal-heading"><div><h2 id={titleId}>{title}</h2>{subtitle && <p id={descriptionId}>{subtitle}</p>}</div><button type="button" className="icon-button" aria-label="Close dialog" onClick={onClose}><Icon name="close"/></button></div>{children}</div></div>;
}
export function ActionForm({ children, onSubmit, submitLabel = 'Save changes', onCancel }: { children: ReactNode; onSubmit: (data: FormData, form: HTMLFormElement) => Promise<void>; submitLabel?: string; onCancel?: () => void }) {
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const submitting = useRef(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    const form = event.currentTarget;
    // Disabled fields are omitted by FormData. Capture values before the busy render.
    const data = new FormData(form);
    submitting.current = true; setBusy(true); setError('');
    try { await onSubmit(data, form); }
    catch (err) { setError(errorMessage(err)); }
    finally { submitting.current = false; setBusy(false); }
  }
  return <form onSubmit={submit} aria-busy={busy}><fieldset disabled={busy} className="form-fieldset">{children}</fieldset>{error && <ErrorNotice message={error}/>}<div className="form-actions">{onCancel && <button type="button" className="button secondary" onClick={onCancel} disabled={busy}>Cancel</button>}<button className="button" disabled={busy}>{busy && <span className="spinner small-spinner"/>}{busy ? 'Saving…' : submitLabel}</button></div></form>;
}
export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) { return <label className="field"><span>{label}</span>{children}{hint && <small>{hint}</small>}</label>; }
export function JsonDetail({ data }: { data: unknown }) { if (data == null) return null; if (typeof data === 'string') return <p className="pre-wrap">{data}</p>; if (typeof data === 'object') return <dl className="detail-list">{Object.entries(data).map(([key, value]) => <div key={key}><dt>{key.replace(/([A-Z])/g, ' $1')}</dt><dd>{typeof value === 'object' ? JSON.stringify(value) : String(value ?? '—')}</dd></div>)}</dl>; return <span>{String(data)}</span>; }
