package com.dadot.server.infrastructure.dart;

import com.dadot.server.domain.disclosure.model.DisclosureOriginal.Extracted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static com.dadot.server.infrastructure.dart.DartOriginalFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class DartArchiveExtractorTest {
    @TempDir Path directory;
    private final DartArchiveExtractor extractor = new DartArchiveExtractor();

    @Test void preservesDartTextAndOnlyDropsPresentationAttributes() throws Exception {
        var document = extract(zip("<SECTION-1><TITLE>사업의 내용</TITLE><P>R&D <녹색기술 인증 현황> <CAPEX> &nbsp; &amp; 순이익</P>"
                + "<TABLE><TR><TH ENG=\"\"Other receivables and others\" WIDTH=\"100\">매출</TH></TR></TABLE></SECTION-1>"));
        assertTrue(text(document).contains("R&D <녹색기술 인증 현황> <CAPEX> & 순이익"));
        assertTrue(text(document).contains("매출"));
        assertFalse(text(document).contains("Other receivables"));
        assertEquals("사업의 내용", document.blocks().get(1).heading());
        assertEquals("/DOCUMENT[1]/BODY[1]/SECTION-1[1]/P[1]", document.blocks().get(1).locator().get("path"));
    }
    @Test void selectsMainDocumentWithoutMixingAuditAttachments() throws Exception {
        var document = extract(archive(Map.of(RECEIPT + ".xml", xml("<P>사업 본문</P>"),
                RECEIPT + "_00760.xml", xml("<P>감사보고서 첨부</P>").replace("11011", "00760"))));
        assertEquals("사업 본문", text(document)); assertEquals(RECEIPT + ".xml", document.entryName());
    }
    @Test void identifiesUniqueFallbackDocumentAndRejectsAmbiguity() throws Exception {
        var original = xml("<P>사업 본문</P>");
        assertEquals("사업 본문", text(extract(archive(Map.of("main.xml", original, "audit.xml", original.replace("11011", "00760"))))));
        assertEquals("DART_AMBIGUOUS_DOCUMENT", error(archive(Map.of("one.xml", original, "two.xml", original))).code());
    }
    @Test void doesNotHideWrongMainDocumentWithAnotherMatchingAttachment() throws Exception {
        assertEquals("DART_WRONG_DOCUMENT", error(archive(Map.of(RECEIPT + ".xml", xml("<P>타기업</P>").replace(CORP, "11111111"),
                "attachment.xml", xml("<P>첨부</P>")))).code());
    }
    @Test void retainsMergedTableCoordinatesAndNumericCellTypes() throws Exception {
        var result = extract(zip("<TABLE><THEAD><TR><TH ROWSPAN=\"2\">구분</TH><TH COLSPAN=\"2\">매출</TH></TR>"
                + "<TR><TH>2024</TH><TH>2025</TH></TR></THEAD><TBODY><TR><TD>반도체</TD><TE>10</TE><TU>20</TU></TR></TBODY></TABLE>"));
        assertEquals("구분\t매출\t매출\n구분\t2024\t2025\n반도체\t10\t20", text(result));
        var locator = result.blocks().getFirst().locator();
        assertEquals(3, locator.get("rowCount")); assertEquals(3, locator.get("columnCount"));
        assertTrue(locator.get("cells").toString().contains("rowSpan=2"));
        assertTrue(locator.get("cells").toString().contains("columnSpan=2"));
    }
    @Test void treatsNestedTableAsContentOfItsParentCell() throws Exception {
        var result = extract(zip("<TABLE><TR><TD>바깥<TABLE><TR><TD>안쪽</TD><TD>10</TD></TR></TABLE></TD><TD>20</TD></TR></TABLE>"));
        assertEquals(1, result.blocks().size());
        assertTrue(text(result).contains("안쪽 | 10")); assertTrue(text(result).endsWith("\t20"));
    }
    @Test void splitsLargeParagraphWithoutLosingCharactersOrBreakingSurrogates() throws Exception {
        String input = "한글" + "😀가".repeat(6000);
        var result = extract(zip("<P>" + input + "</P>"));
        assertTrue(result.blocks().size() > 1);
        assertEquals(input, result.blocks().stream().map(b -> b.text()).collect(java.util.stream.Collectors.joining()));
        int offset = 0;
        for (var block : result.blocks()) {
            assertTrue(block.text().length() <= DartArchiveExtractor.MAX_BLOCK);
            assertFalse(Character.isHighSurrogate(block.text().charAt(block.text().length() - 1)));
            assertEquals(offset, block.locator().get("textStart")); offset += block.text().length();
            assertEquals(offset, block.locator().get("textEnd"));
        }
    }
    @Test void cdataAndBareBodyTextRemainLiteralAndInOrder() throws Exception {
        assertEquals("앞부분\nR&D <literal>\n뒷부분", text(extract(zip("앞부분<P><![CDATA[R&D <literal>]]></P>뒷부분"))));
    }
    @Test void acceptsLineBreaksBetweenPresentationAttributesAndTagEnd() throws Exception {
        assertEquals("원문", text(extract(zip("<P WIDTH=\"100\"\n>원문</P>"))));
    }
    @Test void decodesTrademarkEntityObservedInActualDartFiles() throws Exception {
        assertEquals("브랜드®", text(extract(zip("<P>브랜드&reg;</P>"))));
    }
    @Test void hashesUnmodifiedMainXmlBytes() throws Exception {
        String source = xml("<P>R&D <사업 내용></P>");
        var result = extract(archive(Map.of(RECEIPT + ".xml", source)));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))), result.contentHash());
    }
    @Test void readsUtf8BomAndStrictKoreanEncoding() throws Exception {
        String value = xml("<P>한글 원문</P>");
        assertEquals("한글 원문", text(extract(archive(Map.of(RECEIPT + ".xml", "\ufeff" + value)))));
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(RECEIPT + ".xml"));
            zip.write(value.replace("utf-8", "euc-kr").getBytes(java.nio.charset.Charset.forName("EUC-KR"))); zip.closeEntry();
        }
        assertEquals("한글 원문", text(extract(bytes.toByteArray())));
    }
    @Test void blocksDoctypeAndExternalEntityWithoutReadingItsTarget() throws Exception {
        Path secret = directory.resolve("canary.txt"); Files.writeString(secret, "must-not-be-read");
        String value = xml("<P>&outside;</P>").replace("<DOCUMENT>", "<!DOCTYPE DOCUMENT [<!ENTITY outside SYSTEM \"" + secret.toUri() + "\">]><DOCUMENT>");
        var failure = error(archive(Map.of(RECEIPT + ".xml", value)));
        assertEquals("DART_INVALID_XML", failure.code()); assertFalse(failure.toString().contains("must-not-be-read"));
    }
    @ParameterizedTest @ValueSource(strings = {"../main.xml", "/main.xml", "C:/main.xml", "path\\main.xml"})
    void rejectsUnsafeArchiveEntryNames(String name) throws Exception {
        assertEquals("DART_INVALID_ZIP", error(archive(Map.of(name, xml("<P>원문</P>")))).code());
    }
    @ParameterizedTest @ValueSource(strings = {"<P>닫히지 않음", "<TABLE><TR><TD ROWSPAN=\"2\">범위 오류</TD></TR></TABLE>",
            "<TABLE><TR><TD COLSPAN=\"0\">잘못된 셀</TD></TR></TABLE>", "<P>빈 인용 &#0;</P>"})
    void doesNotSilentlyRepairStructuralOrSemanticErrors(String body) throws Exception { error(zip(body)); }
    @Test void rejectsEmptyBodyAndMismatchedCompanyOrReport() throws Exception {
        error(zip(""));
        assertEquals("DART_WRONG_DOCUMENT", error(archive(Map.of(RECEIPT + ".xml", xml("<P>원문</P>").replace(CORP, "11111111")))).code());
        assertEquals("DART_WRONG_DOCUMENT", error(archive(Map.of(RECEIPT + ".xml", xml("<P>원문</P>").replace("11011", "11012")))).code());
    }
    @Test void rejectsExcessiveDepthAndArchiveSize() throws Exception {
        error(zip("<SECTION-1>".repeat(130) + "<P>원문</P>" + "</SECTION-1>".repeat(130)));
        Path huge = directory.resolve("huge.zip");
        try (var channel = java.nio.channels.FileChannel.open(huge, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(20L * 1024 * 1024); channel.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        assertThrows(DartFailure.class, () -> extractor.extract(huge, RECEIPT, CORP, "ANNUAL"));
    }
    @Test void boundsTableLocatorCellsPerBlockWithoutDroppingTableText() throws Exception {
        String row = "<TR>" + "<TD>값</TD>".repeat(100) + "</TR>";
        var result = extract(zip("<TABLE>" + row.repeat(10) + "</TABLE>"));
        assertTrue(result.blocks().size() >= 4);
        String joined = result.blocks().stream().map(b -> b.text()).collect(java.util.stream.Collectors.joining());
        assertEquals(String.join("\n", java.util.Collections.nCopies(10, String.join("\t", java.util.Collections.nCopies(100, "값")))), joined);
        for (var block : result.blocks()) assertTrue(((java.util.List<?>) block.locator().get("cells")).size() <= 256);
    }
    @Test void stopsTableSpanAmplificationBeforeUnboundedLocatorAllocation() throws Exception {
        String table = "<TABLE><TR><TD ROWSPAN=\"1000\" COLSPAN=\"100\">값</TD></TR>" + "<TR></TR>".repeat(999) + "</TABLE>";
        assertEquals("DART_DOCUMENT_LIMIT", error(zip(table.repeat(3))).code());
    }
    @Test void rejectsCrcMismatchInsteadOfAcceptingChangedArchiveContent() throws Exception {
        byte[] raw = xml("<P>KEEP</P>").getBytes(StandardCharsets.UTF_8);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            var entry = new java.util.zip.ZipEntry(RECEIPT + ".xml"); var crc = new java.util.zip.CRC32(); crc.update(raw);
            entry.setMethod(java.util.zip.ZipEntry.STORED); entry.setSize(raw.length); entry.setCompressedSize(raw.length); entry.setCrc(crc.getValue());
            zip.putNextEntry(entry); zip.write(raw); zip.closeEntry();
        }
        byte[] archive = bytes.toByteArray(); replaceAscii(archive, "KEEP", "RISK");
        assertEquals("DART_INVALID_XML", error(archive).code());
    }
    @Test void rejectsDuplicateArchiveNames() throws Exception {
        byte[] archive = archive(Map.of("one.xml", xml("<P>원문</P>"), "two.xml", xml("<P>다른 원문</P>")));
        replaceAscii(archive, "two.xml", "one.xml");
        assertEquals("DART_INVALID_ZIP", error(archive).code());
    }
    @Test void readsUtf16WithoutChangingRawContentHash() throws Exception {
        String source = xml("<P>UTF16 한글</P>").replace("utf-8", "UTF-16");
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(RECEIPT + ".xml")); zip.write(source.getBytes(StandardCharsets.UTF_16)); zip.closeEntry();
        }
        assertEquals("UTF16 한글", text(extract(bytes.toByteArray())));
    }
    private void replaceAscii(byte[] bytes, String old, String replacement) {
        byte[] from = old.getBytes(StandardCharsets.US_ASCII), to = replacement.getBytes(StandardCharsets.US_ASCII);
        assertEquals(from.length, to.length);
        for (int i = 0; i <= bytes.length - from.length; i++) {
            boolean matches = true; for (int j = 0; j < from.length; j++) if (bytes[i + j] != from[j]) { matches = false; break; }
            if (matches) System.arraycopy(to, 0, bytes, i, to.length);
        }
    }
    private Extracted extract(byte[] archive) throws Exception {
        Path file = directory.resolve("original.zip"); Files.write(file, archive); return extractor.extract(file, RECEIPT, CORP, "ANNUAL");
    }
    private DartFailure error(byte[] archive) throws Exception {
        Path file = directory.resolve("invalid.zip"); Files.write(file, archive);
        return assertThrows(DartFailure.class, () -> extractor.extract(file, RECEIPT, CORP, "ANNUAL"));
    }
    private String text(Extracted result) { return result.blocks().stream().map(b -> b.text()).collect(java.util.stream.Collectors.joining("\n")); }
}
