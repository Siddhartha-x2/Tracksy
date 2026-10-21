const INITIAL_DATA = {
  tasks: [
    { id: 'T101', name: 'Design the onboarding flow', assignee: 'Aarav Rao', due: '2026-10-08', priority: 'High', status: 'In progress' },
    { id: 'T102', name: 'Build event discovery cards', assignee: 'Mia Chen', due: '2026-10-10', priority: 'High', status: 'In progress' },
    { id: 'T103', name: 'Interview 5 club organizers', assignee: 'Aarav Rao', due: '2026-10-06', priority: 'Medium', status: 'To do' },
    { id: 'T104', name: 'Set up project repository', assignee: 'Noah Patel', due: '2026-10-04', priority: 'Low', status: 'Done' },
    { id: 'T105', name: 'Prepare the user research summary', assignee: 'Priya Shah', due: '2026-10-11', priority: 'Medium', status: 'Under review' },
    { id: 'T106', name: 'Create the visual design system', assignee: 'Leo Martin', due: '2026-10-12', priority: 'Medium', status: 'In progress' },
  ],
  evidence: [
    { id: 'E101', title: 'Repository setup and README', task: 'T104', by: 'Noah Patel', date: '2026-10-02', status: 'Approved', note: 'Project structure, README and contribution guide are in the repository.', feedback: '' },
    { id: 'E102', title: 'First onboarding wireframes', task: 'T101', by: 'Aarav Rao', date: '2026-10-03', status: 'Under review', note: 'Three initial screens for account setup and club selection.', feedback: '' },
    { id: 'E103', title: 'Organizer interview notes', task: 'T103', by: 'Aarav Rao', date: '2026-10-03', status: 'Under review', note: 'Notes from the first two organizer interviews.', feedback: '' },
    { id: 'E104', title: 'User research summary', task: 'T105', by: 'Priya Shah', date: '2026-10-01', status: 'Changes requested', note: 'Please add takeaways from the remaining interviews.', feedback: 'Please add takeaways from the remaining interviews.' },
  ],
};

const MEMBERS = ['Aarav Rao', 'Mia Chen', 'Noah Patel', 'Priya Shah', 'Leo Martin'];
const STORAGE_KEY = 'tracksy-v1';
const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);

let appState = loadState();
let activeRole = 'student';
let activePage = 'overview';
let modalAction = 'create-evidence';

function loadState() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
    if (saved && Array.isArray(saved.tasks) && Array.isArray(saved.evidence)) return saved;
  } catch (error) {
    console.warn('Tracksy could not read saved demo data; starting fresh.', error);
  }
  return structuredClone(INITIAL_DATA);
}

function saveState() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(appState));
  } catch (error) {
    showToast('Could not save changes in this browser.');
    console.error('Tracksy could not save demo data.', error);
  }
}

function nextId(prefix, collection) {
  const highest = collection.reduce((max, item) => Math.max(max, Number(item.id.slice(prefix.length)) || 0), 100);
  return `${prefix}${highest + 1}`;
}

