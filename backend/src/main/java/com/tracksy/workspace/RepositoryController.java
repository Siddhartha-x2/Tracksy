package com.tracksy.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracksy.Access;
import com.tracksy.ApiException;
import com.tracksy.Db;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import static com.tracksy.workspace.WorkspaceController.*;

@RestController
@RequestMapping("/api/projects/{projectId}/repository")
public class RepositoryController {
    private final Db db;
    private final Access access;
    private final WorkspaceController workspace;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public RepositoryController(Db db,Access access,WorkspaceController workspace,ObjectMapper json) { this.db=db; this.access=access; this.workspace=workspace; this.json=json; }
    public record IdentityInput(Long userId,String githubUsername,Boolean verified) {}
    public record RepositoryInput(String owner,String name,List<IdentityInput> mappings,String url) {}

    @GetMapping("/identities")
    public Map<String,Object> verifiedIdentities(@PathVariable String projectId) {
        access.faculty(projectId);
        return Map.of("identities",dtos(db.query("SELECT u.id user_id,u.name,g.github_username,true verified,g.verified_at FROM user_github_identities g JOIN users u ON u.id=g.user_id JOIN project_members m ON m.user_id=u.id WHERE m.project_id=? AND m.active AND u.enabled AND u.role='student' ORDER BY u.name,u.id",projectId)));
    }

    @GetMapping
    public Map<String,Object> repository(@PathVariable String projectId) {
        access.member(projectId); boolean faculty=workspace.facultyView(projectId);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("available",true); result.put("mode","public-read-only"); result.put("oauthConnected",!db.query("SELECT user_id FROM user_github_identities WHERE user_id=?",access.user().id()).isEmpty());
        result.put("scoringAvailable",workspace.repositoryReadyForView(projectId));
        result.put("notice","Public repository sync reads GitHub data only. Scoring requires GitHub verified signatures and matching author/committer account IDs owned by the student through OAuth. Unsigned commits remain activity records and earn no repository score. No code is pushed. Sync covers up to 300 default-branch commits since the project start.");
        var rows=db.query("SELECT * FROM repository_integrations WHERE project_id=?",projectId);
        if(rows.isEmpty()) { result.put("configured",false); result.put("status","Not configured"); result.put("mappings",List.of()); return result; }
        var repo=rows.getFirst(); result.putAll(Db.dto(repo)); result.put("configured",true);
        result.put("url","https://github.com/"+repo.get("owner")+"/"+repo.get("name"));
        var mappings=db.query("SELECT i.user_id,u.name student_name,i.github_username,i.verified_at,(g.user_id IS NOT NULL) oauth_verified,true faculty_verified,(SELECT count(*) FROM repository_commits c WHERE c.project_id=i.project_id AND lower(c.github_username)=lower(i.github_username)) commit_count,(SELECT count(*) FROM repository_commits c WHERE c.project_id=i.project_id AND c.signature_verified AND c.github_author_id=g.github_id AND c.github_committer_id=g.github_id) verified_commit_count FROM repository_identities i JOIN users u ON u.id=i.user_id LEFT JOIN user_github_identities g ON g.user_id=i.user_id AND lower(g.github_username)=lower(i.github_username) WHERE i.project_id=?"+(faculty?"":" AND i.user_id=?")+" ORDER BY u.name",faculty?new Object[]{projectId}:new Object[]{projectId,access.user().id()});
        result.put("mappings",dtos(mappings)); return result;
    }

