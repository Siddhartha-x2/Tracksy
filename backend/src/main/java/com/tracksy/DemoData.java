package com.tracksy;

import java.util.*;
import java.time.LocalDate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoData implements ApplicationRunner {
    private final Db db; private final PasswordEncoder passwords; private final Environment environment;
    public DemoData(Db db,PasswordEncoder passwords,Environment environment) { this.db=db; this.passwords=passwords; this.environment=environment; }
    public static List<Map<String,Object>> accounts() {
        return List.of(account("Dr. Jamie Kim","faculty@tracksy.demo","faculty"),account("Aarav Rao","aarav@tracksy.demo","student"),account("Mia Chen","mia@tracksy.demo","student"),account("Noah Patel","noah@tracksy.demo","student"),account("Priya Shah","priya@tracksy.demo","student"),account("Leo Martin","leo@tracksy.demo","student"),account("Demo administrator","admin@tracksy.demo","admin"));
    }
    private static Map<String,Object> account(String name,String email,String role) { return Map.of("name",name,"email",email,"role",role,"password","TracksyDemo2026!"); }
    @Override @Transactional public void run(ApplicationArguments arguments) {
        boolean demo=Arrays.asList(environment.getActiveProfiles()).contains("demo");
        boolean seed=environment.getProperty("tracksy.demo.seed",Boolean.class,true);
        bootstrap("faculty"); bootstrap("admin");
        if(demo && !seed) return;
        if(!demo) {
            if(db.count("SELECT count(*) FROM users WHERE email LIKE '%@tracksy.demo'")>0) throw new IllegalStateException("Demo accounts exist in this database. Use the demo profile locally or select a clean production database.");
            if(db.count("SELECT count(*) FROM users WHERE role='faculty' AND enabled")<1) throw new IllegalStateException("Configure BOOTSTRAP_FACULTY_EMAIL and a strong BOOTSTRAP_FACULTY_PASSWORD for the first production faculty guide.");
            return;
        }
        if(db.count("SELECT count(*) FROM users")>0) {
            if(db.count("SELECT count(*) FROM users WHERE role='admin' AND enabled")==0) insert(account("Demo administrator","admin@tracksy.demo","admin"));
            return;
        }
        for(var account:accounts()) insert(account);
        long faculty=user("faculty@tracksy.demo"); LocalDate today=LocalDate.now();
        db.update("INSERT INTO projects(id,name,description,term,problem_statement,tech_stack,start_date,deadline,status,join_code,faculty_owner_id,supervision_status) VALUES ('P101','Campus Connect',?,'Current term',?,'React, Spring Boot, PostgreSQL',?,?,'Active','CAMPUS26',?,'Approved')","A campus community app for events, clubs, and the people who make them happen.","Help students discover campus events and join communities with useful, accessible onboarding.",today.minusDays(14),today.plusDays(30),faculty);
        for(var account:accounts()) if(!account.get("role").equals("admin")) db.update("INSERT INTO project_members(project_id,user_id,project_role,responsibility) VALUES ('P101',?,?,?)",user((String)account.get("email")),account.get("role").equals("faculty")?"faculty":account.get("email").equals("aarav@tracksy.demo")?"leader":"member",account.get("role").equals("faculty")?"Guide and verify student work":"Contribute assigned work and evidence.");
        db.update("INSERT INTO score_rubrics(project_id,version,tasks,evidence,consistency,peer,repository,documentation,milestones,created_by) VALUES ('P101',1,40,20,15,10,0,10,5,?)",faculty);
        db.update("INSERT INTO milestones(id,project_id,title,description,due_date,status) VALUES ('M101','P101','Research and design','Validate the problem and plan the user experience.',?,'In progress'),('M102','P101','Working prototype','Implement and demonstrate the campus app.',?,'Planned')",today.plusDays(7),today.plusDays(25));
        task("T101","Design the onboarding flow","aarav@tracksy.demo","High",5,"Under review",today.plusDays(3),"M101");
        task("T102","Build event discovery cards","mia@tracksy.demo","High",5,"In progress",today.plusDays(10),"M102");
        task("T103","Interview five club organizers","aarav@tracksy.demo","Medium",3,"Under review",today.plusDays(2),"M101");
        task("T104","Set up project repository","noah@tracksy.demo","Low",1,"Done",today.minusDays(3),"M102");
        task("T105","Prepare the user research summary","priya@tracksy.demo","Medium",3,"In progress",today.plusDays(4),"M101");
        task("T106","Create the visual design system","leo@tracksy.demo","Medium",3,"In progress",today.plusDays(5),"M101");
        evidence("E101","T104","noah@tracksy.demo","Approved","Repository setup and README","Project structure, setup instructions, and contribution guidance are ready.","Verified contribution.",4);
        evidence("E102","T101","aarav@tracksy.demo","Under review","First onboarding wireframes","Three initial screens for account setup and club selection.","",null);
        evidence("E103","T103","aarav@tracksy.demo","Under review","Organizer interview notes","Notes from the first two organizer interviews.","",null);
        evidence("E104","T105","priya@tracksy.demo","Changes requested","User research summary","Initial summary of the organizer interviews.","Add findings from the remaining interviews and connect them to design decisions.",3);
        db.update("INSERT INTO audit_events(project_id,actor_id,action,entity_type,entity_id,details) VALUES ('P101',?,'Demo project prepared','project','P101','Local demo data illustrates the student and faculty workflows.')",faculty);
    }
    private void insert(Map<String,Object> account) { db.update("INSERT INTO users(name,email,password_hash,role) VALUES (?,?,?,?) ON CONFLICT(email) DO NOTHING",account.get("name"),account.get("email"),passwords.encode((String)account.get("password")),account.get("role")); }
    private long user(String email) { return ((Number)db.one("SELECT id FROM users WHERE email=?",email).get("id")).longValue(); }
    private void bootstrap(String role) {
        String email=environment.getProperty("tracksy.bootstrap."+role+"-email","").trim().toLowerCase(Locale.ROOT),password=environment.getProperty("tracksy.bootstrap."+role+"-password","");
        if(email.isEmpty()) return;
        String name=environment.getProperty("tracksy.bootstrap."+role+"-name",role.equals("faculty")?"Faculty guide":"Administrator");
        Input.email(Map.of("email",email),"email"); Input.text(Map.of("name",name),"name",2,100);
        if(password.length()<14 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw new IllegalStateException("Bootstrap passwords must contain at least 14 characters and at most 72 UTF-8 bytes.");
        if(db.count("SELECT count(*) FROM users WHERE email=?",email)>0) {
            if(db.count("SELECT count(*) FROM users WHERE email=? AND role=?",email,role)!=1) throw new IllegalStateException("Bootstrap email already belongs to a different account role.");
            return;
        }
        db.update("INSERT INTO users(name,email,password_hash,role) VALUES (?,?,?,?)",name,email,passwords.encode(password),role);
    }
    private void task(String id,String name,String email,String priority,int points,String status,LocalDate due,String milestone) {
        db.update("INSERT INTO tasks(id,project_id,name,details,assigned_to,created_by,due_date,priority,complexity,points,status,acceptance_criteria,milestone_id) VALUES (?,'P101',?,?,?, ?,?,?,1,?,?,?,?)",id,name,"Connect the work to the campus app goals and explain the decisions.",user(email),user("faculty@tracksy.demo"),due,priority,points,status,"Provide clear evidence of the completed work and its value to the project.",milestone);
    }
    private void evidence(String id,String task,String email,String status,String title,String note,String feedback,Integer quality) {
        db.update("INSERT INTO evidence(id,task_id,student_id,status) VALUES (?,?,?,?)",id,task,user(email),status);
        db.update("INSERT INTO evidence_versions(evidence_id,version,title,note) VALUES (?,1,?,?)",id,title,note);
        if(!status.equals("Under review")) db.update("INSERT INTO evidence_reviews(evidence_version_id,faculty_id,decision,feedback,quality) SELECT id,?,?,?,? FROM evidence_versions WHERE evidence_id=? AND version=1",user("faculty@tracksy.demo"),status,feedback,quality,id);
    }
}
