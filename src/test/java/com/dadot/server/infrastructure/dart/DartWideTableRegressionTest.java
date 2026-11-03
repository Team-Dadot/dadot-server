package com.dadot.server.infrastructure.dart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static com.dadot.server.infrastructure.dart.DartOriginalFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class DartWideTableRegressionTest {
    @TempDir Path directory;
    private final DartArchiveExtractor extractor = new DartArchiveExtractor();

    @Test void preservesWideMergedCellAndItsEvidenceCoordinates() throws Exception {
        Path file = write("wide.zip", "<TABLE><TR><TH>구분</TH>"
                + "<TH COLSPAN=\"288\">거래상대방</TH></TR></TABLE>");
        var result = extractor.extract(file, RECEIPT, CORP, "ANNUAL");
        String text = result.blocks().stream().map(b -> b.text())
                .collect(java.util.stream.Collectors.joining());
        assertEquals("구분\t" + String.join("\t", java.util.Collections.nCopies(288, "거래상대방")), text);
        assertTrue(result.blocks().size() > 1);
        for (var block : result.blocks()) {
            var locator = block.locator();
            assertEquals(289, locator.get("columnCount"));
            assertEquals(1, locator.get("rowCount"));
            int start = (Integer) locator.get("textStart");
            assertEquals(block.text(), text.substring(start, (Integer) locator.get("textEnd")));
            @SuppressWarnings("unchecked")
            var cells = (List<Map<String, Object>>) locator.get("cells");
            assertTrue(cells.size() <= 256);
            for (var cell : cells) {
                int column = (Integer) cell.get("column");
                assertEquals(column == 1 ? 1 : 288, cell.get("columnSpan"));
                assertEquals(column == 1 ? "구분" : "거래상대방",
                        text.substring((Integer) cell.get("textStart"), (Integer) cell.get("textEnd")));
            }
        }
    }

    @Test void keepsColumnAndMergedGridBoundsEnforced() throws Exception {
        assertDoesNotThrow(() -> extractor.extract(write("limit.zip", "<TABLE><TR><TD COLSPAN=\"512\">값</TD></TR></TABLE>"), RECEIPT, CORP, "ANNUAL"));
        for (String body : List.of(
                "<TABLE><TR><TD COLSPAN=\"513\">초과</TD></TR></TABLE>",
                "<TABLE><TR><TD>첫칸</TD><TD COLSPAN=\"512\">초과</TD></TR></TABLE>",
                "<TABLE><TR><TD COLSPAN=\"0\">오류</TD></TR></TABLE>",
                "<TABLE><TR><TD ROWSPAN=\"2\" COLSPAN=\"288\">오류</TD></TR></TABLE>")) {
            var ex = assertThrows(DartFailure.class, () -> extractor.extract(write("invalid.zip", body), RECEIPT, CORP, "ANNUAL"));
            assertEquals("DART_INVALID_TABLE", ex.code());
        }
    }

    @Test void retainsFiveThousandRowSupportAndTableExpansionLimit() throws Exception {
        assertDoesNotThrow(() -> extractor.extract(write("rows.zip", "<TABLE>" + "<TR><TD>값</TD></TR>".repeat(5000) + "</TABLE>"), RECEIPT, CORP, "ANNUAL"));
        var ex = assertThrows(DartFailure.class, () -> extractor.extract(write("expansion.zip",
                "<TABLE><TR><TD ROWSPAN=\"196\" COLSPAN=\"512\">값</TD></TR>" + "<TR></TR>".repeat(195) + "</TABLE>"), RECEIPT, CORP, "ANNUAL"));
        assertEquals("DART_INVALID_TABLE", ex.code());
    }

    private Path write(String name, String body) throws Exception {
        Path file = directory.resolve(name);
        Files.write(file, zip(body));
        return file;
    }
}