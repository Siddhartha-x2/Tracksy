package com.tracksy.workspace;

import com.tracksy.Access;
import com.tracksy.ApiException;
import com.tracksy.Db;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import static com.tracksy.workspace.WorkspaceController.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final Db db;
    private final Access access;
    public AdminController(Db db,Access access) { this.db=db; this.access=access; }
    public record UserState(Boolean enabled) {}
    private void admin() { if(!"admin".equals(access.user().role())) throw new ApiException(403,"Administrator access required"); }

    @GetMapping("/users")
    public Map<String,Object> users() {
        admin();
        return Map.of("users",dtos(db.query("SELECT u.id,u.name,u.email,u.role,u.enabled,u.created_at,count(m.project_id) FILTER(WHERE m.active) project_count FROM users u LEFT JOIN project_members m ON m.user_id=u.id GROUP BY u.id ORDER BY u.id")));
    }

    @PatchMapping("/users/{id}")
    @Transactional
    public Map<String,Object> changeUser(@PathVariable long id,@RequestBody UserState input,HttpServletRequest request) {
        admin(); if(input.enabled()==null) bad("enabled is required");
        // Lock every administrator in a stable order before target selection so concurrent disables cannot remove the last admin.
        var administrators=db.query("SELECT id,enabled FROM users WHERE role='admin' ORDER BY id FOR UPDATE");
        // Recheck after waiting for locks: another admin may have disabled the caller.
        admin(); long actorId=access.user().id();
        var rows=db.query("SELECT id,role,enabled FROM users WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ApiException(404,"User not found");
        var target=rows.getFirst();
        if(!input.enabled() && "admin".equals(target.get("role")) && Boolean.TRUE.equals(target.get("enabled")) && administrators.stream().filter(u->Boolean.TRUE.equals(u.get("enabled"))).count()<=1) conflict("The last enabled administrator cannot be disabled");
        access.audit(null,"User access changed","user",Long.toString(id),"Account enabled: "+input.enabled(),null);
        db.update("UPDATE users SET enabled=? WHERE id=?",input.enabled(),id);
        if(!input.enabled()) {
            access.invalidateSessions(id);
            if(id==actorId) {
                var session=request.getSession(false); if(session!=null) session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        return Map.of("user",Db.dto(db.one("SELECT id,name,email,role,enabled,created_at FROM users WHERE id=?",id)));
    }
}
