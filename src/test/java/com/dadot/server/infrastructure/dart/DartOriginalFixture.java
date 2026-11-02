package com.dadot.server.infrastructure.dart;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DartOriginalFixture {
    public static final String RECEIPT = "20260101000001";
    public static final String CORP = "99990028";
    private DartOriginalFixture() {}
    public static String xml(String body) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><DOCUMENT><DOCUMENT-NAME ACODE=\"11011\">사업보고서</DOCUMENT-NAME>"
                + "<COMPANY-NAME AREGCIK=\"" + CORP + "\">테스트기업</COMPANY-NAME><BODY>" + body + "</BODY></DOCUMENT>";
    }
    public static byte[] zip(String body) { return archive(Map.of(RECEIPT + ".xml", xml(body))); }
    public static byte[] archive(Map<String, String> entries) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (java.io.IOException ex) { throw new AssertionError(ex); }
    }
}
