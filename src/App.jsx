import { useEffect, useMemo, useState } from 'react';
import { api } from './api.js';

const NAV_ITEMS = [
  ['overview', '◫', 'Overview'],
  ['tasks', '☷', 'Tasks'],
  ['evidence', '⌁', 'Evidence'],
  ['team', '♧', 'Team'],
  ['reports', '▥', 'Reports'],
];

function initials(name = '') {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join('').toUpperCase();
}

function formatDate(value) {
  if (!value) return 'No due date';
  const date = new Date(`${value.slice(0, 10)}T12:00:00`);
  return Number.isNaN(date.valueOf()) ? 'No due date' : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function Badge({ children }) {
  const value = String(children || '');
  const tone = ['Approved', 'Done', 'faculty'].includes(value) ? 'success'
    : ['Under review', 'In progress', 'High'].includes(value) ? 'warning'
      : ['Changes requested'].includes(value) ? 'danger'
        : ['Medium'].includes(value) ? 'blue' : '';
  return <span className={`badge ${tone}`}>{value}</span>;
}

function Loading({ label = 'Loading Tracksy…' }) {
  return <div className="loading"><span className="spinner" />{label}</div>;
}

function AuthScreen({ onLogin, onRegister, notice }) {
  const [mode, setMode] = useState('login');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    const form = new FormData(event.currentTarget);
    try {
      if (mode === 'login') await onLogin(form.get('email'), form.get('password'));
      else await onRegister(form.get('name'), form.get('email'), form.get('password'));
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }

  return <div className="auth-layout">
    <aside className="auth-story">
      <a className="brand brand-light" href="#top"><span className="brand-mark">↗</span>tracksy</a>
      <div className="story-content">
        <span className="eyebrow eyebrow-light">TEAMWORK, MADE VISIBLE</span>
        <h1>Good work deserves to be seen.</h1>
        <p>Track tasks, share evidence, and give every teammate a fair view of their contribution.</p>
        <div className="story-flow"><span>Plan work</span><i>→</i><span>Share evidence</span><i>→</i><span>Recognize effort</span></div>
      </div>
      <span className="story-foot">A clearer way to build together.</span>
    </aside>
    <main className="auth-main">
      <form className="auth-card" key={mode} onSubmit={submit}>
        <span className="eyebrow">YOUR PROJECT WORKSPACE</span>
        <h2>{mode === 'login' ? 'Welcome back' : 'Create your account'}</h2>
        <p className="muted">{mode === 'login' ? 'Sign in to pick up where your team left off.' : 'Student accounts can join a project with a faculty code.'}</p>
        {notice && <div className="notice">{notice}</div>}
        {error && <div className="form-error" role="alert">{error}</div>}
        {mode === 'register' && <label className="field"><span>Your name</span><input name="name" autoComplete="name" required minLength="2" maxLength="100" placeholder="e.g. Sam Taylor" /></label>}
        <label className="field"><span>Email address</span><input name="email" type="email" autoComplete="email" required placeholder="you@university.edu" defaultValue={mode === 'login' ? 'aarav@tracksy.demo' : ''} /></label>
        <label className="field"><span>Password</span><input name="password" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required minLength={mode === 'register' ? 10 : 1} placeholder={mode === 'register' ? 'At least 10 characters' : 'Your password'} defaultValue={mode === 'login' ? 'TracksyDemo2026!' : ''} /></label>
        <button className="button button-wide" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'} <span>→</span></button>
        <button className="plain-link" type="button" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(''); }}>
          {mode === 'login' ? 'New to Tracksy? Create a student account' : 'Already have an account? Sign in'}
        </button>
        {mode === 'login' && <div className="demo-credentials"><b>Demo logins</b><span>Student: aarav@tracksy.demo</span><span>Faculty: faculty@tracksy.demo</span><span>Password: TracksyDemo2026!</span></div>}
      </form>
    </main>
  </div>;
}