function formatDate(value) {
  if (!value) return 'No date';
  const date = new Date(`${value}T12:00:00`);
  return Number.isNaN(date.valueOf()) ? 'No date' : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function statusBadge(status) {
  const color = ['Approved', 'Done'].includes(status) ? 'green' : ['Under review', 'In progress'].includes(status) ? 'amber' : status === 'Changes requested' ? 'red' : '';
  return `<span class="pill ${color}">${escapeHtml(status)}</span>`;
}

function visibleEvidence() {
  return activeRole === 'faculty' ? appState.evidence : appState.evidence.filter((item) => item.by === 'Aarav Rao');
}

function visibleTasks() {
  return activeRole === 'faculty' ? appState.tasks : appState.tasks.filter((item) => item.assignee === 'Aarav Rao');
}

function renderStats() {
  const visible = visibleTasks();
  const evidence = visibleEvidence();
  const done = appState.tasks.filter((task) => task.status === 'Done').length;
  const approved = evidence.filter((item) => item.status === 'Approved').length;
  const reviewCount = appState.evidence.filter((item) => item.status === 'Under review').length;
  const cards = [
    ['My tasks', visible.length, '☷', activeRole === 'faculty' ? 'Across the project' : 'Assigned to you'],
    ['Submitted evidence', evidence.length, '⌁', activeRole === 'faculty' ? 'Across your team' : 'Your submissions'],
    [activeRole === 'faculty' ? 'Awaiting review' : 'Approved evidence', activeRole === 'faculty' ? reviewCount : approved, '◷', activeRole === 'faculty' ? 'Ready for your review' : 'Verified by faculty'],
    ['Project progress', `${Math.round((done / Math.max(1, appState.tasks.length)) * 100)}%`, '↗', `${done} of ${appState.tasks.length} tasks complete`],
  ];
  $('#stats').innerHTML = cards.map(([label, value, icon, caption]) => `<article class="stat"><div class="stat-top"><span>${label}</span><span class="stat-icon">${icon}</span></div><strong>${value}</strong><div class="stat-foot"><em>${caption}</em></div></article>`).join('');
}

function renderOverview() {
  const done = appState.tasks.filter((task) => task.status === 'Done').length;
  const approved = visibleEvidence().filter((item) => item.status === 'Approved').length;
  const score = Math.min(100, Math.round((approved / 4) * 100));
  const tasks = visibleTasks();
  $('#navTasks').textContent = tasks.length;
  $('#taskFocus').innerHTML = tasks.slice(0, 4).map((task) => `<div class="task-row"><div class="task-meta"><span class="task-mark">${task.status === 'Done' ? '✓' : '↗'}</span><div><span class="task-name">${escapeHtml(task.name)}</span><span class="task-sub">${escapeHtml(task.assignee)} · ${escapeHtml(task.id)}</span></div></div><div class="task-end">${statusBadge(task.status)}<time>${formatDate(task.due)}</time></div></div>`).join('') || '<div class="empty">No tasks yet. Add one to get started.</div>';
  $('#scoreBlock').innerHTML = `<div class="score-card"><div class="score-flex"><div><div class="score-kicker">${activeRole === 'faculty' ? 'PROJECT VERIFIED CONTRIBUTION' : 'YOUR VERIFIED CONTRIBUTION'}</div><div class="score-number">${score}<span style="font-size:13px;color:#87918a"> / 100</span></div></div><div class="ring" style="--score:${score}"><b>${score}%</b></div></div><div class="progress-caption"><span>Verified work</span><span>${approved} of 4 milestones</span></div><div class="progress"><i style="width:${score}%"></i></div><p class="score-note">Your score grows when faculty approves evidence. Every point links back to real work.</p></div><button class="button secondary" style="width:100%" data-goto="reports">See full contribution report&nbsp; →</button>`;
  const activity = appState.evidence.slice().reverse().slice(0, 3);
  $('#activity').innerHTML = activity.map((item, index) => `<div class="activity-item"><span class="activity-dot">${item.status === 'Approved' ? '✓' : '↗'}</span><div class="activity-text"><b>${escapeHtml(item.by)}</b> ${item.status === 'Approved' ? 'got evidence approved' : 'submitted evidence'} for <b>${escapeHtml(appState.tasks.find((task) => task.id === item.task)?.name || item.task)}</b><small>${index === 0 ? 'Today' : formatDate(item.date)} · ${escapeHtml(item.status)}</small></div></div>`).join('') || '<div class="empty">Team updates will show here.</div>';
}

function renderTasks() {
  const query = ($('#taskSearch')?.value || '').toLowerCase();
  const filter = $('#taskFilter')?.value || 'all';
  const rows = visibleTasks().filter((task) => (filter === 'all' || task.status === filter) && `${task.name} ${task.assignee} ${task.id}`.toLowerCase().includes(query));
  $('#taskTable').innerHTML = rows.map((task) => `<tr><td><b>${escapeHtml(task.name)}</b><div class="muted" style="margin-top:4px">${escapeHtml(task.id)}</div></td><td>${escapeHtml(task.assignee)}</td><td>${formatDate(task.due)}</td><td>${statusBadge(task.priority)}</td><td>${statusBadge(task.status)}</td><td><button class="text-link" data-evidence-task="${escapeHtml(task.id)}">＋ Evidence</button></td></tr>`).join('') || '<tr><td colspan="6" class="empty">No tasks match that filter.</td></tr>';
}

function renderEvidence() {
  const query = ($('#evidenceSearch')?.value || '').toLowerCase();
  const filter = $('#evidenceFilter')?.value || 'all';
  const rows = visibleEvidence().filter((item) => (filter === 'all' || item.status === filter) && `${item.title} ${item.by} ${item.task}`.toLowerCase().includes(query));
  $('#evidenceTable').innerHTML = rows.map((item) => {
    const task = appState.tasks.find((entry) => entry.id === item.task);
    const review = activeRole === 'faculty' && item.status === 'Under review'
      ? `<button class="text-link" data-review="${escapeHtml(item.id)}">Review →</button>`
      : activeRole === 'student' && item.status === 'Changes requested'
        ? `<button class="text-link" data-resubmit="${escapeHtml(item.id)}">Update →</button>`
        : '<span class="muted">—</span>';
    const workLink = item.link ? `<a class="text-link" href="${escapeHtml(item.link)}" target="_blank" rel="noopener noreferrer">Open work ↗</a>` : '';
    const feedback = item.feedback ? `<div class="muted" style="margin-top:4px">Feedback: ${escapeHtml(item.feedback)}</div>` : '';
    return `<tr><td><b>${escapeHtml(item.title)}</b><div class="muted" style="margin-top:4px">${escapeHtml(item.id)} ${workLink}</div>${feedback}</td><td>${escapeHtml(task?.name || item.task)}</td><td>${escapeHtml(item.by)}</td><td>${formatDate(item.date)}</td><td>${statusBadge(item.status)}</td><td>${review}</td></tr>`;
  }).join('') || '<tr><td colspan="6" class="empty">No evidence matches that filter.</td></tr>';
}

function renderTeam() {
  const colors = ['#e8f0eb', '#f1e8f6', '#e7eff7', '#faf0dd', '#f8e9e7'];
  const roles = ['Product design', 'Frontend development', 'Backend development', 'Research', 'Visual design'];
  $('#teamList').innerHTML = MEMBERS.map((name, index) => {
    const count = appState.evidence.filter((item) => item.by === name && item.status === 'Approved').length;
    const memberRole = index === 0 ? statusBadge('Student') : index === 3 ? statusBadge('Faculty') : '';
    return `<div class="task-row"><div class="task-meta"><span class="avatar" style="background:${colors[index]}">${name.split(' ').map((part) => part[0]).join('')}</span><div><span class="task-name">${name}${index === 0 ? ' · You' : ''}</span><span class="task-sub">${roles[index]}</span></div></div><div class="task-end"><span class="muted">${count} verified ${count === 1 ? 'submission' : 'submissions'}</span>${memberRole}</div></div>`;
  }).join('');
}

function renderReports() {
  const totals = MEMBERS.map((name) => ({ name, count: appState.evidence.filter((item) => item.by === name && item.status === 'Approved').length }));
  const largest = Math.max(1, ...totals.map((item) => item.count));
  const approvedCount = appState.evidence.filter((item) => item.status === 'Approved').length;
  const completedCount = appState.tasks.filter((task) => task.status === 'Done').length;
  const pendingCount = appState.evidence.filter((item) => item.status === 'Under review').length;
  $('#reportStats').innerHTML = [
    ['Verified submissions', approvedCount, '✓', 'Approved by faculty'],
    ['Project completion', `${Math.round((completedCount / Math.max(1, appState.tasks.length)) * 100)}%`, '↗', `${completedCount} tasks completed`],
    ['Pending reviews', pendingCount, '◷', 'Evidence awaiting faculty'],
    ['Team members', MEMBERS.length, '♧', 'Active on Campus Connect'],
  ].map(([label, value, icon, caption]) => `<article class="stat"><div class="stat-top"><span>${label}</span><span class="stat-icon">${icon}</span></div><strong>${value}</strong><div class="stat-foot">${caption}</div></article>`).join('');
  $('#reportList').innerHTML = totals.map((item) => `<div class="task-row"><div class="task-meta"><span class="avatar">${item.name.split(' ').map((part) => part[0]).join('')}</span><div><span class="task-name">${item.name}${item.name === 'Aarav Rao' ? ' · You' : ''}</span><span class="task-sub">${item.count} verified ${item.count === 1 ? 'contribution' : 'contributions'}</span></div></div><div class="task-end" style="width:40%"><div class="progress" style="flex:1"><i style="width:${Math.round((item.count / largest) * 100)}%"></i></div><b style="min-width:34px;text-align:right">${Math.round((item.count / Math.max(1, approvedCount)) * 100)}%</b></div></div>`).join('');
}

function render() {
  const now = new Date();
  $('#today').textContent = now.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
  $('#welcomeTitle').textContent = `${now.getHours() < 12 ? 'Good morning' : now.getHours() < 18 ? 'Good afternoon' : 'Good evening'}, ${activeRole === 'faculty' ? 'Dr. Kim' : 'Aarav'} 👋`;
  $('#profileName').textContent = activeRole === 'faculty' ? 'Dr. Jamie Kim' : 'Aarav Rao';
  $('#profileRole').textContent = activeRole === 'faculty' ? 'Faculty · Project guide' : 'Student';
  $('#avatar').textContent = activeRole === 'faculty' ? 'JK' : 'AR';
  $('#primaryAction').textContent = activeRole === 'faculty' ? '＋ Create task' : '＋ Submit evidence';
  $('#primaryAction').onclick = () => activeRole === 'faculty' ? openTaskForm() : openEvidenceForm();
  renderStats();
  renderOverview();
  renderTasks();
  renderEvidence();
  renderTeam();
  renderReports();
}

function navigate(page) {
  activePage = page;
  $$('.section-page').forEach((section) => section.classList.toggle('visible', section.id === `${page}Page`));
  $$('.nav button').forEach((button) => button.classList.toggle('active', button.dataset.page === page));
  $('#crumbLabel').textContent = ({ overview: 'Overview', tasks: 'My tasks', evidence: 'Evidence', team: 'Team', reports: 'Reports' })[page] || page;
}

function openModal(title, body) {
  $('#modalTitle').textContent = title;
  $('#modalBody').innerHTML = body;
  $('#modalBackdrop').classList.add('open');
  setTimeout(() => $('#modalBody input')?.focus(), 50);
}

function openTaskForm() {
  modalAction = 'create-task';
  $('#saveModal').textContent = 'Create task';
  const minDate = new Date().toISOString().slice(0, 10);
  openModal('Create a task', `<div class="field"><label for="itemTitle">Task name</label><input id="itemTitle" maxlength="100" placeholder="What needs to get done?" required></div><div class="field"><label for="itemAssignee">Assign to</label><select id="itemAssignee">${MEMBERS.filter((name) => name !== 'Priya Shah').map((name) => `<option>${name}</option>`).join('')}</select></div><div style="display:grid;grid-template-columns:1fr 1fr;gap:10px"><div class="field"><label for="itemDue">Due date</label><input type="date" id="itemDue" min="${minDate}" value="${minDate}"></div><div class="field"><label for="itemPriority">Priority</label><select id="itemPriority"><option>Medium</option><option>High</option><option>Low</option></select></div></div>`);
}

function openEvidenceForm(taskId = '', evidenceId = '') {
  const existing = appState.evidence.find((item) => item.id === evidenceId);
  const taskOptions = appState.tasks.filter((task) => !taskId || task.id === taskId || task.id === existing?.task);
  modalAction = existing ? `update-evidence:${existing.id}` : 'create-evidence';
  $('#saveModal').textContent = existing ? 'Resubmit evidence' : 'Submit evidence';
  openModal(existing ? 'Update your evidence' : 'Submit evidence', `<div class="field"><label for="itemTitle">What did you work on?</label><input id="itemTitle" maxlength="100" placeholder="e.g. First onboarding wireframes" value="${escapeHtml(existing?.title || '')}" required></div><div class="field"><label for="itemTask">Related task</label><select id="itemTask" required>${taskOptions.map((task) => `<option value="${escapeHtml(task.id)}" ${(task.id === (existing?.task || taskId)) ? 'selected' : ''}>${escapeHtml(task.name)}</option>`).join('')}</select></div><div class="field"><label for="itemNote">Describe the contribution</label><textarea id="itemNote" maxlength="1000" placeholder="Describe the work you completed…">${escapeHtml(existing?.note || '')}</textarea></div><div class="field"><label for="itemLink">Link to your work (optional)</label><input type="url" id="itemLink" maxlength="500" placeholder="https://…" value="${escapeHtml(existing?.link || '')}"></div>`);
}

function showToast(message) {
  const toast = $('#toast');
  if (!toast) return;
  toast.textContent = message;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 2600);
}

