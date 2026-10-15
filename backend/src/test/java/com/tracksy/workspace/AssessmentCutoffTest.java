package com.tracksy.workspace;

import com.tracksy.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssessmentCutoffTest {
    @Test void completedAndArchivedProjectsRetainTheirOriginalCutoff() {
        Instant closed=Instant.parse("2026-09-30T12:00:00Z");
        for(String status:List.of("Completed","Archived"))
            assertEquals(closed,WorkspaceController.assessmentCutoff(Map.of("status",status,"assessment_closed_at",Timestamp.from(closed))));
    }
    @Test void anOpenProjectUsesTheCurrentAssessmentTime() {
        Instant before=Instant.now();
        Instant result=WorkspaceController.assessmentCutoff(Map.of("status","Active"));
        Instant after=Instant.now();
        assertFalse(result.isBefore(before)); assertFalse(result.isAfter(after));
    }
    @Test void repositoryFreshnessUsesTheProvidedCutoffOnBothBounds() {
        class CapturingDb extends Db {
            Object[] parameters; String query;
            CapturingDb(){super(null);}
            @Override public List<Map<String,Object>> query(String sql,Object... args){query=sql;parameters=args;return List.of();}
        }
        CapturingDb db=new CapturingDb();
        Instant closed=Instant.parse("2026-09-30T12:00:00Z");
        assertFalse(new WorkspaceController(db,null,new ObjectMapper()).repositoryReady("project",closed));
        assertArrayEquals(new Object[]{"project",closed.atOffset(ZoneOffset.UTC),closed.atOffset(ZoneOffset.UTC)},db.parameters);
        assertTrue(db.query.contains("last_synced_at<=?::timestamptz"));
        assertFalse(db.query.contains("now()"));
    }
}
