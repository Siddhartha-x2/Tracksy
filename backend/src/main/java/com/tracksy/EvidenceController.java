package com.tracksy;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.io.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@RestController
@RequestMapping("/api")
public class EvidenceController {
    private final Db db; private final Access access; private final TasksController tasks; private final Path uploads;
    public EvidenceController(Db db,Access access,TasksController tasks,@Value("${tracksy.upload-directory}") String directory) throws IOException {
        this.db=db; this.access=access; this.tasks=tasks; uploads=Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(uploads);
    }
    private static final String SELECT="SELECT e.id,e.task_id,t.name AS task_name,e.student_id,u.name AS student_name,e.status,e.current_version AS version,v.title,v.note,v.work_link,v.submitted_at,v.original_filename,(v.stored_filename IS NOT NULL) AS has_file,r.decision AS review_decision,r.feedback,r.quality,r.reviewed_at FROM evidence e JOIN tasks t ON t.id=e.task_id JOIN users u ON u.id=e.student_id JOIN evidence_versions v ON v.evidence_id=e.id AND v.version=e.current_version LEFT JOIN evidence_reviews r ON r.evidence_version_id=v.id ";
    @GetMapping("/projects/{projectId}/evidence") Map<String,Object> list(@PathVariable String projectId) {
        var membership=access.member(projectId); var u=access.user(); boolean faculty="faculty".equals(u.role()) && ((Number)membership.get("faculty_owner_id")).longValue()==u.id();
        var rows=faculty?db.query(SELECT+"WHERE t.project_id=? ORDER BY e.updated_at DESC",projectId):db.query(SELECT+"WHERE t.project_id=? AND e.student_id=? ORDER BY e.updated_at DESC",projectId,u.id());
        return Map.of("evidence",rows.stream().map(Db::dto).toList());
    }
    private Map<String,Object> authorized(String id) {
        var evidence=db.one("SELECT e.*,t.project_id,t.assigned_to FROM evidence e JOIN tasks t ON t.id=e.task_id WHERE e.id=?",id);
        var membership=access.member((String)evidence.get("project_id")); var u=access.user();
        boolean faculty="faculty".equals(u.role()) && ((Number)membership.get("faculty_owner_id")).longValue()==u.id();
        if(!faculty && ((Number)evidence.get("student_id")).longValue()!=u.id()) throw new ApiException(404,"Evidence not found."); return evidence;
    }
    private void lockProject(String project) { db.one("SELECT id FROM projects WHERE id=? FOR UPDATE",project); access.writable(project); }
    private Map<String,Object> item(String id) { return Db.dto(db.one(SELECT+"WHERE e.id=?",id)); }
    private void canSubmit(Map<String,Object> task) {
        String project=(String)task.get("project_id"); access.student(project);
        if(((Number)task.get("assigned_to")).longValue()!=access.user().id()) throw new ApiException(403,"Only the assigned student can submit task evidence.");
        if(db.count("SELECT count(*) FROM task_dependencies d JOIN tasks t ON t.id=d.depends_on_task_id WHERE d.task_id=? AND NOT EXISTS(SELECT 1 FROM evidence e WHERE e.task_id=t.id AND e.status='Approved')",task.get("id"))>0)
            throw new ApiException(409,"Faculty must approve all dependency tasks before you submit this work.");
        if(db.count("SELECT count(*) FROM tasks WHERE parent_task_id=? AND status<>'Done'",task.get("id"))>0) throw new ApiException(409,"Complete the subtasks before submitting the parent task.");
    }
    private Map<String,Object> fields(Map<String,String> form,boolean retained) {
        for(var entry:form.entrySet()) if(!Set.of("title","note","workLink","expectedVersion").contains(entry.getKey())) throw new ApiException(400,"Unexpected evidence field.");
        var body=new HashMap<String,Object>(form);
        String title=Input.text(body,"title",2,160),note=Input.text(body,"note",0,4000),link=Input.url(Input.text(body,"workLink",0,2000));
        return new HashMap<>(Map.of("title",title,"note",note,"workLink",link));
    }
    private record Stored(String original,String filename,String contentType,long size) {}
    private Stored upload(MultipartFile file,MultipartHttpServletRequest request) {
        if(request.getMultiFileMap().values().stream().mapToInt(List::size).sum()>1 || request.getMultiFileMap().keySet().stream().anyMatch(k -> !k.equals("file"))) throw new ApiException(400,"Upload at most one file named file.");
        if(file==null || file.isEmpty()) return null;
        if(file.getSize()>10*1024*1024) throw new ApiException(400,"Evidence files must be 10 MB or less.");
        String original=Optional.ofNullable(file.getOriginalFilename()).orElse("evidence").replace('\\','/'); original=original.substring(original.lastIndexOf('/')+1);
        if(original.isBlank() || original.length()>255 || original.chars().anyMatch(c -> c<32 || c==127)) throw new ApiException(400,"Choose a valid evidence filename.");
        String ext=original.lastIndexOf('.')<0?"":original.substring(original.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        String mime=switch(ext) { case ".pdf" -> "application/pdf"; case ".png" -> "image/png"; case ".jpg",".jpeg" -> "image/jpeg"; case ".txt" -> "text/plain"; case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; default -> throw new ApiException(400,"Upload a PDF, PNG, JPG, TXT, or DOCX file."); };
        try {
            byte[] bytes=file.getBytes(); signature(ext,bytes);
            String filename=UUID.randomUUID()+ext; Path path=uploads.resolve(filename); Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) try { Files.deleteIfExists(path); } catch(IOException ignored) {} } });
            return new Stored(original,filename,mime,bytes.length);
        } catch(IOException e) { throw new ApiException(400,"The uploaded file could not be processed."); }
    }
    private void signature(String ext,byte[] data) throws IOException {
        boolean valid=switch(ext) {
            case ".pdf" -> data.length>=8 && new String(data,0,5,StandardCharsets.US_ASCII).equals("%PDF-");
            case ".png" -> data.length>=24 && Arrays.equals(Arrays.copyOf(data,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
            case ".jpg",".jpeg" -> data.length>=4 && data[0]==(byte)0xff && data[1]==(byte)0xd8 && data[2]==(byte)0xff;
            case ".txt" -> validText(data);
            case ".docx" -> validDocx(data);
            default -> false;
        };
        if(!valid) throw new ApiException(400,"The file content does not match its extension.");
    }
    private boolean validText(byte[] data) {
        try { String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString(); return text.chars().noneMatch(c -> c<32 && c!='\n' && c!='\r' && c!='\t'); }
        catch(CharacterCodingException e) { return false; }
    }
    private boolean validDocx(byte[] data) throws IOException {
        if(data.length<4 || data[0]!='P' || data[1]!='K') return false;
        boolean types=false,document=false; long expanded=0; int entries=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(data))) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                if(++entries>2000) return false;
                String name=entry.getName();
                if(name.contains("..") || name.startsWith("/") || name.contains("\\") || name.toLowerCase(Locale.ROOT).contains("vbaproject") || name.toLowerCase(Locale.ROOT).matches(".*\\.(exe|dll|js|bat|cmd|ps1)$")) return false;
                types|=name.equals("[Content_Types].xml"); document|=name.equals("word/document.xml");
                byte[] buffer=new byte[8192]; for(int read;(read=zip.read(buffer))!=-1;) { expanded+=read; if(expanded>25*1024*1024) return false; }
            }
        } catch(ZipException e) { return false; }
        return types && document;
    }
    private void insertVersion(String evidence,int version,Map<String,Object> fields,Stored file) {
        db.update("INSERT INTO evidence_versions(evidence_id,version,title,note,work_link,original_filename,stored_filename,content_type,file_size) VALUES (?,?,?,?,?,?,?,?,?)",evidence,version,fields.get("title"),fields.get("note"),fields.get("workLink"),file==null?null:file.original(),file==null?null:file.filename(),file==null?null:file.contentType(),file==null?null:file.size());
    }
    @PostMapping(value="/projects/{projectId}/tasks/{taskId}/evidence",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @Transactional
    Map<String,Object> submit(@PathVariable String projectId,@PathVariable String taskId,@RequestParam Map<String,String> form,@RequestParam(required=false) MultipartFile file,MultipartHttpServletRequest request) {
        access.student(projectId); lockProject(projectId); var task=db.one("SELECT * FROM tasks WHERE id=? AND project_id=? FOR UPDATE",taskId,projectId); canSubmit(task);
        if(db.count("SELECT count(*) FROM evidence WHERE task_id=?",taskId)>0) throw new ApiException(409,"This task already has evidence. Use resubmit after faculty requests changes.");
        var values=fields(form,false); var stored=upload(file,request);
        if(stored==null && ((String)values.get("note")).isEmpty() && ((String)values.get("workLink")).isEmpty()) throw new ApiException(400,"Add a note, work link, or file to show your work.");
        String id=Input.id(); db.update("INSERT INTO evidence(id,task_id,student_id) VALUES (?,?,?)",id,taskId,access.user().id()); insertVersion(id,1,values,stored);
        db.update("UPDATE tasks SET status='Under review',blocker='',version=version+1,updated_at=now() WHERE id=?",taskId);
        tasks.history(taskId,"Evidence submitted","Evidence version 1 is awaiting faculty review."); access.audit(projectId,"Evidence submitted","evidence",id,"Evidence was submitted for faculty review.",access.user().id()); notifyFaculty(projectId,"Evidence submitted",task.get("name")+" is ready for review.");
        return Map.of("evidence",item(id));
    }
    @PostMapping(value="/evidence/{id}/resubmit",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional
    Map<String,Object> resubmit(@PathVariable String id,@RequestParam Map<String,String> form,@RequestParam(required=false) MultipartFile file,MultipartHttpServletRequest request) {
        var accessible=authorized(id); String project=(String)accessible.get("project_id"); lockProject(project); access.student(project);
        var evidence=db.one("SELECT * FROM evidence WHERE id=? FOR UPDATE",id);
        if(((Number)evidence.get("student_id")).longValue()!=access.user().id()) throw new ApiException(403,"Only the submitting student can resubmit.");
        int current=((Number)evidence.get("current_version")).intValue(); int expected;
        try { expected=Integer.parseInt(form.getOrDefault("expectedVersion","")); } catch(NumberFormatException e) { throw new ApiException(400,"Include the evidence version you are updating."); }
        if(expected!=current || !"Changes requested".equals(evidence.get("status"))) throw new ApiException(409,"This evidence changed or is not awaiting revision. Refresh before resubmitting.");
        var task=db.one("SELECT * FROM tasks WHERE id=? FOR UPDATE",evidence.get("task_id")); canSubmit(task);
        var latest=db.one("SELECT * FROM evidence_versions WHERE evidence_id=? AND version=?",id,current); var values=fields(form,true); var stored=upload(file,request);
        if(stored==null && latest.get("stored_filename")!=null) stored=new Stored((String)latest.get("original_filename"),(String)latest.get("stored_filename"),(String)latest.get("content_type"),latest.get("file_size")==null?0:((Number)latest.get("file_size")).longValue());
        if(stored==null && ((String)values.get("note")).isEmpty() && ((String)values.get("workLink")).isEmpty()) throw new ApiException(400,"Add evidence of your revised work.");
        insertVersion(id,current+1,values,stored); db.update("UPDATE evidence SET status='Under review',current_version=?,updated_at=now() WHERE id=?",current+1,id);
        db.update("UPDATE tasks SET status='Under review',blocker='',version=version+1,updated_at=now() WHERE id=?",task.get("id")); tasks.history((String)task.get("id"),"Evidence revised","Evidence revision "+(current+1)+" was submitted.");
        access.audit(project,"Evidence revised","evidence",id,"A new evidence version was submitted.",access.user().id()); notifyFaculty(project,"Evidence revised",task.get("name")+" has a new revision to review."); return Map.of("evidence",item(id));
    }
    @PostMapping("/evidence/{id}/review") @Transactional Map<String,Object> review(@PathVariable String id,@RequestBody Map<String,Object> body) {
        var visible=authorized(id); String project=(String)visible.get("project_id"); access.faculty(project); lockProject(project);
        var evidence=db.one("SELECT * FROM evidence WHERE id=? FOR UPDATE",id); long expected=Input.number(body,"expectedVersion",1,Integer.MAX_VALUE);
        if(expected!=((Number)evidence.get("current_version")).longValue() || !"Under review".equals(evidence.get("status"))) throw new ApiException(409,"This evidence changed. Open the latest version before reviewing it.");
        String decision=Input.choice(body,"decision","","Approved","Changes requested","Rejected"); String feedback=Input.text(body,"feedback",decision.equals("Approved")?0:3,1000); long quality=Input.number(body,"quality",1,5);
        var version=db.one("SELECT id FROM evidence_versions WHERE evidence_id=? AND version=?",id,expected);
        db.update("INSERT INTO evidence_reviews(evidence_version_id,faculty_id,decision,feedback,quality) VALUES (?,?,?,?,?)",version.get("id"),access.user().id(),decision,feedback,quality);
        db.update("UPDATE evidence SET status=?,updated_at=now() WHERE id=?",decision,id);
        String status=decision.equals("Approved")?"Done":decision.equals("Rejected")?"Blocked":"In progress"; String reason="Evidence rejected: "+feedback; String blocker=decision.equals("Rejected")?reason.substring(0,Math.min(1000,reason.length())):"";
        db.update("UPDATE tasks SET status=?,blocker=?,version=version+1,updated_at=now() WHERE id=?",status,blocker,evidence.get("task_id"));
        long student=((Number)evidence.get("student_id")).longValue(); tasks.history((String)evidence.get("task_id"),"Evidence reviewed","Evidence version "+expected+": "+decision+"."); access.audit(project,"Evidence reviewed","evidence",id,"Version "+expected+" received a faculty decision: "+decision+".",student);
        db.update("INSERT INTO notifications(user_id,project_id,title,body) VALUES (?,?,?,?)",student,project,"Evidence "+decision.toLowerCase(Locale.ROOT),feedback.isEmpty()?"Your faculty guide approved the evidence.":feedback); return Map.of("evidence",item(id));
    }
    @GetMapping("/evidence/{id}/history") Map<String,Object> history(@PathVariable String id) {
        authorized(id); return Map.of("versions",db.query("SELECT v.version,v.title,v.note,v.work_link,v.submitted_at,v.original_filename,(v.stored_filename IS NOT NULL) AS has_file,r.decision,r.feedback,r.quality,r.reviewed_at FROM evidence_versions v LEFT JOIN evidence_reviews r ON r.evidence_version_id=v.id WHERE v.evidence_id=? ORDER BY v.version DESC",id).stream().map(Db::dto).toList());
    }
    @GetMapping("/evidence/{id}/file") ResponseEntity<FileSystemResource> download(@PathVariable String id,@RequestParam(required=false) Integer version) {
        var evidence=authorized(id); int requested=version==null?((Number)evidence.get("current_version")).intValue():version;
        var file=db.one("SELECT original_filename,stored_filename,content_type FROM evidence_versions WHERE evidence_id=? AND version=?",id,requested);
        if(file.get("stored_filename")==null) throw new ApiException(404,"This evidence version has no uploaded file.");
        Path path=uploads.resolve((String)file.get("stored_filename")).normalize(); if(!path.getParent().equals(uploads) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new ApiException(404,"The evidence file is unavailable.");
        String original=(String)file.get("original_filename");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(original==null?"evidence":original,StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(path));
    }
    private void notifyFaculty(String project,String title,String body) { db.update("INSERT INTO notifications(user_id,project_id,title,body) SELECT faculty_owner_id,id,?,? FROM projects WHERE id=?",title,body,project); }
}
