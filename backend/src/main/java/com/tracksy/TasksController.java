package com.tracksy;

import java.util.*;
import java.time.LocalDate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TasksController {
    private final Db db; private final Access access;
    public TasksController(Db db,Access access) { this.db=db; this.access=access; }
    @GetMapping("/projects/{projectId}/tasks") Map<String,Object> list(@PathVariable String projectId) {
        var membership=access.member(projectId); boolean manage=access.canManage(membership);
        String filter=manage?"":" AND t.assigned_to=?";
        Object[] args=manage?new Object[]{projectId}:new Object[]{projectId,access.user().id()};
        var rows=db.query("SELECT t.*,u.name AS assignee_name,creator.name AS creator_name,e.status AS evidence_status FROM tasks t JOIN users u ON u.id=t.assigned_to JOIN users creator ON creator.id=t.created_by LEFT JOIN evidence e ON e.task_id=t.id AND e.student_id=t.assigned_to WHERE t.project_id=?"+filter+" ORDER BY t.due_date NULLS LAST,t.created_at",args);
        return Map.of("tasks",rows.stream().map(this::dto).toList());
    }
    private Map<String,Object> dto(Map<String,Object> task) {
        var dto=Db.dto(task); dto.put("dependencyIds",db.query("SELECT depends_on_task_id FROM task_dependencies WHERE task_id=? ORDER BY depends_on_task_id",task.get("id")).stream().map(d -> d.get("depends_on_task_id")).toList()); return dto;
    }
    private Map<String,Object> currentDto(String id) {
        return dto(db.one("SELECT t.*,u.name AS assignee_name,c.name AS creator_name,e.status AS evidence_status FROM tasks t JOIN users u ON u.id=t.assigned_to JOIN users c ON c.id=t.created_by LEFT JOIN evidence e ON e.task_id=t.id AND e.student_id=t.assigned_to WHERE t.id=?",id));
    }
    @PostMapping("/projects/{projectId}/tasks") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Transactional Map<String,Object> create(@PathVariable String projectId,@RequestBody Map<String,Object> body) {
        access.manage(projectId); lockProject(projectId); access.writable(projectId); access.manage(projectId);
        String id=Input.id(); long assignee=Input.number(body,"assignedTo",1,Long.MAX_VALUE); assignee(projectId,assignee);
        String priority=Input.choice(body,"priority","Medium","Low","Medium","High"); long complexity=body.containsKey("complexity")?Input.number(body,"complexity",1,5):1;
        LocalDate due=Input.date(body,"dueDate"); dateBounds(projectId,due);
        String milestone=nullableId(body,"milestoneId"),parent=nullableId(body,"parentTaskId"); relation(projectId,id,milestone,parent,due);
        db.update("INSERT INTO tasks(id,project_id,name,details,assigned_to,created_by,due_date,priority,complexity,points,acceptance_criteria,milestone_id,parent_task_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",id,projectId,Input.text(body,"name",2,160),Input.text(body,"details",0,4000),assignee,access.user().id(),due,priority,complexity,points(priority,complexity),Input.text(body,"acceptanceCriteria",0,4000),milestone,parent);
        dependencies(projectId,id,body.getOrDefault("dependencyIds",List.of()));
        history(id,"Task created","Task assigned with "+points(priority,complexity)+" weighted points."); access.audit(projectId,"Task created","task",id,"A task was created and assigned.",assignee);
        db.update("INSERT INTO notifications(user_id,project_id,title,body) VALUES (?,?,?,?)",assignee,projectId,"Task assigned",Input.text(body,"name",2,160));
        return Map.of("task",currentDto(id));
    }
    private void lockProject(String id) { db.one("SELECT id FROM projects WHERE id=? FOR UPDATE",id); }
    private int points(String priority,long complexity) { return Math.toIntExact((priority.equals("High")?5:priority.equals("Medium")?3:1)*complexity); }
    private void assignee(String project,long user) {
        if(db.count("SELECT count(*) FROM project_members pm JOIN users u ON u.id=pm.user_id WHERE pm.project_id=? AND pm.user_id=? AND pm.active AND u.enabled AND u.role='student'",project,user)!=1) throw new ApiException(400,"Choose an active student in this project.");
    }
    private String nullableId(Map<String,Object> body,String key) { if(body.get(key)==null || "".equals(body.get(key))) return null; return Input.text(body,key,1,40); }
    private void dateBounds(String project,LocalDate due) {
        if(due!=null && db.count("SELECT count(*) FROM projects WHERE id=? AND ((start_date IS NOT NULL AND ?<start_date) OR (deadline IS NOT NULL AND ?>deadline))",project,due,due)>0) throw new ApiException(400,"Task due date must fall within the project schedule.");
    }
    private void relation(String project,String id,String milestone,String parent,LocalDate due) {
        if(milestone!=null) {
            var m=db.one("SELECT * FROM milestones WHERE id=? AND project_id=? FOR UPDATE",milestone,project);
            if("Completed".equals(m.get("status"))) throw new ApiException(409,"Reopen this milestone before assigning work to it.");
            if(due!=null && due.isAfter(((java.sql.Date)m.get("due_date")).toLocalDate())) throw new ApiException(400,"Task due date cannot be later than its milestone.");
        }
        if(parent!=null) {
            if(parent.equals(id) || db.count("SELECT count(*) FROM tasks WHERE id=? AND project_id=?",parent,project)!=1) throw new ApiException(400,"Choose another task from this project as the parent.");
            if(reaches(id,parent)) throw new ApiException(409,"Parent and dependency tasks cannot form a cycle.");
        }
    }
    private void dependencies(String project,String task,Object raw) {
        if(!(raw instanceof List<?> values) || values.size()>50) throw new ApiException(400,"Choose up to 50 task dependencies.");
        LinkedHashSet<String> ids=new LinkedHashSet<>();
        for(Object value:values) {
            if(!(value instanceof String id) || id.length()>40 || id.equals(task)) throw new ApiException(400,"Choose valid task dependencies.");
            if(db.count("SELECT count(*) FROM tasks WHERE id=? AND project_id=?",id,project)!=1) throw new ApiException(400,"Dependencies must belong to this project.");
            if(reaches(id,task)) throw new ApiException(409,"Parent and dependency tasks cannot form a cycle.");
            ids.add(id);
        }
        db.update("DELETE FROM task_dependencies WHERE task_id=?",task); for(String id:ids) db.update("INSERT INTO task_dependencies(task_id,depends_on_task_id) VALUES (?,?)",task,id);
    }
    private boolean reaches(String start,String target) {
        return db.count("WITH RECURSIVE graph(source,target) AS (SELECT task_id,depends_on_task_id FROM task_dependencies UNION SELECT parent_task_id,id FROM tasks WHERE parent_task_id IS NOT NULL), reach(id) AS (SELECT target FROM graph WHERE source=? UNION SELECT g.target FROM graph g JOIN reach r ON g.source=r.id) SELECT count(*) FROM reach WHERE id=?",start,target)>0;
    }
    @PatchMapping("/tasks/{id}") @Transactional Map<String,Object> update(@PathVariable String id,@RequestBody Map<String,Object> body) {
        var accessible=access.task(id); String project=(String)accessible.get("project_id"); lockProject(project); access.writable(project);
        var task=db.one("SELECT * FROM tasks WHERE id=? FOR UPDATE",id); var membership=access.member(project); boolean manage=access.canManage(membership);
        if(!manage && ((Number)task.get("assigned_to")).longValue()!=access.user().id()) throw new ApiException(404,"Task not found.");
        if(body.containsKey("expectedVersion") && Input.number(body,"expectedVersion",1,Integer.MAX_VALUE)!=((Number)task.get("version")).longValue()) throw new ApiException(409,"This task changed. Refresh before updating it.");
        Set<String> statusFields=Set.of("status","blocker","expectedVersion"); boolean planning=body.keySet().stream().anyMatch(k -> !statusFields.contains(k));
        if(planning && !manage) throw new ApiException(403,"Only project managers can update task planning.");
        var merged=Db.dto(task); merged.putAll(body);
        if(Set.of("Done","Under review").contains(body.getOrDefault("status",""))) throw new ApiException(409,"Only evidence submission and faculty review can set this task status.");
        String status=body.containsKey("status")?Input.choice(body,"status","To do","To do","In progress","Blocked"):(String)task.get("status");
        var evidence=db.query("SELECT status FROM evidence WHERE task_id=?",id);
        if(body.containsKey("status") && !evidence.isEmpty() && !"Changes requested".equals(evidence.getFirst().get("status"))) throw new ApiException(409,"Task status follows its evidence review and cannot be changed manually.");
        String blocker=Input.text(merged,"blocker",0,1000); if(status.equals("Blocked") && blocker.length()<3) throw new ApiException(400,"Explain why this task is blocked."); if(!status.equals("Blocked")) blocker="";
        long assigned=Input.number(merged,"assignedTo",1,Long.MAX_VALUE); assignee(project,assigned);
        String priority=Input.choice(merged,"priority","Medium","Low","Medium","High"); long complexity=Input.number(merged,"complexity",1,5);
        var due=Input.date(merged,"dueDate"); dateBounds(project,due);
        String milestone=nullableId(merged,"milestoneId"),parent=nullableId(merged,"parentTaskId");
        Set<String> frozen=Set.of("name","details","assignedTo","priority","complexity","points","dueDate","acceptanceCriteria","milestoneId","parentTaskId","dependencyIds");
        if(!evidence.isEmpty() && body.keySet().stream().anyMatch(frozen::contains)) throw new ApiException(409,"Task planning is frozen after evidence is submitted to preserve assessment history.");
        if(planning && evidence.isEmpty()) relation(project,id,milestone,parent,due);
        db.update("UPDATE tasks SET name=?,details=?,assigned_to=?,due_date=?,priority=?,complexity=?,points=?,status=?,acceptance_criteria=?,milestone_id=?,parent_task_id=?,blocker=?,version=version+1,updated_at=now() WHERE id=?",Input.text(merged,"name",2,160),Input.text(merged,"details",0,4000),assigned,due,priority,complexity,points(priority,complexity),status,Input.text(merged,"acceptanceCriteria",0,4000),milestone,parent,blocker,id);
        if(body.containsKey("dependencyIds")) dependencies(project,id,body.get("dependencyIds"));
        history(id,"Task updated","Status: "+task.get("status")+" → "+status+". Planning fields updated where permitted.");
        access.audit(project,"Task updated","task",id,"Task status or planning was updated.",assigned); return Map.of("task",currentDto(id));
    }
    @GetMapping("/tasks/{id}/comments") Map<String,Object> comments(@PathVariable String id) {
        access.task(id); return Map.of("comments",db.query("SELECT c.*,u.name AS author_name FROM task_comments c JOIN users u ON u.id=c.user_id WHERE c.task_id=? ORDER BY c.created_at,c.id",id).stream().map(Db::dto).toList());
    }
    @PostMapping("/tasks/{id}/comments") @Transactional Map<String,Object> comment(@PathVariable String id,@RequestBody Map<String,Object> body) {
        var task=access.task(id); String project=(String)task.get("project_id"); access.writable(project);
        task=access.task(id);
        db.update("INSERT INTO task_comments(task_id,user_id,body) VALUES (?,?,?)",id,access.user().id(),Input.text(body,"body",1,2000));
        access.audit(project,"Task comment added","task",id,"A comment was added to the task discussion.",((Number)task.get("assigned_to")).longValue()); return comments(id);
    }
    @GetMapping("/tasks/{id}/history") Map<String,Object> historyList(@PathVariable String id) {
        access.task(id); return Map.of("history",db.query("SELECT h.*,u.name AS actor_name FROM task_history h JOIN users u ON u.id=h.actor_id WHERE h.task_id=? ORDER BY h.created_at DESC,h.id DESC",id).stream().map(Db::dto).toList());
    }
    public void history(String task,String action,String details) { db.update("INSERT INTO task_history(task_id,actor_id,action,details) VALUES (?,?,?,?)",task,access.user().id(),action,details); }
}