function closeModal() {
  $('#modalBackdrop').classList.remove('open');
  const actions = $('#modalBackdrop .modal-actions');
  actions.querySelectorAll('[data-reject]').forEach((button) => button.remove());
  $('#saveModal').textContent = 'Save';
  $('#saveModal').onclick = null;
}

function finishChange(message) {
  saveState();
  render();
  closeModal();
  if (message) showToast(message);
}

function openReview(evidenceId) {
  const item = appState.evidence.find((entry) => entry.id === evidenceId);
  if (!item) return;
  modalAction = `review:${item.id}`;
  openModal('Review submission', `<div style="background:#f6f8f5;border-radius:12px;padding:14px;margin-bottom:15px"><div class="eyebrow">${escapeHtml(item.id)} · ${escapeHtml(item.by)}</div><h3 style="font:700 15px Manrope;margin:9px 0 5px">${escapeHtml(item.title)}</h3><div class="muted" style="font-size:11px">${escapeHtml(appState.tasks.find((task) => task.id === item.task)?.name || item.task)} · ${formatDate(item.date)}</div><p style="font-size:12px;line-height:1.6;margin:13px 0 0">${escapeHtml(item.note)}</p>${item.link ? `<p><a class="text-link" href="${escapeHtml(item.link)}" target="_blank" rel="noopener noreferrer">Open submitted work ↗</a></p>` : ''}</div><div class="field"><label for="reviewNote">Feedback for student</label><textarea id="reviewNote" maxlength="1000" placeholder="Share a note about this submission…">${escapeHtml(item.feedback || '')}</textarea></div>`);
  $('#saveModal').textContent = 'Approve';
  $('#saveModal').onclick = () => saveReview(item.id, 'Approved');
  const requestChanges = document.createElement('button');
  requestChanges.type = 'button';
  requestChanges.dataset.reject = 'true';
  requestChanges.className = 'button secondary';
  requestChanges.textContent = 'Request changes';
  requestChanges.onclick = () => saveReview(item.id, 'Changes requested');
  $('#modalBackdrop .modal-actions').insertBefore(requestChanges, $('#saveModal'));
}

