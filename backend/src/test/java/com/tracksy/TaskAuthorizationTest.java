package com.tracksy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskAuthorizationTest {
    @Test void reassignmentDuringAnUpdateRevokesTheOldAssigneesWrite() {
        FakeDb db=new FakeDb(); FakeAccess access=new FakeAccess(db);
        ApiException error=assertThrows(ApiException.class,() -> new TasksController(db,access).update("task",Map.of("status","In progress")));
        assertEquals(404,error.status);
        assertEquals(0,db.updates);
    }
    @Test void commentsRecheckAccessAfterTheProjectWriteLock() {
        FakeDb db=new FakeDb(); FakeAccess access=new FakeAccess(db); access.revokeOnSecondLookup=true;
        ApiException error=assertThrows(ApiException.class,() -> new TasksController(db,access).comment("task",Map.of("body","A stale comment")));
        assertEquals(404,error.status);
        assertEquals(2,access.taskLookups);
        assertEquals(0,db.updates);
        assertEquals(0,db.reads);
    }
    private static final class FakeDb extends Db {
        int updates,reads;
        FakeDb() { super(null); }
        @Override public Map<String,Object> one(String sql,Object... args) {
            reads++;
            if(sql.equals("SELECT id FROM projects WHERE id=? FOR UPDATE"))return Map.of("id","project");
            if(sql.equals("SELECT * FROM tasks WHERE id=? FOR UPDATE"))return Map.of("id","task","project_id","project","assigned_to",2L);
            throw new AssertionError("Unexpected database read: "+sql);
        }
        @Override public int update(String sql,Object... args) { updates++; return 1; }
    }
    private static final class FakeAccess extends Access {
        int taskLookups; boolean revokeOnSecondLookup;
        FakeAccess(Db db) { super(db); }
        @Override public User user() {return new User(1,"Old assignee","old@example.test","student");}
        @Override public Map<String,Object> task(String id) {
            taskLookups++;
            if(revokeOnSecondLookup&&taskLookups>1)throw new ApiException(404,"Task not found.");
            return Map.of("id",id,"project_id","project","assigned_to",1L);
        }
        @Override public Map<String,Object> member(String project) { return Map.of("project_role","member"); }
        @Override public boolean canManage(Map<String,Object> membership) {return false;}
        @Override public void writable(String project) {}
    }
}
