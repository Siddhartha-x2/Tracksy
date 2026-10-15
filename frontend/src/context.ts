import { createContext, useContext } from 'react';
import type { Member, Project, User } from './types';
export type Page = 'overview' | 'tasks' | 'evidence' | 'milestones' | 'logs' | 'peer' | 'team' | 'reports' | 'activity' | 'settings' | 'notifications' | 'admin';
export interface WorkspaceContext { user: User; project: Project; members: Member[]; revision: number; refresh: () => void; notify: (message: string) => void; navigate: (page: Page) => void }
export const Workspace = createContext<WorkspaceContext | null>(null);
export function useWorkspace() { const value = useContext(Workspace); if (!value) throw new Error('Project workspace is required'); return value; }
