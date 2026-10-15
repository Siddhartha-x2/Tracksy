package com.tracksy.workspace;

import com.tracksy.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;

@Configuration
@EnableScheduling
public class AlertScheduler {
    private static final Logger log=LoggerFactory.getLogger(AlertScheduler.class);
    private final Db db;
    public AlertScheduler(Db db) { this.db=db; }
    @Scheduled(initialDelay=15000,fixedDelay=3600000)
    public void notifyUnfinishedWork() {
        String week=LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
        try {
            db.update("INSERT INTO notifications(user_id,project_id,title,body,dedupe_key) SELECT t.assigned_to,t.project_id,'Task overdue',t.name || ' was due ' || t.due_date::text,'overdue:' || t.id || ':' || ? FROM tasks t JOIN projects p ON p.id=t.project_id JOIN project_members m ON m.project_id=t.project_id AND m.user_id=t.assigned_to JOIN users u ON u.id=t.assigned_to WHERE t.status<>'Done' AND t.due_date<current_date AND p.status='Active' AND p.supervision_status='Approved' AND m.active AND u.enabled ON CONFLICT(dedupe_key) DO NOTHING",week);
            db.update("INSERT INTO notifications(user_id,project_id,title,body,dedupe_key) SELECT m.user_id,m.project_id,'Progress check-in needed','No project activity is recorded for at least 7 days while assigned work remains unfinished. Update your progress log or assigned tasks.','inactive:' || m.project_id || ':' || m.user_id || ':' || ? FROM project_members m JOIN users u ON u.id=m.user_id JOIN projects p ON p.id=m.project_id WHERE m.active AND u.enabled AND u.role='student' AND p.status='Active' AND p.supervision_status='Approved' AND greatest(m.joined_at,coalesce((SELECT max(a.created_at) FROM audit_events a WHERE a.project_id=m.project_id AND a.actor_id=m.user_id),m.joined_at))<now()-interval '7 days' AND EXISTS(SELECT 1 FROM tasks t WHERE t.project_id=m.project_id AND t.assigned_to=m.user_id AND t.status<>'Done') ON CONFLICT(dedupe_key) DO NOTHING",week);
        } catch(Exception failure) { log.warn("Project alert generation did not complete: {}",failure.getClass().getSimpleName()); }
    }
}