function EmptyProject({ user, onJoin, onCreate, busy }) {
  const faculty = user.role === 'faculty';
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [term, setTerm] = useState('');
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault();
    setError('');
    try {
      if (faculty) await onCreate({ name, description, term });
      else await onJoin(code);
    } catch (err) { setError(err.message); }
  }

  return <div className="empty-project-page"><div className="empty-project-card"><div className="empty-illustration">✳</div><span className="eyebrow">YOUR NEXT TEAM STARTS HERE</span><h1>{faculty ? 'Create a project' : 'Join your project'}</h1><p className="muted">{faculty ? 'Set up a team workspace and invite students with a join code.' : 'Enter the join code from your faculty guide to open your project workspace.'}</p>
    <form onSubmit={submit}>
      {faculty ? <><label className="field"><span>Project name</span><input value={name} onChange={(e) => setName(e.target.value)} required maxLength="120" placeholder="e.g. Campus Connect" /></label><label className="field"><span>Description</span><textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength="1000" placeholder="What is your team building?" /></label><label className="field"><span>Term</span><input value={term} onChange={(e) => setTerm(e.target.value)} placeholder="e.g. Fall term" /></label></>
        : <label className="field"><span>Project join code</span><input value={code} onChange={(e) => setCode(e.target.value)} required placeholder="Enter the code from your faculty guide" /></label>}
      {error && <div className="form-error">{error}</div>}
      <button className="button button-wide" disabled={busy}>{busy ? 'Please wait…' : faculty ? 'Create workspace' : 'Join project'} <span>→</span></button>
    </form>
  </div></div>;
}

