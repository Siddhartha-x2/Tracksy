package com.tracksy.workspace;

import com.tracksy.Access;
import com.tracksy.Db;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import static com.tracksy.workspace.WorkspaceController.*;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class ReportController {
    private final Db db;
    private final Access access;
    private final WorkspaceController workspace;
    public ReportController(Db db,Access access,WorkspaceController workspace) { this.db=db; this.access=access; this.workspace=workspace; }

    @GetMapping("/report")
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> report(@PathVariable String projectId) {
        access.member(projectId);
        boolean faculty=workspace.facultyView(projectId);
        var project=db.one("SELECT * FROM projects WHERE id=?",projectId);
        var rubric=new LinkedHashMap<>(workspace.rubricData(projectId));
        @SuppressWarnings("unchecked") var weights=(Map<String,Integer>)rubric.get("weights");
        var members=db.query("SELECT u.id user_id,u.name,u.role,m.active,m.joined_at,m.deactivated_at FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=? AND u.role='student'"+(faculty?"":" AND u.id=?")+" ORDER BY u.name,u.id",faculty?new Object[]{projectId}:new Object[]{projectId,access.user().id()});
        double totalApprovedPoints=decimal(db.one("SELECT coalesce(sum(t.points),0) points FROM tasks t WHERE t.project_id=? AND t.status='Done' AND EXISTS(SELECT 1 FROM evidence e JOIN evidence_versions v ON v.evidence_id=e.id AND v.version=e.current_version JOIN evidence_reviews r ON r.evidence_version_id=v.id WHERE e.task_id=t.id AND e.student_id=t.assigned_to AND e.status='Approved' AND r.decision='Approved')",projectId).get("points"));
        Instant cutoff=assessmentCutoff(project);
        boolean repositoryReady=workspace.repositoryReady(projectId,cutoff);
        rubric.put("repositoryAvailable",repositoryReady);
        List<Map<String,Object>> scored=new ArrayList<>();
        for(var member:members) scored.add(memberScore(projectId,project,member,weights,totalApprovedPoints,repositoryReady,faculty,cutoff));
        var summary=Db.dto(db.one("SELECT count(*) task_count,count(*) FILTER(WHERE t.status='Done' AND EXISTS(SELECT 1 FROM evidence e JOIN evidence_versions v ON v.evidence_id=e.id AND v.version=e.current_version JOIN evidence_reviews r ON r.evidence_version_id=v.id WHERE e.task_id=t.id AND e.student_id=t.assigned_to AND e.status='Approved' AND r.decision='Approved')) completed_tasks FROM tasks t WHERE t.project_id=?",projectId));
        var submissionSummary=db.one("SELECT count(*) FILTER(WHERE e.status='Under review') pending_reviews,count(*) FILTER(WHERE e.status='Approved') approved_submissions FROM evidence e JOIN tasks t ON t.id=e.task_id WHERE t.project_id=?",projectId);
        summary.putAll(Db.dto(submissionSummary));
        summary.put("completionPercent",ScoringEngine.ratio(number(summary.get("completedTasks")),number(summary.get("taskCount"))));
        summary.put("approvedPoints",totalApprovedPoints);
        summary.put("memberCount",number(db.one("SELECT count(*) count FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=? AND m.active AND u.role='student'",projectId).get("count")));
        return Map.of("members",scored,"summary",summary,"rubric",rubric,"alerts",alerts(projectId,faculty),"generatedAt",Instant.now(),"scope",faculty?"faculty-project":"student-own","projectName",project.get("name"),"repositoryAvailable",repositoryReady,
                "scoringPolicy","This is a progress snapshot, not a permanent certified grade. Weighted rubric score measures declared components; contribution share measures verified assigned points out of the project's verified points. Missing components score zero and are explained. Weekly denominators stop at project closure or membership deactivation while those states remain in effect.");
    }

    private Map<String,Object> memberScore(String projectId,Map<String,Object> project,Map<String,Object> member,Map<String,Integer> weights,double totalApprovedPoints,boolean repoReady,boolean faculty,Instant cutoff) {
        long user=number(member.get("user_id"));
        var task=db.one("SELECT count(*) assigned_tasks,coalesce(sum(t.points),0) assigned_points,count(*) FILTER(WHERE t.status='Done' AND e.status='Approved' AND r.decision='Approved') approved_tasks,coalesce(sum(t.points) FILTER(WHERE t.status='Done' AND e.status='Approved' AND r.decision='Approved'),0) approved_points,coalesce(avg(coalesce(r.quality,0)) FILTER(WHERE t.status='Done' AND e.status='Approved' AND r.decision='Approved'),0) approved_quality,count(*) FILTER(WHERE t.due_date IS NOT NULL) dated_tasks,count(*) FILTER(WHERE t.due_date IS NOT NULL AND t.status='Done' AND e.status='Approved' AND r.decision='Approved' AND v.submitted_at::date<=t.due_date) on_time_tasks FROM tasks t LEFT JOIN evidence e ON e.task_id=t.id AND e.student_id=t.assigned_to LEFT JOIN evidence_versions v ON v.evidence_id=e.id AND v.version=e.current_version LEFT JOIN evidence_reviews r ON r.evidence_version_id=v.id WHERE t.project_id=? AND t.assigned_to=?",projectId,user);
        double assigned=decimal(task.get("assigned_points")),approved=decimal(task.get("approved_points"));
        LocalDate today=LocalDate.now();
        LocalDate first=date(member.get("joined_at"));
        if(project.get("start_date")!=null && date(project.get("start_date")).isAfter(first)) first=date(project.get("start_date"));
        Instant memberCutoff=cutoff;
        if(Boolean.FALSE.equals(member.get("active")) && member.get("deactivated_at")!=null && instant(member.get("deactivated_at")).isBefore(memberCutoff)) memberCutoff=instant(member.get("deactivated_at"));
        if(project.get("deadline")!=null) {
            Instant deadlineEnd=date(project.get("deadline")).plusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant().minusNanos(1000);
            if(deadlineEnd.isBefore(memberCutoff)) memberCutoff=deadlineEnd;
        }
        Instant assessmentStart=instant(member.get("joined_at"));
        if(project.get("start_date")!=null) {
            Instant projectStart=date(project.get("start_date")).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
            if(projectStart.isAfter(assessmentStart)) assessmentStart=projectStart;
        }
        LocalDate last=memberCutoff.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        if(last.isAfter(today)) last=today;
        if(Boolean.FALSE.equals(member.get("active")) && member.get("deactivated_at")!=null && date(member.get("deactivated_at")).isBefore(last)) last=date(member.get("deactivated_at"));
        if(project.get("deadline")!=null && date(project.get("deadline")).isBefore(last)) last=date(project.get("deadline"));
        LocalDate firstWeek=first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeek=last.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long expectedWeeks=first.isAfter(last)?0:ChronoUnit.WEEKS.between(firstWeek,lastWeek)+1;
        var log=db.one("SELECT count(*) logged_weeks,count(*) FILTER(WHERE quality IS NOT NULL) reviewed_weeks,coalesce(sum(quality),0) quality_points FROM weekly_logs WHERE project_id=? AND user_id=? AND week_start>=? AND week_start<=?",projectId,user,java.sql.Date.valueOf(firstWeek),java.sql.Date.valueOf(lastWeek));
        var peer=db.one("SELECT count(DISTINCT reviewer_id) reviewer_count,count(*) review_count,coalesce(avg((collaboration+communication+reliability)/3.0),0) mean_rating FROM peer_reviews WHERE project_id=? AND reviewee_id=?",projectId,user);
        boolean peerVisible=number(peer.get("reviewer_count"))>=3;
        var milestone=db.one("SELECT count(DISTINCT t.milestone_id) assigned_milestones,count(DISTINCT t.milestone_id) FILTER(WHERE m.status='Completed' AND NOT EXISTS(SELECT 1 FROM tasks t2 WHERE t2.milestone_id=m.id AND t2.assigned_to=t.assigned_to AND t2.status<>'Done')) completed_milestones FROM tasks t JOIN milestones m ON m.id=t.milestone_id WHERE t.project_id=? AND t.assigned_to=?",projectId,user);
        long commits=0;
        if(repoReady) commits=number(db.one("SELECT count(*) count FROM repository_commits c JOIN repository_identities i ON i.project_id=c.project_id AND lower(i.github_username)=lower(c.github_username) JOIN user_github_identities g ON g.user_id=i.user_id AND lower(g.github_username)=lower(i.github_username) WHERE c.project_id=? AND i.user_id=? AND c.signature_verified AND c.github_author_id=g.github_id AND c.github_committer_id=g.github_id AND c.committed_at>=?::timestamptz AND c.committed_at<=?::timestamptz",projectId,user,assessmentStart.atOffset(ZoneOffset.UTC),memberCutoff.atOffset(ZoneOffset.UTC)).get("count"));
        Map<String,Double> components=new LinkedHashMap<>();
        components.put("tasks",ScoringEngine.ratio(approved,assigned));
        components.put("evidence",ScoringEngine.ratio(decimal(task.get("approved_quality")),5));
        components.put("consistency",ScoringEngine.ratio(number(log.get("logged_weeks")),expectedWeeks));
        components.put("peer",peerVisible?ScoringEngine.ratio(decimal(peer.get("mean_rating")),5):0.0);
        components.put("repository",repoReady?ScoringEngine.ratio(commits,5):0.0);
        components.put("documentation",ScoringEngine.ratio(decimal(log.get("quality_points")),expectedWeeks*5));
        components.put("milestones",ScoringEngine.ratio(number(milestone.get("completed_milestones")),number(milestone.get("assigned_milestones"))));
        Map<String,String> explanations=new LinkedHashMap<>();
        explanations.put("tasks",approved+" approved assigned points / "+assigned+" total assigned points; no assigned work scores 0.");
        explanations.put("evidence",number(task.get("approved_tasks"))+" approved current evidence submissions; mean faculty quality "+ScoringEngine.round(decimal(task.get("approved_quality")))+" / 5. Ungraded historical reviews contribute 0 to this mean; no grade is invented. Unapproved evidence earns no quality credit.");
        explanations.put("consistency",number(log.get("logged_weeks"))+" submitted weekly logs / "+expectedWeeks+" expected weeks from project start or joining through "+last+". The end is capped by today, project deadline, project closure and membership deactivation; closed/inactive records do not accrue future missing weeks.");
        explanations.put("peer",peerVisible?number(peer.get("review_count"))+" confidential received reviews from "+number(peer.get("reviewer_count"))+" reviewers; mean rating / 5.":"Received peer scores are withheld until at least 3 distinct reviewers respond; component currently 0 to protect confidentiality.");
        explanations.put("repository",repoReady?commits+" commits with a GitHub verified signature and matching OAuth owned author/committer IDs between "+assessmentStart+" and "+memberCutoff+" in the latest bounded public sync / a declared target of 5. Freshness is assessed at "+cutoff+" (project closure for a closed project). Unsigned, ambiguous or out-of-period commits earn no repository score. This measures authenticated activity, not code quality.":"Repository component unavailable: needs a sync within 7 days of the assessment cutoff and OAuth verified account mappings for every active student. The cutoff is frozen at closure for a closed project. Component currently 0.");
        explanations.put("documentation",number(log.get("reviewed_weeks"))+" faculty reviewed weekly logs; "+decimal(log.get("quality_points"))+" quality points / "+(expectedWeeks*5)+" possible. Missing or unreviewed weeks earn 0.");
        explanations.put("milestones",number(milestone.get("completed_milestones"))+" faculty completed milestones containing this student's verified tasks / "+number(milestone.get("assigned_milestones"))+" assigned milestones. No linked milestones scores 0.");
        explanations.put("contributionShare",approved+" approved assigned points / "+totalApprovedPoints+" whole project approved points. This share is separate from rubric score and completion.");
        double onTime=ScoringEngine.ratio(number(task.get("on_time_tasks")),number(task.get("dated_tasks")));
        explanations.put("deadlineAdherence",number(task.get("on_time_tasks"))+" tasks with approved evidence submitted by their due date / "+number(task.get("dated_tasks"))+" dated assigned tasks. Undated tasks are excluded; incomplete dated tasks earn 0.");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("userId",user); result.put("name",member.get("name")); result.put("role",member.get("role")); result.put("active",member.get("active"));
        result.put("assignedTasks",task.get("assigned_tasks")); result.put("assignedPoints",assigned); result.put("approvedPoints",approved); result.put("approvedTasks",task.get("approved_tasks"));
        result.put("completionScore",components.get("tasks")); result.put("contributionScore",ScoringEngine.weighted(components,weights)); result.put("rubricScore",ScoringEngine.weighted(components,weights)); result.put("contributionShare",ScoringEngine.ratio(approved,totalApprovedPoints));
        result.put("components",components); result.put("explanations",explanations); result.put("deadlineAdherence",onTime);
        result.put("assessmentThrough",last.toString()); result.put("assessmentSnapshot",true);
        return result;
    }

    @GetMapping("/alerts")
    public Map<String,Object> projectAlerts(@PathVariable String projectId) { access.member(projectId); return Map.of("alerts",alerts(projectId,workspace.facultyView(projectId))); }

    List<Map<String,Object>> alerts(String project,boolean faculty) {
        List<Map<String,Object>> alerts=new ArrayList<>();
        var overdue=db.query("SELECT t.id,t.name,t.assigned_to,u.name student_name,t.due_date FROM tasks t JOIN users u ON u.id=t.assigned_to WHERE t.project_id=? AND t.status<>'Done' AND t.due_date<current_date"+(faculty?"":" AND t.assigned_to=?")+" ORDER BY t.due_date LIMIT 50",faculty?new Object[]{project}:new Object[]{project,access.user().id()});
        for(var task:overdue) alerts.add(Map.of("type","overdue","severity","warning","userId",task.get("assigned_to"),"taskId",task.get("id"),"message",task.get("student_name")+": "+task.get("name")+" was due "+task.get("due_date")));
        var inactive=db.query("SELECT u.id user_id,u.name,greatest(m.joined_at,coalesce((SELECT max(a.created_at) FROM audit_events a WHERE a.project_id=m.project_id AND a.actor_id=m.user_id),m.joined_at)) last_activity FROM project_members m JOIN users u ON u.id=m.user_id JOIN projects p ON p.id=m.project_id WHERE m.project_id=? AND m.active AND u.enabled AND u.role='student' AND p.status='Active' AND EXISTS(SELECT 1 FROM tasks t WHERE t.project_id=m.project_id AND t.assigned_to=m.user_id AND t.status<>'Done')"+(faculty?"":" AND u.id=?"),faculty?new Object[]{project}:new Object[]{project,access.user().id()});
        for(var user:inactive) {
            long days=ChronoUnit.DAYS.between(date(user.get("last_activity")),LocalDate.now());
            if(days>=7) alerts.add(Map.of("type","inactivity","severity","warning","userId",user.get("user_id"),"days",days,"message",user.get("name")+" has no recorded project activity for "+days+" days while assigned work is unfinished."));
        }
        return alerts;
    }

    @GetMapping("/viva")
    public Map<String,Object> viva(@PathVariable String projectId) {
        access.member(projectId); boolean faculty=workspace.facultyView(projectId);
        var evidence=db.query("SELECT e.id evidence_id,t.id task_id,t.name task_name,t.details,t.acceptance_criteria,v.title evidence_title,v.note,v.work_link,e.student_id,u.name student_name FROM evidence e JOIN tasks t ON t.id=e.task_id JOIN evidence_versions v ON v.evidence_id=e.id AND v.version=e.current_version JOIN evidence_reviews r ON r.evidence_version_id=v.id JOIN users u ON u.id=e.student_id WHERE t.project_id=? AND t.status='Done' AND e.status='Approved' AND r.decision='Approved'"+(faculty?"":" AND e.student_id=?")+" ORDER BY t.name LIMIT 40",faculty?new Object[]{projectId}:new Object[]{projectId,access.user().id()});
        List<Map<String,Object>> questions=new ArrayList<>();
        for(var row:evidence) {
            questions.add(question(row,"Explain the implementation choices behind ‘"+row.get("task_name")+"’ and show where the approved evidence ‘"+row.get("evidence_title")+"’ demonstrates your work.","implementation"));
            String criteria=String.valueOf(row.get("acceptance_criteria"));
            questions.add(question(row,criteria.isBlank()?"For ‘"+row.get("task_name")+"’, describe the failure cases you checked and explain how you would reproduce your submitted results.":"For ‘"+row.get("task_name")+"’, demonstrate how you met these acceptance criteria: "+criteria,"verification"));
            String note=String.valueOf(row.get("note"));
            if(!note.isBlank()) questions.add(question(row,"Your approved submission states: ‘"+note.substring(0,Math.min(note.length(),350))+"’. Explain this result and the part you personally implemented.","ownership"));
        }
        return Map.of("method","deterministic-evidence-grounded","questions",questions,"sourceCount",evidence.size(),"notice","Study questions are generated from approved task evidence using templates. No AI provider is configured or called.");
    }

    private Map<String,Object> question(Map<String,Object> evidence,String question,String focus) {
        return Map.of("taskId",evidence.get("task_id"),"taskName",evidence.get("task_name"),"evidenceId",evidence.get("evidence_id"),"evidenceTitle",evidence.get("evidence_title"),"studentName",evidence.get("student_name"),"question",question,"focus",focus);
    }

    @GetMapping("/report.csv")
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public ResponseEntity<byte[]> csv(@PathVariable String projectId) {
        var report=report(projectId); var csv=new StringBuilder("\uFEFF");
        csv.append("Name,User ID,Role,Active,Assigned points,Approved points,Approved tasks,Completion percent,Rubric score,Verified work share percent,Deadline adherence percent,Rubric version,Task score,Evidence score,Consistency score,Peer score,Repository score,Documentation score,Milestone score,Rubric weights,Scoring explanations,Snapshot type,Generated at,Assessment through\r\n");
        @SuppressWarnings("unchecked") var members=(List<Map<String,Object>>)report.get("members");
        @SuppressWarnings("unchecked") var rubric=(Map<String,Object>)report.get("rubric");
        for(var member:members) {
            @SuppressWarnings("unchecked") var components=(Map<String,Double>)member.get("components");
            var values=new ArrayList<>(List.of(member.get("name"),member.get("userId"),member.get("role"),member.get("active"),member.get("assignedPoints"),member.get("approvedPoints"),member.get("approvedTasks"),member.get("completionScore"),member.get("rubricScore"),member.get("contributionShare"),member.get("deadlineAdherence"),rubric.get("version")));
            for(String component:ScoringEngine.defaults().keySet()) values.add(components.get(component));
            values.add(rubric.get("weights")); values.add(member.get("explanations"));
            values.add("Progress snapshot, not a permanent certified grade"); values.add(report.get("generatedAt")); values.add(member.get("assessmentThrough"));
            csv.append(String.join(",",values.stream().map(ReportController::csvCell).toList())).append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"tracksy-report.csv\"").header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String csvCell(Object value) {
        String text=value==null?"":String.valueOf(value);
        if(text.matches("(?s)^[\\s]*[=+@-].*") || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }

    @GetMapping("/report.pdf")
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public ResponseEntity<byte[]> pdf(@PathVariable String projectId) {
        var report=report(projectId); List<String> lines=new ArrayList<>();
        lines.add("TRACKSY | CONTRIBUTION PROGRESS SNAPSHOT"); lines.add(String.valueOf(report.get("projectName"))); lines.add("Generated: "+report.get("generatedAt")); lines.add("Access scope: "+report.get("scope")); lines.add("Progress snapshot, not a permanent certified grade.");
        @SuppressWarnings("unchecked") var rubric=(Map<String,Object>)report.get("rubric");
        lines.add("Rubric version "+rubric.get("version")+" | weights: "+rubric.get("weights")); lines.add("");
        @SuppressWarnings("unchecked") var members=(List<Map<String,Object>>)report.get("members");
        for(var member:members) {
            lines.add(member.get("name")+" | User ID "+member.get("userId")+" | "+member.get("role"));
            lines.add("Assigned: "+member.get("assignedPoints")+" | Approved points: "+member.get("approvedPoints")+" | Approved tasks: "+member.get("approvedTasks"));
            lines.add("Completion: "+member.get("completionScore")+"% | Rubric score: "+member.get("rubricScore")+" / 100 | Verified share: "+member.get("contributionShare")+"%");
            lines.add("Deadline adherence: "+member.get("deadlineAdherence")+"%");
            @SuppressWarnings("unchecked") var explanations=(Map<String,String>)member.get("explanations");
            for(var entry:explanations.entrySet()) lines.add(entry.getKey()+": "+entry.getValue());
            lines.add("");
        }
        lines.add("Scores reflect the displayed rubric and verified records at export time.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"tracksy-report.pdf\"").header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_PDF).body(SimplePdf.create(lines));
    }
}
