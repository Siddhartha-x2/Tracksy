package com.tracksy;

import java.util.*;
import java.time.LocalDate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProjectsController {
    private final Db db; private final Access access;
    public ProjectsController(Db db,Access access) { this.db=db; this.access=access; }
    @GetMapping("/faculty") Map<String,Object> facultyList() { access.user(); return Map.of("faculty",db.query("SELECT id,name FROM users WHERE role='faculty' AND enabled ORDER BY name")); }
    @GetMapping("/projects") Map<String,Object> projects() {
        var user=access.user();
        var rows=db.query("SELECT p.*,pm.project_role AS my_project_role,(SELECT count(*) FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=p.id AND m.active AND u.enabled) AS member_count,(SELECT count(*) FROM tasks t WHERE t.project_id=p.id) AS task_count FROM projects p JOIN project_members pm ON pm.project_id=p.id WHERE pm.user_id=? AND pm.active ORDER BY p.created_at DESC",user.id());
        return Map.of("projects",rows.stream().map(row -> {
            var out=Db.dto(row); boolean manage="leader".equals(row.get("my_project_role")) || ((Number)row.get("faculty_owner_id")).longValue()==user.id();
            out.put("canManage",manage); if(!manage) out.remove("joinCode"); return out;
        }).toList());
    }
    @PostMapping("/projects") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Transactional Map<String,Object> create(@RequestBody Map<String,Object> body) {
        var user=access.user(); if(!List.of("faculty","student").contains(user.role())) throw new ApiException(403,"Student or faculty access is required.");
        long faculty=user.role().equals("faculty")?user.id():Input.number(body,"facultyId",1,Long.MAX_VALUE);
        if(db.count("SELECT count(*) FROM users WHERE id=? AND role='faculty' AND enabled",faculty)!=1) throw new ApiException(400,"Choose an active faculty guide.");
        String id=Input.id(),name=Input.text(body,"name",2,120); var dates=dates(body);
        db.update("INSERT INTO projects(id,name,description,term,problem_statement,tech_stack,start_date,deadline,join_code,faculty_owner_id,supervision_status) VALUES (?,?,?,?,?,?,?,?,?,?,?)",id,name,Input.text(body,"description",0,4000),Input.text(body,"term",0,100),Input.text(body,"problemStatement",0,4000),Input.text(body,"techStack",0,1000),dates[0],dates[1],UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT),faculty,user.role().equals("faculty")?"Approved":"Pending");
        db.update("INSERT INTO project_members(project_id,user_id,project_role) VALUES (?,?,'faculty')",id,faculty);
        if(user.id()!=faculty) db.update("INSERT INTO project_members(project_id,user_id,project_role) VALUES (?,?,'leader')",id,user.id());
        defaultRubric(id,faculty);
        access.audit(id,"Project created","project",id,"Project created with "+(user.role().equals("faculty")?"approved":"pending")+" faculty supervision.",null);
        if(user.id()!=faculty) notify(faculty,id,"Supervision request",user.name()+" created "+name+". Review the project supervision request.");
        return Map.of("project",projectDto(id));
    }
    private LocalDate[] dates(Map<String,Object> body) {
        var start=Input.date(body,"startDate"); var deadline=Input.date(body,"deadline");
        if(start!=null && deadline!=null && deadline.isBefore(start)) throw new ApiException(400,"Deadline must be on or after the start date.");
        return new LocalDate[]{start,deadline};
    }
    public void defaultRubric(String project,long faculty) {
        db.update("INSERT INTO score_rubrics(project_id,version,tasks,evidence,consistency,peer,repository,documentation,milestones,created_by) VALUES (?,1,40,20,15,10,0,10,5,?) ON CONFLICT(project_id,version) DO NOTHING",project,faculty);
    }
    private Map<String,Object> projectDto(String id) {
        var row=db.one("SELECT p.*,pm.project_role AS my_project_role,(SELECT count(*) FROM project_members WHERE project_id=p.id AND active) member_count,(SELECT count(*) FROM tasks WHERE project_id=p.id) task_count FROM projects p JOIN project_members pm ON pm.project_id=p.id AND pm.user_id=? WHERE p.id=?",access.user().id(),id);
        var out=Db.dto(row); out.put("canManage",access.canManage(access.member(id))); return out;
    }
    @PostMapping("/projects/{id}/supervision") @Transactional Map<String,Object> supervision(@PathVariable String id,@RequestBody Map<String,Object> body) {
        access.faculty(id); var row=db.one("SELECT * FROM projects WHERE id=? FOR UPDATE",id);
        if(!"Pending".equals(row.get("supervision_status"))) throw new ApiException(409,"This supervision request has already been decided.");
        boolean accept=Input.bool(body,"accept"); db.update("UPDATE projects SET supervision_status=?,updated_at=now() WHERE id=?",accept?"Approved":"Declined",id);
        access.audit(id,accept?"Supervision approved":"Supervision declined","project",id,"Faculty supervision decision recorded.",null);
        db.query("SELECT user_id FROM project_members WHERE project_id=? AND project_role='leader'",id).forEach(m -> notify(((Number)m.get("user_id")).longValue(),id,"Supervision decision",accept?"Faculty approved your project. Work can begin.":"Faculty declined supervision. Contact your guide."));
        return Map.of("project",projectDto(id));
    }
    @PatchMapping("/projects/{id}") @Transactional Map<String,Object> update(@PathVariable String id,@RequestBody Map<String,Object> body) {
        access.manage(id); var current=db.one("SELECT * FROM projects WHERE id=? FOR UPDATE",id); access.manage(id);
        var merged=Db.dto(current); merged.putAll(body); var dates=dates(merged);
        String oldStart=current.get("start_date")==null?null:current.get("start_date").toString(),oldDeadline=current.get("deadline")==null?null:current.get("deadline").toString();
        boolean startChanged=!Objects.equals(oldStart,dates[0]==null?null:dates[0].toString()),deadlineChanged=!Objects.equals(oldDeadline,dates[1]==null?null:dates[1].toString());
        if(startChanged || deadlineChanged) {
            long work=db.count("SELECT count(*) FROM tasks WHERE project_id=?",id)+db.count("SELECT count(*) FROM weekly_logs WHERE project_id=?",id);
            if(work>0) access.faculty(id);
            if(startChanged && (db.count("SELECT count(*) FROM weekly_logs WHERE project_id=?",id)>0 || db.count("SELECT count(*) FROM evidence e JOIN tasks t ON t.id=e.task_id WHERE t.project_id=?",id)>0)) throw new ApiException(409,"The assessment start date is frozen once evidence or weekly logs exist.");
        }
        String status=Input.choice(merged,"status","Planning","Planning","Active","Completed","Archived");
        if(!status.equals(current.get("status"))) {
            if(List.of("Completed","Archived").contains(status) || List.of("Completed","Archived").contains(current.get("status"))) access.faculty(id);
            if(!"Approved".equals(current.get("supervision_status"))) throw new ApiException(409,"Faculty supervision must be approved before changing project status.");
            if(status.equals("Completed")) {
                if(db.count("SELECT count(*) FROM tasks WHERE project_id=?",id)==0 || db.count("SELECT count(*) FROM tasks t WHERE t.project_id=? AND (t.status<>'Done' OR NOT EXISTS(SELECT 1 FROM evidence e WHERE e.task_id=t.id AND e.status='Approved'))",id)>0)
                    throw new ApiException(409,"Complete and approve evidence for every task before completing the project.");
                if(db.count("SELECT count(*) FROM milestones WHERE project_id=? AND status<>'Completed'",id)>0) throw new ApiException(409,"Faculty must approve all milestones before completing this project.");
            }
        }
        if(dates[1]!=null && db.count("SELECT count(*) FROM tasks WHERE project_id=? AND due_date>?",id,dates[1])>0) throw new ApiException(409,"The deadline cannot precede an existing task due date.");
        if(dates[0]!=null && db.count("SELECT count(*) FROM tasks WHERE project_id=? AND due_date<?",id,dates[0])>0) throw new ApiException(409,"The start date cannot follow an existing task due date.");
        if(dates[1]!=null && db.count("SELECT count(*) FROM milestones WHERE project_id=? AND due_date>?",id,dates[1])>0) throw new ApiException(409,"The deadline cannot precede an existing milestone.");
        if(dates[0]!=null && db.count("SELECT count(*) FROM milestones WHERE project_id=? AND due_date<?",id,dates[0])>0) throw new ApiException(409,"The start date cannot follow an existing milestone.");
        db.update("UPDATE projects SET name=?,description=?,term=?,problem_statement=?,tech_stack=?,start_date=?,deadline=?,status=?,assessment_closed_at=CASE WHEN ? IN ('Completed','Archived') THEN COALESCE(assessment_closed_at,now()) ELSE NULL END,updated_at=now() WHERE id=?",Input.text(merged,"name",2,120),Input.text(merged,"description",0,4000),Input.text(merged,"term",0,100),Input.text(merged,"problemStatement",0,4000),Input.text(merged,"techStack",0,1000),dates[0],dates[1],status,status,id);
        access.audit(id,"Project updated","project",id,"Lifecycle: "+current.get("status")+" → "+status+". Project planning was updated.",null); return Map.of("project",projectDto(id));
    }
    @GetMapping("/projects/{id}/team") Map<String,Object> team(@PathVariable String id) {
        var membership=access.member(id); boolean manager=access.canManage(membership);
        var members=db.query("SELECT u.id,u.name,u.role,u.email,u.enabled,pm.project_role,pm.responsibility,pm.active,pm.joined_at FROM project_members pm JOIN users u ON u.id=pm.user_id WHERE pm.project_id=? "+(manager?"":"AND pm.active ")+"ORDER BY pm.project_role,u.name",id)
            .stream().map(row -> { var dto=Db.dto(row); if(!manager) dto.remove("email"); return dto; }).toList();
        return Map.of("members",members);
    }
    @PatchMapping("/projects/{id}/members/{userId}") @Transactional Map<String,Object> memberUpdate(@PathVariable String id,@PathVariable long userId,@RequestBody Map<String,Object> body) {
        access.manage(id); access.writable(id); access.manage(id); db.one("SELECT id FROM projects WHERE id=? FOR UPDATE",id);
        var member=db.one("SELECT pm.*,u.role,u.enabled FROM project_members pm JOIN users u ON u.id=pm.user_id WHERE pm.project_id=? AND pm.user_id=? FOR UPDATE OF pm",id,userId);
        if("faculty".equals(member.get("project_role"))) throw new ApiException(409,"The supervising faculty membership cannot be removed or changed.");
        boolean active=body.containsKey("active")?Input.bool(body,"active"):Boolean.TRUE.equals(member.get("active"));
        String role=body.containsKey("projectRole")?Input.choice(body,"projectRole","member","member","leader"):(String)member.get("project_role");
        if(active && !Boolean.TRUE.equals(member.get("enabled"))) throw new ApiException(409,"Enable this account before reactivating membership.");
        if(!active && db.count("SELECT count(*) FROM tasks WHERE project_id=? AND assigned_to=? AND status<>'Done'",id,userId)>0) throw new ApiException(409,"Reassign unfinished tasks before deactivating this member.");
        if(!active && "leader".equals(member.get("project_role"))) throw new ApiException(409,"Transfer leadership before deactivating the team leader.");
        if(role.equals("leader")) {
            if(!active || !"student".equals(member.get("role"))) throw new ApiException(400,"A team leader must be an active student.");
            access.faculty(id); db.update("UPDATE project_members SET project_role='member',updated_at=now() WHERE project_id=? AND project_role='leader' AND user_id<>?",id,userId);
        }
        if("leader".equals(member.get("project_role")) && role.equals("member")) access.faculty(id);
        String responsibility=body.containsKey("responsibility")?Input.text(body,"responsibility",0,1000):(String)member.get("responsibility");
        db.update("UPDATE project_members SET project_role=?,active=?,responsibility=?,deactivated_at=CASE WHEN ? THEN NULL ELSE COALESCE(deactivated_at,now()) END,updated_at=now() WHERE project_id=? AND user_id=?",role,active,responsibility,active,id,userId);
        access.audit(id,"Membership updated","member",Long.toString(userId),"Membership activity, responsibilities, or leadership updated.",null); return team(id);
    }
    @PostMapping("/projects/join") @Transactional Map<String,Object> requestJoin(@RequestBody Map<String,Object> body) {
        var user=access.user(); if(!user.role().equals("student")) throw new ApiException(403,"Only students can request project membership.");
        String code=Input.text(body,"joinCode",4,32).toUpperCase(Locale.ROOT);
        var project=db.one("SELECT * FROM projects WHERE join_code=? FOR UPDATE",code); String id=(String)project.get("id");
        if(!"Approved".equals(project.get("supervision_status")) || List.of("Completed","Archived").contains(project.get("status"))) throw new ApiException(409,"This project is not accepting join requests.");
        if(db.count("SELECT count(*) FROM project_members WHERE project_id=? AND user_id=? AND active",id,user.id())>0) throw new ApiException(409,"You are already a member of this project.");
        var existing=db.query("SELECT status FROM join_requests WHERE project_id=? AND user_id=?",id,user.id());
        if(!existing.isEmpty() && "Pending".equals(existing.getFirst().get("status"))) throw new ApiException(409,"Your membership request is already pending.");
        String requestId=Input.id();
        db.update("INSERT INTO join_requests(id,project_id,user_id) VALUES (?,?,?) ON CONFLICT(project_id,user_id) DO UPDATE SET status='Pending',decided_by=NULL,decided_at=NULL,created_at=now()",requestId,id,user.id());
        notify(((Number)project.get("faculty_owner_id")).longValue(),id,"Join request",user.name()+" requested project membership.");
        return Map.of("request",Db.dto(db.one("SELECT * FROM join_requests WHERE project_id=? AND user_id=?",id,user.id())),"message","Your join request is waiting for faculty or team leader approval.");
    }
    @GetMapping("/invitations") Map<String,Object> ownInvitations() {
        var u=access.user(); expire();
        return Map.of("invitations",dtos(db.query("SELECT i.*,p.name AS project_name FROM invitations i JOIN projects p ON p.id=i.project_id WHERE lower(i.email)=? ORDER BY i.created_at DESC",u.email())),"joinRequests",dtos(db.query("SELECT r.*,p.name AS project_name FROM join_requests r JOIN projects p ON p.id=r.project_id WHERE r.user_id=? ORDER BY r.created_at DESC",u.id())));
    }
    @GetMapping("/projects/{id}/invitations") Map<String,Object> projectInvitations(@PathVariable String id) {
        access.manage(id); expire();
        return Map.of("invitations",dtos(db.query("SELECT * FROM invitations WHERE project_id=? ORDER BY created_at DESC",id)),"joinRequests",dtos(db.query("SELECT r.*,u.name,u.email FROM join_requests r JOIN users u ON u.id=r.user_id WHERE r.project_id=? ORDER BY r.created_at DESC",id)));
    }
    @PostMapping("/projects/{id}/invitations") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Transactional Map<String,Object> invite(@PathVariable String id,@RequestBody Map<String,Object> body) {
        access.manage(id); access.writable(id); access.manage(id); expire(); String email=Input.email(body,"email");
        var rows=db.query("SELECT id,role,enabled FROM users WHERE email=?",email);
        if(!rows.isEmpty() && (!"student".equals(rows.getFirst().get("role")) || !Boolean.TRUE.equals(rows.getFirst().get("enabled")))) throw new ApiException(400,"Invite an active student account.");
        if(!rows.isEmpty() && db.count("SELECT count(*) FROM project_members WHERE project_id=? AND user_id=? AND active",id,rows.getFirst().get("id"))>0) throw new ApiException(409,"This student is already a member.");
        String inviteId=UUID.randomUUID().toString()+UUID.randomUUID().toString().replace("-","").substring(0,16);
        db.update("INSERT INTO invitations(id,project_id,email,invited_by,expires_at) VALUES (?,?,?,?,now()+interval '7 days')",inviteId,id,email,access.user().id());
        if(!rows.isEmpty()) notify(((Number)rows.getFirst().get("id")).longValue(),id,"Project invitation","You have a new project invitation. Open Invitations to accept or decline within seven days.");
        access.audit(id,"Invitation created","invitation",inviteId,"An email-bound seven-day invitation was created.",null);
        return Map.of("invitation",Db.dto(db.one("SELECT * FROM invitations WHERE id=?",inviteId)));
    }
    @PostMapping("/invitations/{id}/respond") @Transactional Map<String,Object> respond(@PathVariable String id,@RequestBody Map<String,Object> body) {
        var user=access.user(); if(!user.role().equals("student")) throw new ApiException(403,"Only students can accept project invitations.");
        var initial=db.one("SELECT * FROM invitations WHERE id=? AND lower(email)=?",id,user.email());
        String project=(String)initial.get("project_id");
        var projectRow=db.one("SELECT * FROM projects WHERE id=? FOR UPDATE",project);
        var invitation=db.one("SELECT * FROM invitations WHERE id=? AND lower(email)=? FOR UPDATE",id,user.email());
        if(!"Pending".equals(invitation.get("status")) || db.count("SELECT count(*) FROM invitations WHERE id=? AND expires_at>now()",id)!=1) throw new ApiException(409,"This invitation has expired or already been used.");
        boolean accept=Input.bool(body,"accept");
        if(!"Approved".equals(projectRow.get("supervision_status")) || List.of("Completed","Archived").contains(projectRow.get("status"))) throw new ApiException(409,"This project is not accepting memberships.");
        if(accept) addMember(project,user.id());
        db.update("UPDATE invitations SET status=?,responded_at=now() WHERE id=?",accept?"Accepted":"Declined",id);
        access.audit(project,accept?"Invitation accepted":"Invitation declined","invitation",id,"Student responded to the invitation.",user.id());
        return Map.of("accepted",accept,"projectId",project);
    }
    @PostMapping("/projects/{id}/join-requests/{requestId}/decision") @Transactional Map<String,Object> joinDecision(@PathVariable String id,@PathVariable String requestId,@RequestBody Map<String,Object> body) {
        access.manage(id); access.writable(id); access.manage(id); var request=db.one("SELECT * FROM join_requests WHERE id=? AND project_id=? FOR UPDATE",requestId,id);
        if(!"Pending".equals(request.get("status"))) throw new ApiException(409,"This membership request already has a decision.");
        boolean accept=Input.bool(body,"accept"); long student=((Number)request.get("user_id")).longValue(); if(accept) addMember(id,student);
        db.update("UPDATE join_requests SET status=?,decided_by=?,decided_at=now() WHERE id=?",accept?"Accepted":"Declined",access.user().id(),requestId);
        access.audit(id,"Join request decided","join-request",requestId,accept?"Membership approved.":"Membership declined.",student);
        notify(student,id,"Membership decision",accept?"Your project membership was approved.":"Your project membership request was declined."); return Map.of("accepted",accept);
    }
    private void addMember(String project,long user) {
        if(db.count("SELECT count(*) FROM users WHERE id=? AND role='student' AND enabled",user)!=1) throw new ApiException(409,"The student account is not available.");
        db.update("INSERT INTO project_members(project_id,user_id) VALUES (?,?) ON CONFLICT(project_id,user_id) DO UPDATE SET active=true,project_role='member',deactivated_at=NULL,updated_at=now()",project,user);
    }
    private void expire() { db.update("UPDATE invitations SET status='Expired' WHERE status='Pending' AND expires_at<=now()"); }
    private List<Map<String,Object>> dtos(List<Map<String,Object>> rows) { return rows.stream().map(Db::dto).toList(); }
    private void notify(long user,String project,String title,String body) { db.update("INSERT INTO notifications(user_id,project_id,title,body) VALUES (?,?,?,?)",user,project,title,body); }
}