function Modal({ modal, onClose, onCreateTask, onCreateProject, onEvidence, onReview, members, busy }) {
  const [error, setError] = useState('');
  if (!modal) return null;
  const evidenceItem = modal.evidence;

  async function submit(event) {
    event.preventDefault();
    setError('');
    const form = event.currentTarget;
    const data = new FormData(form);
    try {
      if (modal.type === 'project') {
        await onCreateProject({ name: data.get('name'), description: data.get('description'), term: data.get('term') });
      } else if (modal.type === 'task') {
        await onCreateTask({ name: data.get('name'), details: data.get('details'), assignedTo: data.get('assignedTo'), dueDate: data.get('dueDate'), priority: data.get('priority') });
      } else if (modal.type === 'review') {
        await onReview(evidenceItem.id, data.get('decision'), data.get('feedback'));
      } else {
        await onEvidence({ taskId: modal.task?.id || evidenceItem?.taskId, evidenceId: evidenceItem?.id, data });
      }
    } catch (err) { setError(err.message); }
  }

  return <div className="modal-scrim" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <form className="modal-card" onSubmit={submit}>
      <div className="modal-head"><div><span className="eyebrow">{modal.type === 'project' ? 'TEAM WORKSPACE' : modal.type === 'task' ? 'PROJECT PLANNING' : modal.type === 'review' ? 'FACULTY REVIEW' : 'CONTRIBUTION EVIDENCE'}</span><h2>{modal.type === 'project' ? 'Create a project' : modal.type === 'task' ? 'Create a task' : modal.type === 'review' ? 'Review submission' : evidenceItem ? 'Update your evidence' : 'Submit evidence'}</h2></div><button className="icon-button" type="button" aria-label="Close" onClick={onClose}>×</button></div>
      {modal.type === 'project' && <><label className="field"><span>Project name</span><input name="name" required minLength="2" maxLength="120" placeholder="e.g. Campus Connect" /></label><label className="field"><span>Description</span><textarea name="description" maxLength="1000" placeholder="What is your team building?" /></label><label className="field"><span>Term</span><input name="term" maxLength="80" placeholder="e.g. Fall term" /></label></>}
      {modal.type === 'task' && <><label className="field"><span>Task name</span><input name="name" required minLength="2" maxLength="160" placeholder="What needs to get done?" /></label><label className="field"><span>Task details</span><textarea name="details" maxLength="2000" placeholder="Add context or a definition of done…" /></label><div className="form-row"><label className="field"><span>Assign to</span><select name="assignedTo" required>{members.filter((member) => member.role === 'student').map((member) => <option key={member.id} value={member.id}>{member.name}</option>)}</select></label><label className="field"><span>Priority</span><select name="priority"><option>Medium</option><option>High</option><option>Low</option></select></label></div><label className="field"><span>Due date</span><input name="dueDate" type="date" min={new Date().toISOString().slice(0, 10)} /></label></>}
      {modal.type === 'evidence' && <><div className="context-box"><b>{evidenceItem ? evidenceItem.taskName : modal.task.name}</b><span>{evidenceItem ? `Submission ${evidenceItem.id} · version ${evidenceItem.version}` : 'Your work is reviewed by the project faculty guide.'}</span></div><label className="field"><span>What did you work on?</span><input name="title" defaultValue={evidenceItem?.title || ''} required minLength="2" maxLength="160" placeholder="e.g. First onboarding wireframes" /></label><label className="field"><span>Describe your contribution</span><textarea name="note" defaultValue={evidenceItem?.note || ''} maxLength="4000" placeholder="Explain what you did and what changed…" /></label><label className="field"><span>Link to your work (optional)</span><input name="workLink" type="url" defaultValue={evidenceItem?.workLink || ''} maxLength="1000" placeholder="https://…" /></label><label className="field"><span>Attach a file (optional · PDF, PNG, JPG, TXT, DOCX · max 10 MB)</span><input name="file" type="file" accept=".pdf,.png,.jpg,.jpeg,.txt,.docx" /></label>{evidenceItem?.feedback && <div className="feedback-box"><b>Faculty feedback</b><p>{evidenceItem.feedback}</p></div>}</>}
      {modal.type === 'review' && <><div className="context-box"><b>{evidenceItem.title}</b><span>{evidenceItem.studentName} · {evidenceItem.taskName} · version {evidenceItem.version}</span></div><p className="review-note">{evidenceItem.note || 'No written note was added.'}</p>{evidenceItem.workLink && <a className="plain-link align-left" href={evidenceItem.workLink} target="_blank" rel="noreferrer">Open submitted work ↗</a>}{evidenceItem.hasFile && <a className="plain-link align-left" href={`/api/evidence/${encodeURIComponent(evidenceItem.id)}/file`}>Download {evidenceItem.originalFilename} ↓</a>}<label className="field"><span>Decision</span><select name="decision"><option>Approved</option><option>Changes requested</option></select></label><label className="field"><span>Feedback to student</span><textarea name="feedback" required maxLength="1000" defaultValue={evidenceItem.feedback || ''} placeholder="Explain your decision and any next steps…" /></label></>}
      {error && <div className="form-error" role="alert">{error}</div>}
      <div className="modal-actions"><button type="button" className="button button-quiet" onClick={onClose}>Cancel</button><button className="button" disabled={busy}>{busy ? 'Saving…' : modal.type === 'project' ? 'Create project' : modal.type === 'task' ? 'Create task' : modal.type === 'review' ? 'Save review' : evidenceItem ? 'Resubmit evidence' : 'Submit for review'}</button></div>
    </form>
  </div>;
}