function saveReview(evidenceId, status) {
  const item = appState.evidence.find((entry) => entry.id === evidenceId);
  if (!item) return;
  item.status = status;
  item.feedback = $('#reviewNote').value.trim();
  const task = appState.tasks.find((entry) => entry.id === item.task);
  if (task) task.status = status === 'Approved' ? 'Done' : 'In progress';
  finishChange(status === 'Approved' ? 'Evidence approved. Contribution score updated.' : 'Changes requested. Feedback is ready for the student.');
}

function saveForm() {
  if (modalAction.startsWith('review:')) return;
  const title = $('#itemTitle').value.trim();
  if (!title) {
    $('#itemTitle').focus();
    showToast('Add a name to continue.');
    return;
  }

  if (modalAction === 'create-task') {
    appState.tasks.unshift({
      id: nextId('T', appState.tasks), name: title, assignee: $('#itemAssignee').value,
      due: $('#itemDue').value, priority: $('#itemPriority').value, status: 'To do',
    });
    finishChange('Task created. Your team is ready to get moving.');
    return;
  }

  const taskId = $('#itemTask').value;
  const link = $('#itemLink').value.trim();
  if (link) {
    try {
      const url = new URL(link);
      if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Unsupported link type');
    } catch {
      $('#itemLink').focus();
      showToast('Enter a valid http or https link.');
      return;
    }
  }

  if (modalAction.startsWith('update-evidence:')) {
    const id = modalAction.split(':')[1];
    const item = appState.evidence.find((entry) => entry.id === id);
    if (!item) return;
    item.title = title;
    item.task = taskId;
    item.note = $('#itemNote').value.trim();
    item.link = link;
    item.date = new Date().toISOString().slice(0, 10);
    item.status = 'Under review';
    item.feedback = '';
    finishChange('Evidence updated and sent back for review.');
    return;
  }

  appState.evidence.unshift({
    id: nextId('E', appState.evidence), title, task: taskId, by: 'Aarav Rao',
    date: new Date().toISOString().slice(0, 10), status: 'Under review',
    note: $('#itemNote').value.trim(), link, feedback: '',
  });
  const task = appState.tasks.find((entry) => entry.id === taskId);
  if (task && task.status === 'To do') task.status = 'Under review';
  finishChange('Evidence submitted for faculty review.');
}

