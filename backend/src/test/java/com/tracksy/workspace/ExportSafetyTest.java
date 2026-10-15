package com.tracksy.workspace;

import org.junit.jupiter.api.Test;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import static org.junit.jupiter.api.Assertions.*;

class ExportSafetyTest {
    @Test void csvCannotInterpretUntrustedNamesAsFormulas() {
        for(String value:List.of("=HYPERLINK(\"https://bad\")","+cmd","-cmd","@SUM(A1)","\tformula","\rformula","  =1+1")) assertTrue(ReportController.csvCell(value).startsWith("\"'"));
        assertEquals("\"Normal \"\"name\"\"\"",ReportController.csvCell("Normal \"name\""));
    }
    @Test void pdfPreservesUnicodeNamesAndPaginatesLongReports() throws Exception {
        var lines=new java.util.ArrayList<>(java.util.Collections.nCopies(120,"Name (with parentheses) \\ path"));
        lines.set(0,"Élodie García — Αλέξανδρος");
        try(var document=Loader.loadPDF(SimplePdf.create(lines))) {
            assertEquals(3,document.getNumberOfPages());
            String extracted=new PDFTextStripper().getText(document);
            assertTrue(extracted.contains("Élodie García — Αλέξανδρος"),extracted);
            assertTrue(extracted.contains("Name (with parentheses) \\ path"));
            assertTrue(extracted.contains("Tracksy | Page 3 of 3"));
        }
    }
    @Test void repositoryIdentifiersCannotInjectAnotherNetworkHostOrApiRoute() {
        for(String owner:List.of("https://evil.test","../evil","name/name","name?","-bad")) assertThrows(com.tracksy.ApiException.class,()->RepositoryController.githubOwner(owner));
        for(String name:List.of("../evil","repo?x=1","repo/path","repo.git")) assertThrows(com.tracksy.ApiException.class,()->RepositoryController.githubRepository(name));
    }
}