export default function App() {
  const [user, setUser] = useState(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [projects, setProjects] = useState([]);
  const [projectId, setProjectId] = useState('');
  const [data, setData] = useState({ tasks: [], evidence: [], members: [], report: null });
  const [dataLoading, setDataLoading] = useState(false);
  const [page, setPage] = useState('overview');
  const [modal, setModal] = useState(null);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState('');
  const [projectActionBusy, setProjectActionBusy] = useState(false);

  const project = projects.find((item) => item.id === projectId) || projects[0] || null;
  const isFaculty = user?.role === 'faculty';
  const myEvidence = useMemo(() => data.evidence, [data.evidence]);

  useEffect(() => {
    api.me().then(({ user: currentUser }) => setUser(currentUser)).catch(() => setUser(null)).finally(() => setAuthLoading(false));
  }, []);

  useEffect(() => {
    if (!user) { setProjects([]); setProjectId(''); return; }
    let cancelled = false;
    api.projects().then(({ projects: items }) => {
      if (cancelled) return;
      setProjects(items);
      setProjectId((current) => items.some((item) => item.id === current) ? current : items[0]?.id || '');
    }).catch((error) => showToast(error.message));
    return () => { cancelled = true; };
  }, [user]);

  useEffect(() => {
    if (!user || !projectId) { setData({ tasks: [], evidence: [], members: [], report: null }); return; }
    let cancelled = false;
    setDataLoading(true);
    Promise.all([api.tasks(projectId), api.evidence(projectId), api.team(projectId), api.report(projectId)])
      .then(([tasks, evidence, team, report]) => {
        if (!cancelled) setData({ tasks: tasks.tasks, evidence: evidence.evidence, members: team.members, report });
      })
      .catch((error) => { if (!cancelled) showToast(error.message); })
      .finally(() => { if (!cancelled) setDataLoading(false); });
    return () => { cancelled = true; };
  }, [user, projectId]);

  useEffect(() => {
    if (!toast) return undefined;
    const timer = setTimeout(() => setToast(''), 3000);
    return () => clearTimeout(timer);
  }, [toast]);

  function showToast(message) { setToast(message); }

  async function refreshProject() {
    if (!projectId) return;
    const [tasks, evidence, team, report] = await Promise.all([api.tasks(projectId), api.evidence(projectId), api.team(projectId), api.report(projectId)]);
    setData({ tasks: tasks.tasks, evidence: evidence.evidence, members: team.members, report });
  }

  async function handleLogin(email, password) {
    const result = await api.login(email, password);
    setUser(result.user);
    setPage('overview');
  }

  async function handleRegister(name, email, password) {
    const result = await api.register(name, email, password);
    setUser(result.user);
    setPage('overview');
  }

  async function handleLogout() {
    await api.logout();
    setUser(null);
    setData({ tasks: [], evidence: [], members: [], report: null });
    setToast('You have signed out.');
  }

  async function handleJoinProject(code) {
    setProjectActionBusy(true);
    try {
      const { project: joined } = await api.joinProject(code);
      const { projects: items } = await api.projects();
      setProjects(items);
      setProjectId(joined.id);
      showToast(`You joined ${joined.name}.`);
    } finally { setProjectActionBusy(false); }
  }

  async function handleCreateProject(values) {
    setProjectActionBusy(true);
    try {
      const { project: created } = await api.createProject(values);
      const { projects: items } = await api.projects();
      setProjects(items);
      setProjectId(created.id);
      showToast(`Project created. Share join code ${created.joinCode} with your students.`);
    } finally { setProjectActionBusy(false); }
  }

  async function runModalAction(action) {
    setBusy(true);
    try { await action(); setModal(null); await refreshProject(); }
    finally { setBusy(false); }
  }

  async function createTask(values) {
    await runModalAction(() => api.createTask(project.id, values));
    showToast('Task created and assigned.');
  }

  async function createProject(values) {
    setBusy(true);
    try {
      const { project: created } = await api.createProject(values);
      const { projects: items } = await api.projects();
      setProjects(items);
      setProjectId(created.id);
      setPage('overview');
      setModal(null);
      showToast(`Project created. Share join code ${created.joinCode} with students.`);
    } catch (error) { throw error; }
    finally { setBusy(false); }
  }

  async function saveEvidence({ taskId, evidenceId, data: formData }) {
    await runModalAction(() => evidenceId ? api.resubmitEvidence(evidenceId, formData) : api.submitEvidence(project.id, taskId, formData));
    showToast(evidenceId ? 'Updated evidence is back under review.' : 'Evidence submitted to your faculty guide.');
  }

  async function saveReview(evidenceId, decision, feedback) {
    await runModalAction(() => api.reviewEvidence(evidenceId, decision, feedback));
    showToast(decision === 'Approved' ? 'Evidence approved. The report has been updated.' : 'Changes requested. Feedback is visible to the student.');
  }

  async function changeTaskStatus(task, status) {
    try { await api.updateTask(task.id, status); await refreshProject(); showToast('Task status updated.'); }
    catch (error) { showToast(error.message); }
  }

  function openTaskEvidence(task) {
    const existing = data.evidence.find((item) => item.taskId === task.id);
    setModal({ type: 'evidence', task, evidence: existing?.status === 'Changes requested' ? existing : null });
  }

  if (authLoading) return <Loading />;
  if (!user) return <AuthScreen onLogin={handleLogin} onRegister={handleRegister} notice={toast} />;
  if (projects.length === 0) return <EmptyProject user={user} onJoin={handleJoinProject} onCreate={handleCreateProject} busy={projectActionBusy} />;
  if (dataLoading && !data.report) return <Loading label="Opening your project…" />;

  const title = ({ overview: 'Overview', tasks: 'Tasks', evidence: 'Evidence', team: 'Team', reports: 'Reports' })[page];
  const pendingReviews = myEvidence.filter((item) => item.status === 'Under review').length;
  const completedTasks = data.tasks.filter((task) => task.status === 'Done').length;
  const approvedCount = isFaculty ? data.evidence.filter((item) => item.status === 'Approved').length : myEvidence.filter((item) => item.status === 'Approved').length;
  const myScore = data.report?.members.find((member) => member.userId === user.id)?.contributionScore || 0;
  const score = isFaculty ? Math.round(data.report?.summary.completionPercent || 0) : myScore;

  return <div className="app-shell">
    <aside className="sidebar">
      <a className="brand" href="#home" onClick={(e) => { e.preventDefault(); setPage('overview'); }}><span className="brand-mark">↗</span>tracksy</a>
      <div className="sidebar-label">Workspace</div>
      <nav className="navigation" aria-label="Workspace navigation">{NAV_ITEMS.map(([id, icon, label]) => <button key={id} className={page === id ? 'nav-item active' : 'nav-item'} onClick={() => setPage(id)}><span>{icon}</span>{label}{id === 'evidence' && isFaculty && pendingReviews > 0 && <i>{pendingReviews}</i>}</button>)}</nav>
      <div className="sidebar-spacer" />
      <div className="project-mini"><span className="sidebar-label">Active project</span><b>{project.name}</b><small>{project.term || 'Project workspace'} · {project.member_count} members</small></div>
      <div className="user-menu"><div className="avatar">{initials(user.name)}</div><div className="user-copy"><b>{user.name}</b><small>{user.role === 'faculty' ? 'Faculty guide' : 'Student'}</small></div><button className="logout-button" onClick={handleLogout} title="Sign out" aria-label="Sign out">↗</button></div>
    </aside>
    <main className="main-area">
      <header className="topbar"><div className="breadcrumb">Tracksy <span>/</span> <b>{title}</b></div><div className="topbar-right">{isFaculty && <button className="button button-small" onClick={() => setModal({ type: 'project' })}>＋ New project</button>}<select className="project-select" aria-label="Choose project" value={project.id} onChange={(e) => setProjectId(e.target.value)}>{projects.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select><span className="top-date">{new Date().toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })}</span></div></header>
      <div className="content-area">
        {page === 'overview' && <>
          <section className="welcome"><div><span className="eyebrow">YOUR PROJECT WORKSPACE</span><h1>{new Date().getHours() < 12 ? 'Good morning' : new Date().getHours() < 18 ? 'Good afternoon' : 'Good evening'}, {user.name.split(' ')[0]} <span>✳</span></h1><p>Here’s where the work stands today.</p></div>{isFaculty && <button className="button" onClick={() => setModal({ type: 'task' })}>＋ Create task</button>}</section>
          <div className="stats-grid">
            <Stat label="Tasks" value={isFaculty ? data.tasks.length : data.tasks.length} icon="☷" foot={`${completedTasks} completed`} />
            <Stat label={isFaculty ? 'Awaiting review' : 'Your evidence'} value={isFaculty ? pendingReviews : myEvidence.length} icon="⌁" foot={isFaculty ? 'Ready for your decision' : `${approvedCount} approved`} />
            <Stat label="Project progress" value={`${data.report?.summary.completionPercent || 0}%`} icon="↗" foot={`${completedTasks} of ${data.report?.summary.taskCount || 0} tasks complete`} />
            <Stat label={isFaculty ? 'Verified submissions' : 'Your contribution'} value={isFaculty ? approvedCount : `${score}%`} icon="◷" foot={isFaculty ? 'Approved evidence' : `${myScore} contribution points score`} />
          </div>
          <div className="dashboard-grid">
            <section className="panel"><div className="panel-heading"><div><h2>{project.name}</h2><p>{project.description || 'Your project team workspace.'}</p></div><Badge>In progress</Badge></div><div className="project-facts"><span>♧ &nbsp;{project.member_count} members</span><span>☷ &nbsp;{data.tasks.length} tasks</span><span>⌁ &nbsp;{data.evidence.length} evidence submissions</span></div><div className="subhead"><div><h3>{isFaculty ? 'Project tasks' : 'Your tasks'}</h3><p>Keep each next step clear.</p></div><button className="text-button" onClick={() => setPage('tasks')}>See all →</button></div><TaskList tasks={data.tasks.slice(0, 5)} isFaculty={isFaculty} onEvidence={openTaskEvidence} onStatus={changeTaskStatus} />
            </section>
            <div className="side-panels"><section className="panel score-panel"><div className="panel-heading"><div><h2>{isFaculty ? 'Project completion' : 'Your contribution'}</h2><p>{isFaculty ? 'Task completion across the team' : 'Verified, assigned work'}</p></div><div className="score-ring" style={{ '--score': score }}><b>{score}%</b></div></div><div className="progress-label"><span>{isFaculty ? 'Tasks completed' : 'Approved task points'}</span><b>{isFaculty ? `${completedTasks}/${data.report?.summary.taskCount || 0}` : `${data.report?.members.find((member) => member.userId === user.id)?.approvedPoints || 0}/${data.report?.members.find((member) => member.userId === user.id)?.assignedPoints || 0}`}</b></div><div className="progress"><i style={{ width: `${Math.min(100, score)}%` }} /></div><p className="helper-copy">{isFaculty ? 'The report shows approved contributions for every team member.' : 'Only faculty-approved evidence contributes to your score.'}</p><button className="button button-quiet button-wide" onClick={() => setPage('reports')}>Open contribution report&nbsp; →</button></section>
              <section className="panel"><div className="panel-heading"><div><h2>Recent submissions</h2><p>Evidence from your project</p></div><button className="text-button" onClick={() => setPage('evidence')}>All evidence →</button></div><EvidenceActivity evidence={data.evidence.slice(0, 4)} isFaculty={isFaculty} onReview={(item) => setModal({ type: 'review', evidence: item })} /></section></div>
          </div>
        </>}
        {page === 'tasks' && <><PageHeading eyebrow="PROJECT PLANNING" title={isFaculty ? 'Project tasks' : 'Your tasks'} description={isFaculty ? 'Assign work, track progress, and keep the team moving.' : 'Your assigned work, deadlines, and next steps.'} action={isFaculty && <button className="button" onClick={() => setModal({ type: 'task' })}>＋ Create task</button>} /><section className="panel"><TaskList tasks={data.tasks} isFaculty={isFaculty} onEvidence={openTaskEvidence} onStatus={changeTaskStatus} full /></section></>}
        {page === 'evidence' && <><PageHeading eyebrow="WORK, WITH CONTEXT" title="Evidence" description={isFaculty ? 'Review work submitted by the students in this project.' : 'Show what you worked on and keep feedback with each submission.'} /><EvidenceList evidence={data.evidence} isFaculty={isFaculty} onReview={(item) => setModal({ type: 'review', evidence: item })} onResubmit={(item) => setModal({ type: 'evidence', evidence: item })} /></>}
        {page === 'team' && <><PageHeading eyebrow={project.name.toUpperCase()} title="Project team" description="The people sharing the work and the responsibility." /><section className="panel"><div className="panel-heading"><div><h2>Members</h2><p>{data.members.length} people in this project</p></div>{isFaculty && <JoinCode code={project.join_code} />}</div><div className="member-list">{data.members.map((member) => <div className="member-row" key={member.id}><div className="avatar">{initials(member.name)}</div><div className="member-info"><b>{member.name}{member.id === user.id ? ' · You' : ''}</b><small>{member.email || (member.role === 'faculty' ? 'Project faculty guide' : 'Project student')}</small></div><Badge>{member.role}</Badge></div>)}</div></section></>}
        {page === 'reports' && <><PageHeading eyebrow="FAIR CONTRIBUTION, MADE VISIBLE" title="Contribution report" description="Scores are calculated from approved task points, compared with each student’s assigned points." /><ReportView report={data.report} /></>}
      </div>
    </main>
    {toast && <div className="toast" role="status">{toast}</div>}
    <Modal modal={modal} onClose={() => setModal(null)} onCreateTask={createTask} onCreateProject={createProject} onEvidence={saveEvidence} onReview={saveReview} members={data.members} busy={busy} />
  </div>;
}

