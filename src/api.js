async function request(path, options = {}) {
  const isForm = options.body instanceof FormData;
  const response = await fetch(`/api${path}`, {
    credentials: 'include',
    ...options,
    headers: {
      ...(isForm ? {} : { 'Content-Type': 'application/json' }),
      ...options.headers,
    },
  });

  if (response.status === 204) return null;
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || 'Request failed.');
  return payload;
}

const json = (body) => ({ method: 'POST', body: JSON.stringify(body) });

export const api = {
  me: () => request('/auth/me'),
  login: (email, password) => request('/auth/login', json({ email, password })),
  register: (name, email, password) => request('/auth/register', json({ name, email, password })),
  logout: () => request('/auth/logout', { method: 'POST' }),
  projects: () => request('/projects'),
  createProject: (project) => request('/projects', json(project)),
  joinProject: (joinCode) => request('/projects/join', json({ joinCode })),
  tasks: (projectId) => request(`/projects/${encodeURIComponent(projectId)}/tasks`),
  createTask: (projectId, task) => request(`/projects/${encodeURIComponent(projectId)}/tasks`, json(task)),
  updateTask: (taskId, status) => request(`/tasks/${encodeURIComponent(taskId)}`, { method: 'PATCH', body: JSON.stringify({ status }) }),
  evidence: (projectId) => request(`/projects/${encodeURIComponent(projectId)}/evidence`),
  submitEvidence: (projectId, taskId, form) => request(`/projects/${encodeURIComponent(projectId)}/tasks/${encodeURIComponent(taskId)}/evidence`, { method: 'POST', body: form }),
  resubmitEvidence: (evidenceId, form) => request(`/evidence/${encodeURIComponent(evidenceId)}/resubmit`, { method: 'POST', body: form }),
  reviewEvidence: (evidenceId, decision, feedback) => request(`/evidence/${encodeURIComponent(evidenceId)}/review`, json({ decision, feedback })),
  team: (projectId) => request(`/projects/${encodeURIComponent(projectId)}/team`),
  report: (projectId) => request(`/projects/${encodeURIComponent(projectId)}/report`),
};
