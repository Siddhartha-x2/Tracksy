package com.tracksy.workspace;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

/** Unicode PDF export using an embedded, bundled font. No browser or external call is required. */
final class SimplePdf {
    private SimplePdf() {}
    static byte[] create(List<String> input) {
        try(var document=new PDDocument(); var fontStream=SimplePdf.class.getResourceAsStream("/fonts/NotoSans-Regular.ttf")) {
            if(fontStream==null) throw new IOException("Bundled report font is missing");
            PDType0Font font=PDType0Font.load(document,fontStream,true);
            List<String> lines=new ArrayList<>();
            for(String source:input) for(String paragraph:source.split("\\R",-1)) wrap(supportedText(paragraph,font),font,10,515,lines);
            int pages=Math.max(1,(lines.size()+49)/50);
            for(int pageIndex=0;pageIndex<pages;pageIndex++) {
                var page=new PDPage(PDRectangle.A4); document.addPage(page);
                try(var content=new PDPageContentStream(document,page)) {
                    content.beginText(); content.setFont(font,10); content.setLeading(14); content.newLineAtOffset(40,795);
                    for(int index=pageIndex*50;index<Math.min(lines.size(),(pageIndex+1)*50);index++) {
                        content.showText(lines.get(index)); content.newLine();
                    }
                    content.endText();
                    content.beginText(); content.setFont(font,9); content.newLineAtOffset(40,35);
                    content.showText("Tracksy | Page "+(pageIndex+1)+" of "+pages); content.endText();
                }
            }
            document.getDocumentInformation().setTitle("Tracksy contribution report");
            document.getDocumentInformation().setAuthor("Tracksy");
            var output=new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        } catch(IOException failure) { throw new UncheckedIOException("Could not generate the report PDF",failure); }
    }

    private static String supportedText(String value,PDType0Font font) throws IOException {
        var result=new StringBuilder();
        for(int codePoint:value.codePoints().toArray()) {
            if(Character.isISOControl(codePoint)) result.append(' ');
            else {
                String character=new String(Character.toChars(codePoint));
                try { font.encode(character); result.append(character); }
                catch(IllegalArgumentException unavailableGlyph) { result.append('?'); }
            }
        }
        return result.toString();
    }

    private static void wrap(String text,PDType0Font font,float size,float available,List<String> output) throws IOException {
        if(text.isEmpty()) { output.add(""); return; }
        while(font.getStringWidth(text)/1000*size>available) {
            int boundary=0,lastSpace=-1;
            for(int offset=0;offset<text.length();) {
                int next=offset+Character.charCount(text.codePointAt(offset));
                if(font.getStringWidth(text.substring(0,next))/1000*size>available) break;
                boundary=next;
                if(Character.isWhitespace(text.codePointAt(offset))) lastSpace=offset;
                offset=next;
            }
            if(boundary==0) boundary=Character.charCount(text.codePointAt(0));
            if(lastSpace>0) boundary=lastSpace;
            output.add(text.substring(0,boundary).stripTrailing()); text=text.substring(boundary).stripLeading();
        }
        output.add(text);
    }
}