function Stat({ label, value, icon, foot }) {
  return <article className="stat-card"><div className="stat-top"><span>{label}</span><span className="stat-icon">{icon}</span></div><strong>{value}</strong><small>{foot}</small></article>;
}

function PageHeading({ eyebrow, title, description, action }) {
  return <div className="page-heading"><div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p></div>{action}</div>;
}

function TaskList({ tasks, isFaculty, onEvidence, onStatus, full = false }) {
  if (!tasks.length) return <EmptyState title="No tasks yet" copy={isFaculty ? 'Create a task to get your project moving.' : 'Your faculty guide has not assigned any tasks to you yet.'} />;
  return <div className={full ? 'task-table-wrap' : 'task-list'}>
    {full && <div className="task-table-head"><span>Task</span><span>Assigned to</span><span>Due date</span><span>Priority</span><span>Status</span><span>Action</span></div>}
    {tasks.map((task) => <div className={full ? 'task-line full' : 'task-line'} key={task.id}>
      <div className="task-primary"><span className="task-symbol">{task.status === 'Done' ? '✓' : '↗'}</span><div><b>{task.name}</b><small>{task.id}{task.details ? ` · ${task.details}` : ''}</small></div></div>
      {full && <span className="task-cell">{task.assignee_name}</span>}
      {full ? <><span className="task-cell due-cell">{formatDate(task.due_date)}</span><span className="task-cell"><Badge>{task.priority}</Badge><small className="points">{task.points} pt{task.points === 1 ? '' : 's'}</small></span><span className="task-cell">{isFaculty ? <select className="status-select" aria-label={`Change status for ${task.name}`} value={task.status} onChange={(e) => onStatus(task, e.target.value)}><option>To do</option><option>In progress</option><option>Under review</option><option>Done</option></select> : <Badge>{task.status}</Badge>}</span></> : <div className="task-compact-meta"><span>{formatDate(task.due_date)}</span><Badge>{task.status}</Badge></div>}
      <div className="task-action">{!isFaculty && (!task.evidence_status ? <button className="text-button" onClick={() => onEvidence(task)}>＋ Submit evidence</button> : task.evidence_status === 'Changes requested' ? <button className="text-button" onClick={() => onEvidence(task)}>Update evidence →</button> : <Badge>{task.evidence_status}</Badge>)}</div>
    </div>)}
  </div>;
}

