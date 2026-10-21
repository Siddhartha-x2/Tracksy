import { useState } from 'react';
import { api, errorMessage } from '../api';
import { useWorkspace } from '../context';
import { useResource } from '../hooks/useResource';
import type { Activity, Evidence, Milestone, Report, Task } from '../types';
import { AttentionItem, Avatar, Badge, date, Empty, ErrorNotice, Icon, Loading, Metric, PageHeading, percent, SectionHeader } from '../components/ui';

export default function Overview() {
  const {user,project,revision,members,navigate,refresh,notify} = useWorkspace();
  const report = useResource<Report>(`/projects/${project.id}/report`,revision);
  const tasks = useResource<{tasks:Task[]}>(`/projects/${project.id}/tasks`,revision);
  const evidence = useResource<{evidence:Evidence[]}>(`/projects/${project.id}/evidence`,revision);
  const milestones = useResource<{milestones:Milestone[]}>(`/projects/${project.id}/milestones`,revision);
  const activity = useResource<{events:Activity[]}>(`/projects/${project.id}/activity`,revision);
  const actionable = (tasks.data?.tasks || []).filter(task => task.status !== 'Done').sort((a,b) => (a.dueDate || '9999').localeCompare(b.dueDate || '9999')).slice(0,4);
  const pending = (evidence.data?.evidence || []).filter(item => /review/i.test(item.status));
  const summary = report.data?.summary;
  const today = new Date().toISOString().slice(0,10);
  const nextWeek = new Date(Date.now()+7*86400000).toISOString().slice(0,10);
  const overdue = (tasks.data?.tasks || []).filter(task => task.status !== 'Done' && task.dueDate && task.dueDate.slice(0,10) < today);
  const dueMilestones = (milestones.data?.milestones || []).filter(item => item.status !== 'Completed' && item.dueDate && item.dueDate.slice(0,10) <= nextWeek);
  const attentionLoading = (tasks.loading&&!tasks.data) || (evidence.loading&&!evidence.data) || (milestones.loading&&!milestones.data);
  const attentionUnavailable = Boolean(tasks.error || evidence.error || milestones.error);
  const [supervisionBusy,setSupervisionBusy] = useState(false);
  const [supervisionError,setSupervisionError] = useState('');
  async function supervise(accept:boolean) {setSupervisionBusy(true);setSupervisionError('');try{await api(`/projects/${project.id}/supervision`,{method:'POST',body:{accept}});refresh();notify(accept?'Supervision approved.':'Supervision declined.');}catch(error){setSupervisionError(errorMessage(error));}finally{setSupervisionBusy(false);}}

  return <>
    <PageHeading eyebrow="YOUR WORKSPACE" title={`Good to see you, ${user.name.split(' ')[0]}.`} description="Your project, the next steps, and the work that needs a decision." actions={<>
      <button className="button secondary" onClick={() => navigate('reports')}><Icon name="reports" size={17}/>Contribution report</button>
      <button className="button" onClick={() => navigate(user.role === 'faculty' ? 'evidence' : 'tasks')}><Icon name={user.role === 'faculty' ? 'evidence' : 'tasks'} size={17}/>{user.role === 'faculty' ? 'Review evidence' : 'View my tasks'}</button>
    </>}/>

    <div className="overview-lead">
    <section className="project-hero" aria-label="Current project">
      <div><div className="hero-label"><span className="eyebrow">CURRENT PROJECT</span><Badge>{project.status}</Badge></div>
        <h2>{project.name}</h2><p>{project.description || 'A shared workspace for your team’s project work.'}</p>
        <div className="hero-meta">
          {project.term && <span><Icon name="logs" size={16}/>{project.term}</span>}
          <span><Icon name="team" size={16}/>{members.filter(member => member.active).length} team members</span>
          <span><Icon name="clock" size={16}/>Due {date(project.deadline)}</span>
          {project.techStack && <span>{project.techStack}</span>}
        </div>
      </div>
      <div className="hero-progress"><div className="completion-ring" role="progressbar" aria-label="Project verified completion" aria-valuemin={0} aria-valuemax={100} aria-valuenow={summary ? summary.completionPercent : undefined} aria-valuetext={summary ? percent(summary.completionPercent) : 'Awaiting project report'}><svg viewBox="0 0 120 120" aria-hidden="true"><circle cx="60" cy="60" r="52"/><circle className="ring-value" cx="60" cy="60" r="52" pathLength="100" strokeDasharray={String(Math.max(0,Math.min(100,summary?.completionPercent || 0))) + ' 100'}/></svg><strong>{summary ? percent(summary.completionPercent) : '—'}</strong></div><span>Verified project completion</span><small>{summary ? `${summary.completedTasks} of ${summary.taskCount} tasks verified` : 'Awaiting project report'}</small></div>
    </section>
    <section className="panel attention-panel" aria-label="Needs attention">
      <SectionHeader title="Needs attention" description="Your next actions, based on the work you can access." action={<Icon name="warning" size={18}/>}/>
      {attentionLoading ? <Loading label="Checking project priorities…"/> : <>
        {(pending.length > 0 || overdue.length > 0 || dueMilestones.length > 0) && <div className="attention-list">
          {pending.length > 0 && <AttentionItem icon="evidence" title={`${pending.length} ${pending.length === 1 ? 'submission' : 'submissions'} under review`} detail={user.role === 'faculty' ? 'Open the queue to record your decisions.' : 'Track feedback on submitted evidence.'} action={user.role === 'faculty' ? 'Review' : 'View'} onClick={() => navigate('evidence')}/>}
          {overdue.length > 0 && <AttentionItem icon="clock" title={`${overdue.length} overdue ${overdue.length === 1 ? 'task' : 'tasks'}`} detail="Check blockers and the next steps." action="Open tasks" tone="red" onClick={() => navigate('tasks')}/>}
          {dueMilestones.length > 0 && <AttentionItem icon="milestones" title={`${dueMilestones.length} ${dueMilestones.length === 1 ? 'milestone needs' : 'milestones need'} attention`} detail="Due within seven days or already overdue." action="View plan" tone="blue" onClick={() => navigate('milestones')}/>}
        </div>}
        {!pending.length && !overdue.length && !dueMilestones.length && !attentionUnavailable && <div className="attention-clear"><Icon name="check" size={18}/>No pending reviews, overdue tasks, or milestones due in the next seven days.</div>}
        {attentionUnavailable && <p className="fine-print">Some priorities could not be loaded. Use the retry actions below to refresh them.</p>}
      </>}
    </section>
    </div>

    {project.supervisionStatus && project.supervisionStatus !== 'Approved' && <div className="notice supervision"><Icon name="shield"/><div><b>Faculty supervision: {project.supervisionStatus}</b><p>Planning is available. Your guide’s approval is required before submitting project work.</p>{user.role === 'faculty' && project.supervisionStatus === 'Pending' && <div className="heading-actions"><button className="button small-button" disabled={supervisionBusy} onClick={() => void supervise(true)}>Approve supervision</button><button className="button secondary small-button" disabled={supervisionBusy} onClick={() => void supervise(false)}>Decline</button></div>}</div></div>}
    {supervisionError && <ErrorNotice message={supervisionError}/>}
    {report.error && <ErrorNotice message={report.error} retry={report.reload}/>}
    <div className="metric-grid overview-stats">
      <Metric label="Project completion" value={summary ? percent(summary.completionPercent) : '—'} detail="Based on faculty-verified tasks" icon="reports" tone="blue"/>
      <Metric label="Tasks completed" value={summary ? `${summary.completedTasks} / ${summary.taskCount}` : '—'} detail="Approved work across the project" icon="check" tone="green"/>
      <Metric label="Evidence pending" value={summary?.pendingReviews ?? '—'} detail="Submissions awaiting a decision" icon="evidence" tone="amber"/>
      <Metric label="Overdue tasks" value={tasks.data ? overdue.length : '—'} detail="Unfinished tasks in your task view" icon="clock" tone="red"/>
    </div>



    <div className="dashboard-grid">
      <section className="panel dashboard-tasks"><SectionHeader title="Up next" description={project.canManage ? 'Upcoming project work, ordered by due date.' : 'Your assigned work, ordered by due date.'} action={<button className="text-button" onClick={() => navigate('tasks')}>All tasks <Icon name="arrow" size={15}/></button>}/>
        {tasks.loading && !tasks.data ? <Loading/> : tasks.error ? <ErrorNotice message={tasks.error} retry={tasks.reload}/> : !actionable.length ? <Empty icon="tasks" title="You’re all caught up" detail="New planned work will appear here."/> : <div className="task-preview-list">{actionable.map((task,index) => <button className="task-preview" key={task.id} onClick={() => navigate('tasks')}><span className="task-order" aria-hidden="true">{String(index+1).padStart(2,'0')}</span><div><b>{task.name}</b><span>{task.assigneeName} · {date(task.dueDate)} · {task.points} points</span></div><Badge>{task.status}</Badge></button>)}</div>}
      </section>
      <section className="panel"><SectionHeader title="Evidence review queue" description="Submissions waiting for faculty feedback." action={<span className="count-badge">{evidence.data ? pending.length : '—'}</span>}/>
        {evidence.loading&&!evidence.data ? <Loading/> : evidence.error ? <ErrorNotice message={evidence.error} retry={evidence.reload}/> : !pending.length ? <Empty icon="evidence" title="No pending reviews" detail="Submitted work appears here for faculty review."/> : <div className="review-preview-list">{pending.slice(0,3).map(item => <div key={item.id} className="review-preview"><Avatar name={item.studentName} small/><div><b>{item.title}</b><span>{item.studentName} · v{item.version} · {date(item.submittedAt)}</span></div></div>)}<button className={`button ${user.role === 'faculty' ? '' : 'secondary'} full-width`} onClick={() => navigate('evidence')}>{user.role === 'faculty' ? 'Review submissions' : 'Open evidence workspace'}<Icon name="arrow" size={16}/></button></div>}
      </section>
      <section className="panel"><SectionHeader title="Project roadmap" description="The checkpoints that keep your team on track." action={<button className="text-button" onClick={() => navigate('milestones')}>View plan <Icon name="arrow" size={15}/></button>}/>
        {milestones.loading&&!milestones.data ? <Loading/> : milestones.error ? <ErrorNotice message={milestones.error} retry={milestones.reload}/> : !milestones.data?.milestones.length ? <Empty icon="milestones" title="No milestones planned" detail="Add checkpoints to break the project into manageable stages."/> : <div className="milestone-preview-list">{milestones.data.milestones.slice(0,3).map((milestone,index) => <div className="milestone-preview" key={milestone.id}><span className="step-number">{String(index+1).padStart(2,'0')}</span><div><b>{milestone.title}</b><span>Due {date(milestone.dueDate)}</span></div><Badge>{milestone.status}</Badge></div>)}</div>}
      </section>
      <section className="panel dashboard-activity"><SectionHeader title="Recent activity" description="A shared record of project decisions." action={<button className="text-button" onClick={() => navigate('activity')}>View activity <Icon name="arrow" size={15}/></button>}/>
        {activity.loading&&!activity.data ? <Loading/> : activity.error ? <ErrorNotice message={activity.error} retry={activity.reload}/> : !activity.data?.events.length ? <Empty icon="activity" title="Your story starts here" detail="Saved project activity will appear in this timeline."/> : <div className="activity-preview">{activity.data.events.slice(0,4).map(event => <div key={event.id}><span className="timeline-dot"/><div><p><b>{event.actorName}</b> {event.action.replaceAll('_',' ').toLowerCase()}</p><small>{date(event.createdAt,true)}</small></div></div>)}</div>}
      </section>
    </div>
    <div className="principle-banner"><Icon name="shield"/><div><b>Know what your contribution means.</b><span>Rubric score, assigned-work completion, and verified work share each measure a different part of your progress.</span></div><button className="text-button" onClick={() => navigate('reports')}>Explore your score <Icon name="arrow" size={16}/></button></div>
  </>;
}