    @PutMapping
    @Transactional
    public Map<String,Object> configure(@PathVariable String projectId,@RequestBody RepositoryInput input) {
        access.faculty(projectId); workspace.writable(projectId);
        db.one("SELECT id FROM projects WHERE id=? FOR UPDATE",projectId);
        @SuppressWarnings("unchecked") var weights=(Map<String,Integer>)workspace.rubricData(projectId).get("weights");
        if(weights.get("repository")>0) conflict("Set repository rubric weight to 0 before changing repository configuration or mappings");
        String owner=githubOwner(input.owner()),name=githubRepository(input.name());
        if(input.url()!=null) {
            try {
                var uri=URI.create(input.url());
                if(!"https".equals(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().replaceAll("/$","").equalsIgnoreCase("/"+owner+"/"+name)) throw new IllegalArgumentException();
            } catch(Exception invalidUrl) { bad("Repository URL must be the matching HTTPS github.com owner/repository address"); }
        }
        var mappings=input.mappings()==null?List.<IdentityInput>of():input.mappings();
        if(mappings.size()>100) bad("At most 100 account mappings are supported");
        Set<Long> users=new HashSet<>(); Set<String> usernames=new HashSet<>();
        for(var mapping:mappings) {
            if(mapping.userId()==null || !users.add(mapping.userId())) bad("Each student can have only one repository account mapping");
            String username=githubOwner(mapping.githubUsername()).toLowerCase(Locale.ROOT);
            if(!usernames.add(username)) bad("A GitHub account cannot be mapped to multiple students");
            if(!Boolean.TRUE.equals(mapping.verified())) bad("Confirm the selected OAuth verified identity mapping");
            if(db.query("SELECT g.user_id FROM user_github_identities g JOIN project_members m ON m.user_id=g.user_id JOIN users u ON u.id=g.user_id WHERE m.project_id=? AND m.active AND u.enabled AND u.role='student' AND g.user_id=? AND lower(g.github_username)=?",projectId,mapping.userId(),username).isEmpty()) bad("Mapping must match the active student's GitHub account verified through OAuth");
        }
        db.update("INSERT INTO repository_integrations(project_id,owner,name) VALUES(?,?,?) ON CONFLICT(project_id) DO UPDATE SET owner=excluded.owner,name=excluded.name,status='Configured',sync_error=NULL,last_synced_at=NULL,inspected_commits=0,sample_truncated=false,updated_at=now()",projectId,owner,name);
        db.update("DELETE FROM repository_identities WHERE project_id=?",projectId);
        db.update("DELETE FROM repository_commits WHERE project_id=?",projectId);
        for(var mapping:mappings) db.update("INSERT INTO repository_identities(project_id,user_id,github_username,verified_by) VALUES(?,?,?,?)",projectId,mapping.userId(),mapping.githubUsername().trim().toLowerCase(Locale.ROOT),access.user().id());
        access.audit(projectId,"Repository configured","repository",projectId,"Configured public repository "+owner+"/"+name+" with "+mappings.size()+" OAuth verified mappings",null);
        return repository(projectId);
    }

    @PostMapping("/sync")
    @Transactional(timeout=45)
    public Map<String,Object> sync(@PathVariable String projectId) {
        access.faculty(projectId); workspace.writable(projectId);
        var repo=workspace.required("SELECT * FROM repository_integrations WHERE project_id=? FOR UPDATE",projectId);
        if(repo.get("last_synced_at") instanceof java.sql.Timestamp timestamp && timestamp.toInstant().isAfter(Instant.now().minusSeconds(300))) conflict("Wait 5 minutes before requesting another public sync");
        String base="https://api.github.com/repos/"+githubOwner((String)repo.get("owner"))+"/"+githubRepository((String)repo.get("name"));
        var project=db.one("SELECT start_date,created_at FROM projects WHERE id=?",projectId);
        LocalDate start=project.get("start_date")!=null?date(project.get("start_date")):date(project.get("created_at"));
        String since=start.atStartOfDay().atOffset(ZoneOffset.UTC).toString();
        List<Commit> commits=new ArrayList<>(); boolean truncated=false;
        try {
            JsonNode metadata=fetch(base);
            if(metadata==null || !metadata.isObject()) throw new ApiException(502,"GitHub returned invalid repository metadata");
            if(metadata.path("private").asBoolean(true)) throw new ApiException(400,"Only public repositories are supported");
            for(int page=1;page<=3;page++) {
                JsonNode batch=fetch(base+"/commits?per_page=100&page="+page+"&since="+since);
                if(!batch.isArray()) throw new ApiException(502,"GitHub returned an unexpected commit response");
                for(JsonNode item:batch) {
                    String sha=item.path("sha").asText();
                    if(!sha.matches("[0-9a-f]{40,64}")) continue;
                    String username=item.path("author").path("login").asText(null);
                    String message=item.path("commit").path("message").asText("");
                    OffsetDateTime committed;
                    try { committed=OffsetDateTime.parse(item.path("commit").path("author").path("date").asText()); } catch(Exception invalidDate) { continue; }
                    if(committed.toLocalDate().isBefore(start) || committed.toInstant().isAfter(Instant.now().plusSeconds(300))) continue;
                    if(username!=null && !username.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")) username=null;
                    Long authorId=item.path("author").path("id").isIntegralNumber()?item.path("author").path("id").longValue():null;
                    Long committerId=item.path("committer").path("id").isIntegralNumber()?item.path("committer").path("id").longValue():null;
                    boolean verified=item.path("commit").path("verification").path("verified").asBoolean(false) && "valid".equals(item.path("commit").path("verification").path("reason").asText());
                    commits.add(new Commit(sha,username==null?null:username.toLowerCase(Locale.ROOT),authorId,committerId,verified,committed,message.substring(0,Math.min(message.length(),500))));
                }
                if(batch.size()<100) break;
                if(page==3) truncated=true;
            }
        } catch(ApiException error) {
            db.update("UPDATE repository_integrations SET status='Error',sync_error=?,updated_at=now() WHERE project_id=?",error.getMessage(),projectId);
            // Return a visible integration state; throwing would roll back the stored failure state.
            var result=repository(projectId); result.put("syncSucceeded",false); return result;
        }
        db.update("DELETE FROM repository_commits WHERE project_id=?",projectId);
        for(var commit:commits) db.update("INSERT INTO repository_commits(project_id,sha,github_username,github_author_id,github_committer_id,signature_verified,committed_at,message) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",projectId,commit.sha(),commit.username(),commit.authorId(),commit.committerId(),commit.signatureVerified(),commit.committed(),commit.message());
        db.update("UPDATE repository_integrations SET status='Synced',sync_error=NULL,last_synced_at=now(),inspected_commits=?,sample_truncated=?,updated_at=now() WHERE project_id=?",commits.size(),truncated,projectId);
        access.audit(projectId,"Public repository synced","repository",projectId,"Read "+commits.size()+" commits from GitHub; bounded sample truncated: "+truncated,null);
        var result=repository(projectId); result.put("syncSucceeded",true); return result;
    }

    private record Commit(String sha,String username,Long authorId,Long committerId,boolean signatureVerified,OffsetDateTime committed,String message) {}
    private JsonNode fetch(String url) {
        URI uri=URI.create(url);
        if(!"https".equals(uri.getScheme()) || !"api.github.com".equals(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null) throw new ApiException(400,"GitHub API host required");
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2026-03-10").header("User-Agent","Tracksy-project-audit").GET().build();
        try {
            var response=http.send(request,info->new LimitedBody());
            if(response.statusCode()!=200) throw new ApiException(response.statusCode()==404?400:502,switch(response.statusCode()) { case 404 -> "Public repository not found"; case 403,429 -> "GitHub rate limit reached; try later"; case 409 -> "Repository has no readable commits"; default -> "GitHub public sync failed with status "+response.statusCode(); });
            return json.readTree(response.body());
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ApiException(502,"GitHub sync interrupted"); }
        catch(IOException error) { throw new ApiException(502,"GitHub is unavailable or timed out"); }
    }
    /** Enforce the body size while receiving it, with HttpClient's whole-response timeout. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription incoming) { subscription=incoming; incoming.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(bytes.size()+buffer.remaining()>4_000_000) { subscription.cancel(); result.completeExceptionally(new IOException("GitHub response exceeds safe size")); return; }
                byte[] chunk=new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    static String githubOwner(String owner) {
        if(owner==null || !owner.trim().matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")) bad("Use a GitHub owner or username, 1–39 letters, numbers or internal hyphens");
        return owner.trim();
    }
    static String githubRepository(String name) {
        if(name==null || !name.trim().matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}") || name.trim().endsWith(".git")) bad("Use a GitHub repository name, without a URL or .git suffix");
        return name.trim();
    }
}