function EvidenceActivity({ evidence, isFaculty, onReview }) {
  if (!evidence.length) return <EmptyState title="No submissions yet" copy="Evidence submitted by your team will appear here." />;
  return <div className="activity-list">{evidence.map((item) => <div className="activity-row" key={item.id}><span className={`activity-mark ${item.status === 'Approved' ? 'approved' : ''}`}>{item.status === 'Approved' ? '✓' : '⌁'}</span><div className="activity-copy"><b>{item.studentName}</b><span>{item.title}</span><small>{item.taskName} · {formatDate(item.submittedAt)}</small></div><div className="activity-action">{isFaculty && item.status === 'Under review' ? <button className="text-button" onClick={() => onReview(item)}>Review →</button> : <Badge>{item.status}</Badge>}</div></div>)}</div>;
}

function EvidenceList({ evidence, isFaculty, onReview, onResubmit }) {
  const [filter, setFilter] = useState('All evidence');
  const [query, setQuery] = useState('');
  const filtered = evidence.filter((item) => (filter === 'All evidence' || item.status === filter) && `${item.title} ${item.studentName} ${item.taskName}`.toLowerCase().includes(query.toLowerCase()));
  return <section className="panel"><div className="list-tools"><input className="search-input" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="⌕  Search evidence…" /><select className="filter-select" value={filter} onChange={(e) => setFilter(e.target.value)}><option>All evidence</option><option>Under review</option><option>Approved</option><option>Changes requested</option></select></div>
    {!filtered.length ? <EmptyState title="No evidence found" copy="Try another search or status filter." /> : <div className="evidence-list">{filtered.map((item) => <article className="evidence-card" key={item.id}><div className="evidence-top"><div className="evidence-icon">⌁</div><div className="evidence-title"><b>{item.title}</b><span>{item.taskName} · {item.studentName} · v{item.version}</span></div><Badge>{item.status}</Badge></div><p>{item.note || 'No written description.'}</p><div className="evidence-bottom"><span>Submitted {formatDate(item.submittedAt)}{item.workLink ? <> · <a href={item.workLink} target="_blank" rel="noreferrer">Open work ↗</a></> : null}{item.hasFile ? <> · <a href={`/api/evidence/${encodeURIComponent(item.id)}/file`}>Download {item.originalFilename} ↓</a></> : null}</span><div>{isFaculty && item.status === 'Under review' && <button className="button button-small" onClick={() => onReview(item)}>Review evidence</button>}{!isFaculty && item.status === 'Changes requested' && <button className="button button-small" onClick={() => onResubmit(item)}>Update submission</button>}</div></div>{item.feedback && <div className="feedback-box"><b>Faculty feedback</b><p>{item.feedback}</p></div>}</article>)}</div>}
  </section>;
}