function exportReport() {
  const rows = [
    ['Member', 'Approved contributions', 'Share (%)'],
    ...MEMBERS.map((name) => {
      const count = appState.evidence.filter((item) => item.by === name && item.status === 'Approved').length;
      const total = appState.evidence.filter((item) => item.status === 'Approved').length;
      return [name, count, Math.round((count / Math.max(1, total)) * 100)];
    }),
  ];
  const csv = rows.map((row) => row.map((value) => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\r\n');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'tracksy-contribution-report.csv';
  link.click();
  URL.revokeObjectURL(url);
  showToast('Contribution report exported.');
}

$$('.nav button').forEach((button) => button.addEventListener('click', () => navigate(button.dataset.page)));
$('#roleSelect').addEventListener('change', (event) => {
  activeRole = event.target.value;
  render();
  navigate(activePage);
  showToast(activeRole === 'faculty' ? 'Faculty view' : 'Student view');
});
$('#newEvidence').addEventListener('click', () => openEvidenceForm());
$('#newTask').addEventListener('click', openTaskForm);
$('#taskSearch').addEventListener('input', renderTasks);
$('#taskFilter').addEventListener('change', renderTasks);
$('#evidenceSearch').addEventListener('input', renderEvidence);
$('#evidenceFilter').addEventListener('change', renderEvidence);
$('#saveModal').addEventListener('click', saveForm);
$('#closeModal').addEventListener('click', closeModal);
$('#cancelModal').addEventListener('click', closeModal);
$('#modalBackdrop').addEventListener('click', (event) => {
  if (event.target === $('#modalBackdrop')) closeModal();
});
$('#exportReport').addEventListener('click', exportReport);
document.body.addEventListener('click', (event) => {
  const goto = event.target.closest('[data-goto]');
  const evidenceTask = event.target.closest('[data-evidence-task]');
  const review = event.target.closest('[data-review]');
  const resubmit = event.target.closest('[data-resubmit]');
  if (goto) navigate(goto.dataset.goto);
  if (evidenceTask) openEvidenceForm(evidenceTask.dataset.evidenceTask);
  if (review) openReview(review.dataset.review);
  if (resubmit) openEvidenceForm('', resubmit.dataset.resubmit);
});

render();
