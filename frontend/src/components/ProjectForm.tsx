import { api, formValues } from '../api';
import { useResource } from '../hooks/useResource';
import type { Project, User } from '../types';
import { ActionForm, ErrorNotice, Field, Loading } from './ui';
export default function ProjectForm({ user, project, onSaved, onCancel }: { user: User; project?: Project; onSaved: () => void; onCancel: () => void }) {
  const faculty = useResource<{ faculty: { id: number; name: string }[] }>(!project && user.role === 'student' ? '/faculty' : null);
  return <ActionForm onCancel={onCancel} submitLabel={project ? 'Save project' : 'Create workspace'} onSubmit={async submission => { const values = formValues(submission); await api(project ? `/projects/${project.id}` : '/projects', { method: project ? 'PATCH' : 'POST', body: { ...values, facultyId: values.facultyId ? Number(values.facultyId) : undefined } }); onSaved(); }}>
    <Field label="Project name"><input name="name" defaultValue={project?.name} required minLength={2} maxLength={120} placeholder="e.g. Campus Connect"/></Field>
    <Field label="Description"><textarea name="description" defaultValue={project?.description} maxLength={4000} placeholder="What is your team building?"/></Field>
    <Field label="Problem statement"><textarea name="problemStatement" defaultValue={project?.problemStatement} maxLength={4000} placeholder="Describe the problem and who it affects."/></Field>
    <div className="form-row"><Field label="Academic term"><input name="term" defaultValue={project?.term} maxLength={80} placeholder="e.g. Semester 6"/></Field><Field label="Technology stack"><input name="techStack" defaultValue={project?.techStack} maxLength={500} placeholder="React, Spring Boot, PostgreSQL"/></Field></div>
    <div className="form-row"><Field label="Start date"><input type="date" name="startDate" defaultValue={project?.startDate?.slice(0,10)}/></Field><Field label="Project deadline"><input type="date" name="deadline" defaultValue={project?.deadline?.slice(0,10)}/></Field></div>
    {!project && user.role === 'student' && <><Field label="Faculty guide" hint="Your guide must approve supervision before the team can submit work."><select name="facultyId" required defaultValue=""><option value="" disabled>Select a faculty guide</option>{faculty.data?.faculty.map(guide => <option key={guide.id} value={guide.id}>{guide.name}</option>)}</select></Field>{faculty.loading && <Loading label="Loading faculty guides…"/>}{faculty.error && <ErrorNotice message={faculty.error} retry={faculty.reload}/>}</>}
    {project && <Field label="Project status" hint="Completion is checked against approved work and milestones by the server."><select name="status" defaultValue={project.status}>{(user.role === 'faculty' ? ['Planning','Active','Completed','Archived'] : ['Planning','Active']).map(value => <option key={value}>{value}</option>)}</select></Field>}
  </ActionForm>;
}
