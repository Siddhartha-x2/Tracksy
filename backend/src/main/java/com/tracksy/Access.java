package com.tracksy;

import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class Access {
    public record User(long id, String name, String email, String role) {}
    private final Db db;
    public Access(Db db) { this.db=db; }
    public User user() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal()))
            throw new ApiException(401,"Sign in to continue.");
        var rows=db.query("SELECT id,name,email,role,enabled FROM users WHERE email=?",authentication.getName());
        if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled"))) throw new ApiException(401,"Your account is unavailable. Sign in again.");
        var u=rows.getFirst(); return new User(((Number)u.get("id")).longValue(),(String)u.get("name"),(String)u.get("email"),(String)u.get("role"));
    }
    public Map<String,Object> member(String projectId) {
        var rows=db.query("SELECT pm.*,p.faculty_owner_id,p.status AS project_status,p.supervision_status FROM project_members pm JOIN projects p ON p.id=pm.project_id WHERE pm.project_id=? AND pm.user_id=? AND pm.active=true",projectId,user().id());
        if(rows.isEmpty()) throw new ApiException(404,"Project not found.");
        return rows.getFirst();
    }
    public boolean canManage(Map<String,Object> membership) {
        return "leader".equals(membership.get("project_role")) || ((Number)membership.get("faculty_owner_id")).longValue()==user().id();
    }
    public Map<String,Object> manage(String projectId) {
        var membership=member(projectId);
        if(!canManage(membership)) throw new ApiException(403,"Only the project faculty guide or team leader can perform this action.");
        return membership;
    }
    public Map<String,Object> faculty(String projectId) {
        var membership=member(projectId); var u=user();
        if(!"faculty".equals(u.role()) || ((Number)membership.get("faculty_owner_id")).longValue()!=u.id()) throw new ApiException(403,"Only this project's faculty guide can perform this action.");
        return membership;
    }
    public Map<String,Object> student(String projectId) {
        var membership=member(projectId);
        if(!"student".equals(user().role())) throw new ApiException(403,"Student membership is required.");
        return membership;
    }
    public Map<String,Object> task(String taskId) {
        var task=db.one("SELECT * FROM tasks WHERE id=?",taskId); var membership=member((String)task.get("project_id"));
        if(((Number)task.get("assigned_to")).longValue()!=user().id() && !canManage(membership)) throw new ApiException(404,"Task not found.");
        return task;
    }
    public void writable(String projectId) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) db.one("SELECT id FROM projects WHERE id=? FOR UPDATE",projectId);
        var membership=member(projectId);
        if(!"Approved".equals(membership.get("supervision_status"))) throw new ApiException(409,"Your faculty guide must approve supervision before work can begin.");
        if("Completed".equals(membership.get("project_status")) || "Archived".equals(membership.get("project_status"))) throw new ApiException(409,"Reopen this project before changing its work.");
    }
    public void audit(String projectId,String action,String entityType,String entityId,String details,Long visibilityUserId) {
        db.update("INSERT INTO audit_events(project_id,actor_id,action,entity_type,entity_id,details,visibility_user_id) VALUES (?,?,?,?,?,?,?)",projectId,user().id(),action,entityType,entityId,details,visibilityUserId);
    }
    public void invalidateSessions(long userId) {
        db.update("DELETE FROM spring_session WHERE principal_name=(SELECT email FROM users WHERE id=?)",userId);
    }
}
