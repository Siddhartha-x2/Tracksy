package com.tracksy.workspace;

import com.tracksy.Access;
import com.tracksy.ApiException;
import com.tracksy.Db;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final Db db;
    private final Access access;
    private final ObjectMapper json;
    public WorkspaceController(Db db, Access access,ObjectMapper json) { this.db = db; this.access = access; this.json=json; }

    public record MilestoneInput(String title, String description, String dueDate, String status) {}
    public record LogInput(String weekStart, String completedWork, String nextPlan, String blockers, BigDecimal hours,
                           List<String> taskIds, Integer expectedRevision) {}
    public record LogReview(String feedback, Integer quality, Integer expectedRevision) {}
    public record PeerInput(String period, Long revieweeId, Integer collaboration, Integer communication,
                            Integer reliability, String feedback) {}
    public record RubricInput(Map<String,Integer> weights) {}

    @GetMapping("/projects/{projectId}/milestones")
    public Map<String,Object> milestones(@PathVariable String projectId) {
        access.member(projectId);
        var result = db.query("SELECT m.*,count(t.id) task_count,count(t.id) FILTER(WHERE t.status='Done' AND EXISTS(SELECT 1 FROM evidence e JOIN evidence_versions v ON v.evidence_id=e.id AND v.version=e.current_version JOIN evidence_reviews r ON r.evidence_version_id=v.id WHERE e.task_id=t.id AND e.student_id=t.assigned_to AND e.status='Approved' AND r.decision='Approved')) completed_tasks FROM milestones m LEFT JOIN tasks t ON t.milestone_id=m.id WHERE m.project_id=? GROUP BY m.id ORDER BY m.due_date,m.created_at", projectId);
        return Map.of("milestones", dtos(result));
    }

    @PostMapping("/projects/{projectId}/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> createMilestone(@PathVariable String projectId, @RequestBody MilestoneInput input) {
        access.manage(projectId); writable(projectId); access.manage(projectId);
        var due = dueDate(projectId,input.dueDate());
        String id = UUID.randomUUID().toString();
        if (input.status()!=null && !input.status().equals("Planned")) bad("A new milestone starts as Planned");
        db.update("INSERT INTO milestones(id,project_id,title,description,due_date) VALUES(?,?,?,?,?)", id, projectId,
                text(input.title(),2,160,"Milestone title"), optional(input.description(),4000,"Description"),Date.valueOf(due));
        access.audit(projectId,"Milestone created","milestone",id,"Milestone added to the project plan",null);
        return Map.of("milestone",Db.dto(db.one("SELECT * FROM milestones WHERE id=?",id)));
    }

    @PatchMapping("/milestones/{id}")
    @Transactional
    public Map<String,Object> updateMilestone(@PathVariable String id, @RequestBody MilestoneInput input) {
        var milestone = required("SELECT * FROM milestones WHERE id=?",id);
        String project = (String) milestone.get("project_id");
        access.manage(project); writable(project); access.manage(project);
        milestone=required("SELECT * FROM milestones WHERE id=? FOR UPDATE",id);
        String status = input.status()==null ? (String) milestone.get("status") : input.status();
        if (!Set.of("Planned","In progress","Completed").contains(status)) bad("Invalid milestone status");
        LocalDate due=input.dueDate()==null?date(milestone.get("due_date")):dueDate(project,input.dueDate());
        if(number(db.one("SELECT count(*) count FROM tasks WHERE milestone_id=? AND due_date>?",id,Date.valueOf(due)).get("count"))>0) conflict("Milestone due date cannot precede a linked task's due date");
        if ("Completed".equals(status) || "Completed".equals(milestone.get("status"))) {
            access.faculty(project);
            long unfinished = number(db.one("SELECT count(*) count FROM tasks t WHERE t.milestone_id=? AND (t.status<>'Done' OR NOT EXISTS(SELECT 1 FROM evidence e JOIN evidence_versions v ON v.evidence_id=e.id AND v.version=e.current_version JOIN evidence_reviews r ON r.evidence_version_id=v.id WHERE e.task_id=t.id AND e.student_id=t.assigned_to AND e.status='Approved' AND r.decision='Approved'))",id).get("count"));
            long total = number(db.one("SELECT count(*) count FROM tasks WHERE milestone_id=?",id).get("count"));
            if ("Completed".equals(status) && (total==0 || unfinished>0)) conflict("Complete and verify every linked task before approving this milestone");
        }
        db.update("UPDATE milestones SET title=?,description=?,due_date=?,status=?,approved_by=?,approved_at=CASE WHEN ?='Completed' THEN now() ELSE NULL END,updated_at=now() WHERE id=?",
                input.title()==null ? milestone.get("title") : text(input.title(),2,160,"Milestone title"),
                input.description()==null ? milestone.get("description") : optional(input.description(),4000,"Description"),
                Date.valueOf(due),
                status,"Completed".equals(status) ? access.user().id() : null,status,id);
        access.audit(project,"Milestone updated","milestone",id,"Milestone status: "+status,null);
        return Map.of("milestone",Db.dto(db.one("SELECT * FROM milestones WHERE id=?",id)));
    }

    @GetMapping("/projects/{projectId}/logs")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> logs(@PathVariable String projectId) {
        access.member(projectId);
        boolean faculty = facultyView(projectId);
        var rows = db.query("SELECT l.*,u.name student_name FROM weekly_logs l JOIN users u ON u.id=l.user_id WHERE l.project_id=?"+
                (faculty ? "" : " AND l.user_id=?")+" ORDER BY l.week_start DESC,l.created_at DESC", faculty ? new Object[]{projectId} : new Object[]{projectId,access.user().id()});
        return Map.of("logs",rows.stream().map(this::logDto).toList());
    }

    @PostMapping("/projects/{projectId}/logs")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> createLog(@PathVariable String projectId,@RequestBody LogInput input) {
        var user = access.user(); access.student(projectId); writable(projectId);
        LocalDate week = logWeek(projectId,input.weekStart(),user.id());
        if (!db.query("SELECT id FROM weekly_logs WHERE project_id=? AND user_id=? AND week_start=?",projectId,user.id(),Date.valueOf(week)).isEmpty()) conflict("A log already exists for this week");
        validateLog(input);
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO weekly_logs(id,project_id,user_id,week_start,completed_work,next_plan,blockers,hours) VALUES(?,?,?,?,?,?,?,?)",id,projectId,user.id(),Date.valueOf(week),input.completedWork().trim(),input.nextPlan().trim(),optional(input.blockers(),2000,"Blockers"),input.hours());
        replaceLogTasks(id,projectId,user.id(),input.taskIds());
        access.audit(projectId,"Weekly log submitted","log",id,"Weekly progress log for "+week,user.id());
        return Map.of("log",logDto(db.one("SELECT l.*,u.name student_name FROM weekly_logs l JOIN users u ON u.id=l.user_id WHERE l.id=?",id)));
    }

    @PatchMapping("/logs/{id}")
    @Transactional
    public Map<String,Object> editLog(@PathVariable String id,@RequestBody LogInput input) {
        var log=required("SELECT * FROM weekly_logs WHERE id=?",id);
        String project=(String)log.get("project_id"); access.student(project); writable(project);
        log=required("SELECT * FROM weekly_logs WHERE id=? FOR UPDATE",id);
        if (number(log.get("user_id"))!=access.user().id()) forbidden("You may edit only your own weekly logs");
        revision(log,input.expectedRevision());
        validateLog(input);
        if (input.weekStart()!=null && !date(log.get("week_start")).equals(logWeek(project,input.weekStart(),access.user().id()))) bad("The week of a submitted log cannot change");
        saveLogVersion(id);
        db.update("UPDATE weekly_logs SET completed_work=?,next_plan=?,blockers=?,hours=?,feedback='',quality=NULL,reviewed_by=NULL,reviewed_at=NULL,revision=revision+1,updated_at=now() WHERE id=?",input.completedWork().trim(),input.nextPlan().trim(),optional(input.blockers(),2000,"Blockers"),input.hours(),id);
        replaceLogTasks(id,project,access.user().id(),input.taskIds());
        boolean late=LocalDate.now().isAfter(date(log.get("week_start")).plusDays(13));
        access.audit(project,late ? "Late weekly log edit" : "Weekly log edited","log",id,"Revision "+(number(log.get("revision"))+1)+"; previous review invalidated",access.user().id());
        return Map.of("log",logDto(db.one("SELECT l.*,u.name student_name FROM weekly_logs l JOIN users u ON u.id=l.user_id WHERE l.id=?",id)));
    }

    @PostMapping("/logs/{id}/review")
    @Transactional
    public Map<String,Object> reviewLog(@PathVariable String id,@RequestBody LogReview input) {
        var log=required("SELECT * FROM weekly_logs WHERE id=?",id);
        String project=(String)log.get("project_id"); access.faculty(project); writable(project);
        log=required("SELECT * FROM weekly_logs WHERE id=? FOR UPDATE",id); revision(log,input.expectedRevision());
        int quality=rating(input.quality(),"Quality");
        saveLogVersion(id);
        db.update("UPDATE weekly_logs SET feedback=?,quality=?,reviewed_by=?,reviewed_at=now(),revision=revision+1,updated_at=now() WHERE id=?",text(input.feedback(),2,2000,"Review feedback"),quality,access.user().id(),id);
        access.audit(project,"Weekly log reviewed","log",id,"Faculty reviewed progress log",number(log.get("user_id")));
        notifyUser(number(log.get("user_id")),project,"Weekly log reviewed","Your progress log for "+log.get("week_start")+" has faculty feedback",null);
        return Map.of("log",logDto(db.one("SELECT l.*,u.name student_name FROM weekly_logs l JOIN users u ON u.id=l.user_id WHERE l.id=?",id)));
    }

    @GetMapping("/logs/{id}/history")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> logHistory(@PathVariable String id) {
        var log=required("SELECT * FROM weekly_logs WHERE id=?",id); String project=(String)log.get("project_id"); access.member(project);
        if(!facultyView(project) && number(log.get("user_id"))!=access.user().id()) forbidden("Log history is private");
        var versions=db.query("SELECT revision,snapshot,saved_at FROM weekly_log_versions WHERE log_id=? ORDER BY revision DESC",id).stream().map(row->{
            var dto=Db.dto(row);
            try { dto.put("snapshot",Db.dto(json.readValue(row.get("snapshot").toString(),new TypeReference<Map<String,Object>>(){}))); }
            catch(JsonProcessingException malformed) { throw new ApiException(500,"The saved log version could not be read"); }
            return dto;
        }).toList();
        return Map.of("versions",versions);
    }

    @GetMapping("/projects/{projectId}/peer-reviews")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> peerReviews(@PathVariable String projectId) {
        access.member(projectId); boolean faculty=facultyView(projectId);
        var rows=db.query("SELECT r.*,u.name reviewee_name,v.name reviewer_name FROM peer_reviews r JOIN users u ON u.id=r.reviewee_id JOIN users v ON v.id=r.reviewer_id WHERE r.project_id=?"+(faculty ? "" : " AND r.reviewer_id=?")+" ORDER BY r.created_at DESC",faculty ? new Object[]{projectId} : new Object[]{projectId,access.user().id()});
        var received=db.query("SELECT period,count(*) count,avg(collaboration) collaboration,avg(communication) communication,avg(reliability) reliability FROM peer_reviews WHERE project_id=? AND reviewee_id=? GROUP BY period ORDER BY period DESC",projectId,access.user().id());
        List<Map<String,Object>> aggregates=new ArrayList<>();
        for(var row:received) {
            if(number(row.get("count"))<3) aggregates.add(Map.of("period",row.get("period"),"count",row.get("count"),"withheld",true));
            else {
                var dto=Db.dto(row); dto.put("withheld",false);
                dto.put("score",ScoringEngine.round((decimal(row.get("collaboration"))+decimal(row.get("communication"))+decimal(row.get("reliability")))/15*100));
                aggregates.add(dto);
            }
        }
        return Map.of("reviews",dtos(rows),"receivedAggregate",aggregates,"confidential",true,"minimumReviewersForAggregate",3);
    }

    @PostMapping("/projects/{projectId}/peer-reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> submitPeerReview(@PathVariable String projectId,@RequestBody PeerInput input) {
        access.student(projectId); writable(projectId);
        if(input.revieweeId()==null || input.revieweeId()==access.user().id()) bad("Select a different student team member");
        if(db.query("SELECT u.id FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=? AND m.user_id=? AND m.active AND u.enabled AND u.role='student'",projectId,input.revieweeId()).isEmpty()) bad("The reviewee must be an active student in this project");
        String period=text(input.period(),7,7,"Period");
        if(!period.equals(YearMonth.now().toString())) bad("Peer evaluation period must be the current month (YYYY-MM)");
        if(!db.query("SELECT id FROM peer_reviews WHERE project_id=? AND reviewer_id=? AND reviewee_id=? AND period=?",projectId,access.user().id(),input.revieweeId(),period).isEmpty()) conflict("You have already evaluated this student for this period");
        int collaboration=rating(input.collaboration(),"Collaboration"),communication=rating(input.communication(),"Communication"),reliability=rating(input.reliability(),"Reliability");
        var row=db.one("INSERT INTO peer_reviews(project_id,reviewer_id,reviewee_id,period,collaboration,communication,reliability,feedback) VALUES(?,?,?,?,?,?,?,?) RETURNING id",projectId,access.user().id(),input.revieweeId(),period,collaboration,communication,reliability,optional(input.feedback(),2000,"Peer feedback"));
        access.audit(projectId,"Confidential peer evaluation submitted","peerReview",row.get("id").toString(),"Confidential evaluation recorded",access.user().id());
        return Map.of("id",row.get("id"),"submitted",true);
    }

    @GetMapping("/projects/{projectId}/rubric")
    public Map<String,Object> rubric(@PathVariable String projectId) { access.member(projectId); return rubricData(projectId); }

    @PostMapping("/projects/{projectId}/rubric")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String,Object> createRubric(@PathVariable String projectId,@RequestBody RubricInput input) {
        access.faculty(projectId); writable(projectId);
        db.one("SELECT id FROM projects WHERE id=? FOR UPDATE",projectId);
        var weights=input.weights();
        if(weights==null || !weights.keySet().equals(ScoringEngine.defaults().keySet()) || weights.values().stream().anyMatch(v->v==null||v<0||v>100) || weights.values().stream().mapToInt(Integer::intValue).sum()!=100) bad("Supply all seven nonnegative integer weights totaling 100");
        if(weights.get("repository")>0 && !repositoryReady(projectId)) bad("Repository weight needs a successful public sync and faculty verified mappings for every active student");
        int version=((Number)rubricData(projectId).get("version")).intValue()+1;
        db.update("INSERT INTO score_rubrics(project_id,version,tasks,evidence,consistency,peer,repository,documentation,milestones,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",projectId,version,weights.get("tasks"),weights.get("evidence"),weights.get("consistency"),weights.get("peer"),weights.get("repository"),weights.get("documentation"),weights.get("milestones"),access.user().id());
        access.audit(projectId,"Scoring rubric changed","rubric",Integer.toString(version),"Published rubric version "+version+" with weights "+weights,null);
        for(var member:db.query("SELECT user_id FROM project_members WHERE project_id=? AND active",projectId)) notifyUser(number(member.get("user_id")),projectId,"Scoring rubric updated","Faculty published rubric version "+version,null);
        return rubricData(projectId);
    }

    @GetMapping("/projects/{projectId}/rubric/history")
    public Map<String,Object> rubricHistory(@PathVariable String projectId) {
        access.member(projectId); return Map.of("versions",dtos(db.query("SELECT version,tasks,evidence,consistency,peer,repository,documentation,milestones,created_at FROM score_rubrics WHERE project_id=? ORDER BY version DESC",projectId)));
    }

    @GetMapping("/projects/{projectId}/activity")
    public Map<String,Object> activity(@PathVariable String projectId) {
        access.member(projectId); boolean faculty=facultyView(projectId);
        var events=db.query("SELECT a.id,u.name actor_name,a.action,a.entity_type,a.entity_id,a.details,a.created_at FROM audit_events a JOIN users u ON u.id=a.actor_id WHERE a.project_id=?"+(faculty ? "" : " AND (a.visibility_user_id IS NULL OR a.visibility_user_id=?)")+" ORDER BY a.created_at DESC,a.id DESC LIMIT 200",faculty?new Object[]{projectId}:new Object[]{projectId,access.user().id()});
        // A confidential review never exposes the reviewee or its ratings through the timeline.
        return Map.of("events",dtos(events));
    }

    @GetMapping("/notifications")
    public Map<String,Object> notifications() {
        long user=access.user().id();
        var rows=dtos(db.query("SELECT id,title,body,(read_at IS NOT NULL) read,project_id,created_at FROM notifications WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 100",user));
        return Map.of("notifications",rows,"unreadCount",number(db.one("SELECT count(*) count FROM notifications WHERE user_id=? AND read_at IS NULL",user).get("count")));
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String,Object> readNotification(@PathVariable long id) {
        if(db.update("UPDATE notifications SET read_at=coalesce(read_at,now()) WHERE id=? AND user_id=?",id,access.user().id())==0) throw new ApiException(404,"Notification not found");
        return Map.of("read",true);
    }

    @PostMapping("/notifications/read-all")
    public Map<String,Object> readAllNotifications() {
        db.update("UPDATE notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL",access.user().id()); return Map.of("read",true);
    }

    public Map<String,Object> rubricData(String project) {
        var rows=db.query("SELECT * FROM score_rubrics WHERE project_id=? ORDER BY version DESC LIMIT 1",project);
        if(rows.isEmpty()) {
            var owner=required("SELECT faculty_owner_id FROM projects WHERE id=?",project);
            try {
                rows=db.query("INSERT INTO score_rubrics(project_id,version,tasks,evidence,consistency,peer,repository,documentation,milestones,created_by) VALUES(?,1,40,20,15,10,0,10,5,?) ON CONFLICT(project_id,version) DO NOTHING RETURNING *",project,owner.get("faculty_owner_id"));
            } catch(ConcurrencyFailureException concurrentInitialization) { throw new ApiException(409,"The project rubric changed. Refresh the report"); }
            if(rows.isEmpty()) rows=db.query("SELECT * FROM score_rubrics WHERE project_id=? ORDER BY version DESC LIMIT 1",project);
            if(rows.isEmpty()) throw new ApiException(409,"The project rubric changed. Refresh the report");
        }
        var row=rows.getFirst(); var weights=new LinkedHashMap<String,Integer>();
        for(String key:ScoringEngine.defaults().keySet()) weights.put(key,((Number)row.get(key)).intValue());
        return Map.of("version",row.get("version"),"weights",weights,"createdAt",row.get("created_at"),"repositoryAvailable",repositoryReadyForView(project));
    }

    public boolean repositoryReady(String project) {
        return repositoryReady(project,java.time.Instant.now());
    }
    public boolean repositoryReady(String project,java.time.Instant cutoff) {
        return !db.query("SELECT r.project_id FROM repository_integrations r WHERE r.project_id=? AND r.status='Synced' AND r.last_synced_at>?::timestamptz-interval '7 days' AND r.last_synced_at<=?::timestamptz AND EXISTS(SELECT 1 FROM repository_identities i JOIN user_github_identities g ON g.user_id=i.user_id AND lower(g.github_username)=lower(i.github_username) WHERE i.project_id=r.project_id) AND NOT EXISTS(SELECT 1 FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=r.project_id AND m.active AND u.enabled AND u.role='student' AND NOT EXISTS(SELECT 1 FROM repository_identities i JOIN user_github_identities g ON g.user_id=i.user_id AND lower(g.github_username)=lower(i.github_username) WHERE i.project_id=m.project_id AND i.user_id=m.user_id))",project,cutoff.atOffset(java.time.ZoneOffset.UTC),cutoff.atOffset(java.time.ZoneOffset.UTC)).isEmpty();
    }
    public boolean repositoryReadyForView(String project) {
        var record=required("SELECT status,assessment_closed_at FROM projects WHERE id=?",project);
        return repositoryReady(project,assessmentCutoff(record));
    }
    static java.time.Instant assessmentCutoff(Map<String,Object> project) {
        if(Set.of("Completed","Archived").contains(project.get("status")) && project.get("assessment_closed_at")!=null) return instant(project.get("assessment_closed_at"));
        return java.time.Instant.now();
    }

    boolean facultyView(String project) {
        if(!"faculty".equals(access.user().role())) return false;
        access.faculty(project); return true;
    }

    void writable(String project) {
        access.writable(project);
    }

    void notifyUser(long user,String project,String title,String body,String dedupe) {
        db.update("INSERT INTO notifications(user_id,project_id,title,body,dedupe_key) VALUES(?,?,?,?,?) ON CONFLICT(dedupe_key) DO NOTHING",user,project,title,body,dedupe);
    }

    private Map<String,Object> logDto(Map<String,Object> row) {
        var dto=Db.dto(row); dto.put("taskIds",db.query("SELECT task_id FROM log_tasks WHERE log_id=?",row.get("id")).stream().map(r->r.get("task_id")).toList()); return dto;
    }
    private void replaceLogTasks(String id,String project,long user,List<String> tasks) {
        var ids=tasks==null?List.<String>of():tasks;
        if(ids.size()>100 || new HashSet<>(ids).size()!=ids.size()) bad("Use at most 100 distinct task references");
        for(String task:ids) if(db.query("SELECT id FROM tasks WHERE id=? AND project_id=? AND assigned_to=?",task,project,user).isEmpty()) bad("Log task references must belong to your own project work");
        db.update("DELETE FROM log_tasks WHERE log_id=?",id);
        for(String task:ids) db.update("INSERT INTO log_tasks(log_id,task_id) VALUES(?,?)",id,task);
    }
    private void saveLogVersion(String id) {
        db.update("INSERT INTO weekly_log_versions(log_id,revision,snapshot) SELECT l.id,l.revision,to_jsonb(l)||jsonb_build_object('taskIds',coalesce((SELECT jsonb_agg(t.task_id ORDER BY t.task_id) FROM log_tasks t WHERE t.log_id=l.id),'[]'::jsonb)) FROM weekly_logs l WHERE l.id=? ON CONFLICT DO NOTHING",id);
    }
    private LocalDate dueDate(String project,String value) {
        LocalDate due=parseDate(value,"Due date"); var row=required("SELECT start_date,deadline FROM projects WHERE id=?",project);
        if(row.get("start_date")!=null && due.isBefore(date(row.get("start_date")))) bad("Milestone cannot precede the project start");
        if(row.get("deadline")!=null && due.isAfter(date(row.get("deadline")))) bad("Milestone must fit within the project deadline");
        return due;
    }
    private LocalDate logWeek(String project,String value,long user) {
        LocalDate week=parseDate(value,"Week start");
        if(week.getDayOfWeek()!=DayOfWeek.MONDAY || week.isAfter(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))) bad("Week start must be a Monday on or before the current week");
        var row=required("SELECT p.start_date,p.deadline,m.joined_at FROM projects p JOIN project_members m ON m.project_id=p.id WHERE p.id=? AND m.user_id=?",project,user);
        LocalDate first=date(row.get("joined_at"));
        if(row.get("start_date")!=null && date(row.get("start_date")).isAfter(first)) first=date(row.get("start_date"));
        if(week.isBefore(first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))) bad("You cannot backfill logs before joining this project");
        if(row.get("deadline")!=null && week.isAfter(date(row.get("deadline")).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))) bad("Progress log weeks must fit within the project deadline");
        return week;
    }
    private void validateLog(LogInput input) {
        text(input.completedWork(),10,4000,"Completed work"); text(input.nextPlan(),10,4000,"Next plan");
        if(input.hours()==null || input.hours().compareTo(BigDecimal.ZERO)<0 || input.hours().compareTo(BigDecimal.valueOf(168))>0 || input.hours().scale()>2) bad("Hours must be between 0 and 168 with at most two decimals");
    }
    private void revision(Map<String,Object> log,Integer expected) {
        if(expected==null) bad("expectedRevision is required");
        if(number(log.get("revision"))!=expected) conflict("This log changed. Refresh before editing or reviewing");
    }
    Map<String,Object> required(String sql,Object...args) { var rows=db.query(sql,args); if(rows.isEmpty()) throw new ApiException(404,"Record not found"); return rows.getFirst(); }
    static List<Map<String,Object>> dtos(List<Map<String,Object>> rows) { return rows.stream().map(Db::dto).toList(); }
    static String text(String value,int min,int max,String field) { if(value==null || value.trim().length()<min || value.trim().length()>max || value.indexOf('\0')>=0) bad(field+" must have "+min+"–"+max+" characters and no null characters"); return value.trim(); }
    static String optional(String value,int max,String field) { return value==null || value.isBlank()?"":text(value,0,max,field); }
    static LocalDate parseDate(String value,String field) { try { LocalDate parsed=LocalDate.parse(value); if(parsed.getYear()<2000 || parsed.getYear()>2200) throw new IllegalArgumentException(); return parsed; } catch(Exception e) { throw new ApiException(400,field+" must be YYYY-MM-DD in 2000–2200"); } }
    static LocalDate date(Object value) {
        if(value instanceof Date d) return d.toLocalDate();
        if(value instanceof java.sql.Timestamp t) return t.toInstant().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
        if(value instanceof java.time.OffsetDateTime t) return t.toLocalDate();
        return LocalDate.parse(value.toString().substring(0,10));
    }
    static java.time.Instant instant(Object value) {
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if(value instanceof java.time.OffsetDateTime timestamp) return timestamp.toInstant();
        if(value instanceof java.time.Instant timestamp) return timestamp;
        return java.time.Instant.parse(value.toString());
    }
    static int rating(Integer value,String field) { if(value==null || value<1 || value>5) bad(field+" must be 1–5"); return value; }
    static long number(Object value) { return value==null?0:((Number)value).longValue(); }
    static double decimal(Object value) { return value==null?0:((Number)value).doubleValue(); }
    static void bad(String message) { throw new ApiException(400,message); }
    static void forbidden(String message) { throw new ApiException(403,message); }
    static void conflict(String message) { throw new ApiException(409,message); }
}