function ReportView({ report }) {
  if (!report) return <Loading label="Preparing contribution report…" />;
  const { members, summary } = report;
  const highest = Math.max(1, ...members.map((member) => member.approvedPoints));
  return <><div className="stats-grid report-stats"><Stat label="Verified submissions" value={summary.approvedSubmissions || 0} icon="✓" foot="Approved by faculty" /><Stat label="Project completion" value={`${summary.completionPercent}%`} icon="↗" foot={`${summary.completedTasks || 0} tasks completed`} /><Stat label="Pending review" value={summary.pendingReviews || 0} icon="◷" foot="Waiting for a faculty decision" /><Stat label="Team members" value={members.length} icon="♧" foot="In this project" /></div><section className="panel report-panel"><div className="panel-heading"><div><h2>Contribution by member</h2><p>Score = approved task points ÷ points assigned to that student.</p></div><button className="button button-small button-quiet" onClick={() => exportReport(members)}>↓ &nbsp;Export CSV</button></div><div className="report-list">{members.filter((member) => member.role === 'student').map((member) => <div className="report-row" key={member.userId}><div className="avatar">{initials(member.name)}</div><div className="report-name"><b>{member.name}</b><small>{member.approvedTasks} approved task{member.approvedTasks === 1 ? '' : 's'} · {member.approvedPoints} of {member.assignedPoints} points</small></div><div className="report-bar"><span><i style={{ width: `${Math.min(100, Math.round((member.approvedPoints / highest) * 100))}%` }} /></span><b>{member.contributionScore}%</b></div></div>)}</div></section></>;
}

function exportReport(members) {
  const quote = (value) => `"${String(value ?? '').replaceAll('"', '""')}"`;
  const rows = [['Student', 'Approved tasks', 'Approved points', 'Assigned points', 'Contribution score (%)'],
    ...members.filter((member) => member.role === 'student').map((member) => [member.name, member.approvedTasks, member.approvedPoints, member.assignedPoints, member.contributionScore])];
  const csv = rows.map((row) => row.map(quote).join(',')).join('\r\n');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'tracksy-contribution-report.csv';
  link.click();
  URL.revokeObjectURL(url);
}

function JoinCode({ code }) {
  const [copied, setCopied] = useState(false);
  return <button className="join-code" onClick={() => { navigator.clipboard?.writeText(code).then(() => setCopied(true)).catch(() => setCopied(false)); }} title="Copy project join code"><small>Student join code</small><b>{code}</b><span>{copied ? 'Copied' : 'Copy'}</span></button>;
}

function EmptyState({ title, copy }) {
  return <div className="empty-state"><span>✳</span><b>{title}</b><p>{copy}</p></div>;
}
