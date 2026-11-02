package com.dadot.server.infrastructure.dart;

import com.dadot.server.domain.disclosure.model.DisclosureOriginal.Block;
import com.dadot.server.domain.disclosure.model.DisclosureOriginal.Extracted;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 네트워크·DB·원본 보관소와 분리된 추출기.
 */
public final class DartArchiveExtractor {

    public static final String VERSION = "dart-markup-v1";
    public static final int MAX_BLOCK = 8192;

    private static final int MAX_TEXT = 8_000_000;
    private static final long MAX_XML = 64L * 1024 * 1024;

    private static final Pattern ENCODING = Pattern.compile(
            "encoding\\s*=\\s*['\"]([A-Za-z0-9._-]+)['\"]",
            Pattern.CASE_INSENSITIVE
    );

    public Extracted extract(
            Path archive,
            String receipt,
            String corpCode,
            String type
    ) {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(25).toNanos();

        try {
            if (java.nio.file.Files.size(archive)
                    > 20L * 1024 * 1024) {
                throw failure("DART_DOCUMENT_LIMIT");
            }

        } catch (IOException ex) {
            throw failure("DART_INVALID_ZIP");
        }

        try (var zip = new ZipFile(
                archive.toFile(),
                StandardCharsets.UTF_8
        )) {
            var entries = new ArrayList<ZipEntry>();
            var names = new HashSet<String>();

            var all = zip.entries();
            int count = 0;
            long expanded = 0;

            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();

                if (++count > 2048
                        || name.length() > 512
                        || name.startsWith("/")
                        || name.contains("\\")
                        || name.indexOf('\0') >= 0
                        || name.matches("^[A-Za-z]:.*")
                        || !names.add(name)) {
                    throw failure("DART_INVALID_ZIP");
                }

                for (String part : name.split("/")) {
                    if (part.equals("..") || part.equals(".")) {
                        throw failure("DART_INVALID_ZIP");
                    }
                }

                if (!entry.isDirectory()
                        && name.toLowerCase(Locale.ROOT)
                        .endsWith(".xml")) {
                    if (entry.getSize() < 0
                            || entry.getSize() > MAX_XML
                            || (expanded += entry.getSize())
                            > 128L * 1024 * 1024) {
                        throw failure("DART_DOCUMENT_LIMIT");
                    }

                    entries.add(entry);
                }
            }

            if (entries.isEmpty() || entries.size() > 32) {
                throw failure("DART_INVALID_ZIP");
            }

            var preferred = entries.stream()
                    .filter(e ->
                            e.getName().equals(receipt + ".xml")
                                    || e.getName().endsWith(
                                    "/" + receipt + ".xml"
                            ))
                    .toList();

            if (preferred.size() > 1) {
                throw failure("DART_AMBIGUOUS_DOCUMENT");
            }

            if (preferred.size() == 1) {
                var result = parse(
                        zip,
                        preferred.getFirst(),
                        corpCode,
                        type,
                        deadline
                );

                if (result == null) {
                    throw failure("DART_WRONG_DOCUMENT");
                }

                return result;
            }

            Extracted selected = null;

            for (var entry : entries) {
                Extracted result = parse(
                        zip,
                        entry,
                        corpCode,
                        type,
                        deadline
                );

                if (result != null) {
                    if (selected != null) {
                        throw failure("DART_AMBIGUOUS_DOCUMENT");
                    }

                    selected = result;
                }
            }

            if (selected == null) {
                throw failure("DART_WRONG_DOCUMENT");
            }

            return selected;

        } catch (DartFailure ex) {
            throw ex;

        } catch (Exception ex) {
            throw failure("DART_INVALID_XML");
        }
    }

    private Extracted parse(
            ZipFile zip,
            ZipEntry entry,
            String corp,
            String type,
            long deadline
    ) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");

        try (
                var bytes = new CheckedBytes(
                        zip.getInputStream(entry),
                        deadline
                );
                var raw = new DigestInputStream(bytes, digest);
                var input = new PushbackInputStream(raw, 512)
        ) {
            byte[] prefix = input.readNBytes(512);
            input.unread(prefix);

            Charset charset = charset(prefix);

            var decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);

            var handler = new Handler(
                    entry.getName(),
                    corp,
                    type,
                    deadline
            );

            var factory = SAXParserFactory.newInstance();

            factory.setNamespaceAware(false);
            factory.setValidating(false);
            factory.setXIncludeAware(false);

            factory.setFeature(
                    XMLConstants.FEATURE_SECURE_PROCESSING,
                    true
            );
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true
            );
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false
            );
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false
            );
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false
            );

            var reader = factory.newSAXParser().getXMLReader();

            reader.setProperty(
                    XMLConstants.ACCESS_EXTERNAL_DTD,
                    ""
            );
            reader.setProperty(
                    XMLConstants.ACCESS_EXTERNAL_SCHEMA,
                    ""
            );

            reader.setEntityResolver((publicId, systemId) -> {
                throw new SAXException(
                        "External XML reference forbidden"
                );
            });

            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);

            try (var markup = new DartMarkupReader(
                    new BufferedReader(
                            new InputStreamReader(input, decoder),
                            16384
                    )
            )) {
                try {
                    reader.parse(new InputSource(markup));

                } catch (OtherDocument ex) {
                    return null;
                }
            }

            if (!handler.sawBody
                    || handler.blocks.isEmpty()
                    || bytes.count != entry.getSize()
                    || bytes.crc.getValue() != entry.getCrc()) {
                throw failure("DART_INVALID_XML");
            }

            return new Extracted(
                    entry.getName(),
                    HexFormat.of().formatHex(digest.digest()),
                    List.copyOf(handler.blocks)
            );
        }
    }

    private Charset charset(byte[] prefix) {
        if (prefix.length >= 2
                && ((prefix[0] == (byte) 0xff
                && prefix[1] == (byte) 0xfe)
                || (prefix[0] == (byte) 0xfe
                && prefix[1] == (byte) 0xff))) {
            return StandardCharsets.UTF_16;
        }

        String start = new String(
                prefix,
                StandardCharsets.US_ASCII
        );

        var match = ENCODING.matcher(start);

        String name = match.find()
                ? match.group(1).toUpperCase(Locale.ROOT)
                : "UTF-8";

        if (!Set.of(
                "UTF-8",
                "UTF8",
                "EUC-KR",
                "MS949",
                "CP949",
                "WINDOWS-949"
        ).contains(name)) {
            throw failure("DART_UNSUPPORTED_ENCODING");
        }

        return Charset.forName(
                name.equals("WINDOWS-949") ? "MS949" : name
        );
    }

    private static final class CheckedBytes
            extends FilterInputStream {

        private final long deadline;
        private final CRC32 crc = new CRC32();
        private long count;

        CheckedBytes(InputStream in, long deadline) {
            super(in);
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();

            if (value >= 0) {
                check(1);
                crc.update(value);
            }

            return value;
        }

        @Override
        public int read(byte[] data, int offset, int length)
                throws IOException {
            int n = in.read(data, offset, length);

            if (n > 0) {
                check(n);
                crc.update(data, offset, n);
            }

            return n;
        }

        private void check(int amount) {
            if ((count += amount) > MAX_XML
                    || System.nanoTime() > deadline) {
                throw failure("DART_DOCUMENT_LIMIT");
            }
        }
    }

    private static final class OtherDocument extends SAXException {
    }

    private static final class Frame {
        final String name;
        final String path;
        final Map<String, Integer> children = new HashMap<>();
        String heading;

        Frame(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    private static final class Cell {
        final String path;
        final int rows;
        final int columns;
        final int depth;
        final StringBuilder text = new StringBuilder();

        Cell(String path, int rows, int columns, int depth) {
            this.path = path;
            this.rows = rows;
            this.columns = columns;
            this.depth = depth;
        }
    }

    private static final class Table {
        final String path;
        final List<List<Cell>> rows = new ArrayList<>();
        Cell cell;

        Table(String path) {
            this.path = path;
        }
    }

    private record Anchor(
            int start,
            int end,
            int row,
            int column,
            Cell cell
    ) {}

    private record Rendered(
            String text,
            List<Anchor> anchors,
            int rows,
            int columns
    ) {}

    private static final class Handler extends DefaultHandler {

        private final String entry;
        private final String expectedCorp;
        private final String expectedType;
        private final long deadline;

        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private final ArrayDeque<Table> tables = new ArrayDeque<>();
        private final List<Block> blocks = new ArrayList<>();

        private String code;
        private String corp;
        private boolean sawBody;
        private int bodyDepth;
        private int nodes;
        private int textTotal;

        private long sourceCharacters;
        private long locatorPathCharacters;
        private long locatorCells;

        private StringBuilder paragraph;
        private StringBuilder title;
        private String paragraphPath;
        private String titlePath;
        private int paragraphDepth;
        private int titleDepth;

        private final StringBuilder loose = new StringBuilder();
        private String loosePath;

        Handler(
                String entry,
                String corp,
                String type,
                long deadline
        ) {
            this.entry = entry;
            this.expectedCorp = corp;
            this.expectedType = type;
            this.deadline = deadline;
        }

        @Override
        public void startElement(
                String uri,
                String local,
                String name,
                Attributes attributes
        ) throws SAXException {
            if (++nodes > 1_000_000
                    || frames.size() >= 128
                    || System.nanoTime() > deadline) {
                throw failure("DART_DOCUMENT_LIMIT");
            }

            if (frames.isEmpty() && !name.equals("DOCUMENT")) {
                throw failure("DART_INVALID_XML");
            }

            if (bodyDepth != 0
                    && tables.isEmpty()
                    && Set.of(
                    "P", "TITLE", "COVER-TITLE", "TABLE"
            ).contains(name)) {
                flushLoose();
            }

            Frame parent = frames.peek();

            int index = parent == null
                    ? 1
                    : parent.children.merge(name, 1, Integer::sum);

            frames.push(new Frame(
                    name,
                    (parent == null ? "" : parent.path)
                            + "/"
                            + name
                            + "["
                            + index
                            + "]"
            ));

            if (frames.peek().path.length() > 1024) {
                throw failure("DART_DOCUMENT_LIMIT");
            }

            if (name.equals("DOCUMENT-NAME")) {
                code = attributes.getValue("ACODE");
            }

            if (name.equals("COMPANY-NAME")) {
                corp = attributes.getValue("AREGCIK");
            }

            if (name.equals("BODY")) {
                if (!matches()) {
                    throw new OtherDocument();
                }

                if (sawBody) {
                    throw failure("DART_INVALID_XML");
                }

                sawBody = true;
                bodyDepth = frames.size();
            }

            if (bodyDepth == 0) {
                return;
            }

            if (name.equals("TABLE")) {
                tables.push(new Table(frames.peek().path));
                return;
            }

            if (!tables.isEmpty()) {
                var table = tables.peek();

                if (name.equals("TR")) {
                    if (table.rows.size() >= 5000) {
                        throw failure("DART_DOCUMENT_LIMIT");
                    }

                    table.rows.add(new ArrayList<>());
                }

                if (Set.of("TD", "TH", "TE", "TU").contains(name)
                        && table.cell == null) {
                    if (table.rows.isEmpty()) {
                        throw failure("DART_INVALID_TABLE");
                    }

                    table.cell = new Cell(
                            frames.peek().path,
                            span(
                                    attributes.getValue("ROWSPAN"),
                                    5000
                            ),
                            span(
                                    attributes.getValue("COLSPAN"),
                                    256
                            ),
                            frames.size()
                    );

                    pathBudget(table.cell.path);
                }

                if (Set.of("BR", "PGBRK").contains(name)
                        && table.cell != null) {
                    newline(table.cell.text);
                }

                return;
            }

            if (name.equals("TITLE")
                    || name.equals("COVER-TITLE")) {
                title = new StringBuilder();
                titlePath = frames.peek().path;
                titleDepth = frames.size();

            } else if (name.equals("P")
                    && paragraph == null
                    && title == null) {
                paragraph = new StringBuilder();
                paragraphPath = frames.peek().path;
                paragraphDepth = frames.size();

            } else if (name.equals("BR")
                    || name.equals("PGBRK")) {
                if (paragraph != null) {
                    newline(paragraph);
                } else if (title != null) {
                    newline(title);
                }
            }
        }

        @Override
        public void characters(char[] value, int start, int length) {
            if (bodyDepth == 0) {
                return;
            }

            if (!tables.isEmpty()) {
                var cell = tables.peek().cell;

                if (cell != null) {
                    append(cell.text, value, start, length);

                } else if (!new String(
                        value,
                        start,
                        length
                ).isBlank()) {
                    throw failure("DART_INVALID_TABLE");
                }

            } else if (title != null) {
                append(title, value, start, length);

            } else if (paragraph != null) {
                append(paragraph, value, start, length);

            } else {
                if (loose.isEmpty()) {
                    loosePath = frames.peek().path;
                }

                append(loose, value, start, length);
            }
        }

        @Override
        public void endElement(
                String uri,
                String local,
                String name
        ) {
            if (bodyDepth != 0) {
                if (name.equals("TABLE")) {
                    var rendered = render(tables.pop());

                    if (tables.isEmpty()) {
                        emit(
                                "TABLE",
                                frames.peek().path,
                                rendered.text(),
                                rendered
                        );

                    } else if (tables.peek().cell != null) {
                        var out = tables.peek().cell.text;

                        out.append('\n')
                                .append(
                                        rendered.text()
                                                .replace("\t", " | ")
                                )
                                .append('\n');

                        if (out.length() > MAX_TEXT) {
                            throw failure("DART_DOCUMENT_LIMIT");
                        }

                    } else {
                        throw failure("DART_INVALID_TABLE");
                    }

                } else if (!tables.isEmpty()) {
                    var table = tables.peek();

                    if (table.cell != null
                            && table.cell.depth == frames.size()) {
                        table.rows.getLast().add(table.cell);
                        table.cell = null;

                    } else if (name.equals("P")
                            && table.cell != null) {
                        newline(table.cell.text);
                    }

                } else if (title != null
                        && titleDepth == frames.size()) {
                    String text = title.toString().strip();

                    if (frames.size() > 1) {
                        var iterator = frames.iterator();
                        iterator.next();
                        iterator.next().heading = heading(text);
                    }

                    emit("TITLE", titlePath, text, null);
                    title = null;

                } else if (paragraph != null
                        && paragraphDepth == frames.size()) {
                    emit(
                            "PARAGRAPH",
                            paragraphPath,
                            paragraph.toString().strip(),
                            null
                    );

                    paragraph = null;
                }

                if (frames.size() == bodyDepth
                        && name.equals("BODY")) {
                    flushLoose();
                    bodyDepth = 0;

                } else if (name.startsWith("SECTION-")) {
                    flushLoose();
                }
            }

            frames.pop();
        }

        private boolean matches() {
            if (!expectedCorp.equals(corp)) {
                return false;
            }

            return switch (expectedType) {
                case "ANNUAL" -> "11011".equals(code);
                case "HALF_YEAR" -> "11012".equals(code);
                case "QUARTERLY" ->
                        "11013".equals(code)
                                || "11014".equals(code);
                default -> false;
            };
        }

        private int span(String value, int maximum) {
            if (value == null) {
                return 1;
            }

            try {
                int n = Integer.parseInt(value);

                if (n < 1 || n > maximum) {
                    throw failure("DART_INVALID_TABLE");
                }

                return n;

            } catch (NumberFormatException ex) {
                throw failure("DART_INVALID_TABLE");
            }
        }

        private void flushLoose() {
            if (!loose.isEmpty()) {
                emit(
                        "TEXT",
                        loosePath,
                        loose.toString().strip(),
                        null
                );

                loose.setLength(0);
            }
        }

        private void emit(
                String kind,
                String path,
                String text,
                Rendered table
        ) {
            if (text.isBlank()) {
                return;
            }

            if ((textTotal += text.length()) > MAX_TEXT) {
                throw failure("DART_DOCUMENT_LIMIT");
            }

            pathBudget(path);

            String heading = frames.stream()
                    .map(f -> f.heading)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            int anchorStart = 0;

            for (int start = 0; start < text.length();) {
                if (System.nanoTime() > deadline) {
                    throw failure("DART_DOCUMENT_LIMIT");
                }

                int end = Math.min(
                        text.length(),
                        start + MAX_BLOCK
                );

                if (table != null) {
                    while (anchorStart < table.anchors().size()
                            && table.anchors()
                            .get(anchorStart)
                            .end() <= start) {
                        anchorStart++;
                    }

                    int next = anchorStart + 256;

                    if (next < table.anchors().size()
                            && table.anchors()
                            .get(next)
                            .start() < end) {
                        end = table.anchors().get(next).start();
                    }
                }

                if (end < text.length()
                        && Character.isHighSurrogate(
                        text.charAt(end - 1)
                )) {
                    end--;
                }

                if (blocks.size() >= 20000) {
                    throw failure("DART_DOCUMENT_LIMIT");
                }

                Map<String, Object> locator =
                        new LinkedHashMap<>();

                locator.put("format", "DART_MARKUP");
                locator.put("extractorVersion", VERSION);
                locator.put("entry", entry);
                locator.put("path", path);
                locator.put("kind", kind);
                locator.put("offsetUnit", "UTF16");
                locator.put("textStart", start);
                locator.put("textEnd", end);

                if (table != null) {
                    int anchorEnd = anchorStart;

                    while (anchorEnd < table.anchors().size()
                            && table.anchors()
                            .get(anchorEnd)
                            .start() < end) {
                        anchorEnd++;
                    }

                    if ((locatorCells += anchorEnd - anchorStart)
                            > 200_000) {
                        throw failure("DART_DOCUMENT_LIMIT");
                    }

                    locator.put("rowCount", table.rows());
                    locator.put("columnCount", table.columns());

                    locator.put(
                            "cells",
                            table.anchors()
                                    .subList(
                                            anchorStart,
                                            anchorEnd
                                    )
                                    .stream()
                                    .map(a -> Map.of(
                                            "row", a.row(),
                                            "column", a.column(),
                                            "rowSpan", a.cell().rows,
                                            "columnSpan",
                                            a.cell().columns,
                                            "path", a.cell().path,
                                            "textStart", a.start(),
                                            "textEnd", a.end()
                                    ))
                                    .toList()
                    );
                }

                blocks.add(new Block(
                        "b%06d".formatted(blocks.size() + 1),
                        heading,
                        text.substring(start, end),
                        locator
                ));

                start = end;
            }
        }

        private String heading(String text) {
            return text.codePointCount(0, text.length()) <= 500
                    ? text
                    : text.substring(
                    0,
                    text.offsetByCodePoints(0, 500)
            );
        }

        private Rendered render(Table table) {
            int rows = table.rows.size();
            int width = 0;

            if ((long) rows * 256 > 1_280_000) {
                throw failure("DART_DOCUMENT_LIMIT");
            }

            Cell[][] grid = new Cell[rows][256];
            int occupied = 0;

            for (int r = 0; r < rows; r++) {
                int column = 0;

                for (var cell : table.rows.get(r)) {
                    while (column < 256
                            && grid[r][column] != null) {
                        column++;
                    }

                    if (r + cell.rows > rows
                            || column + cell.columns > 256) {
                        throw failure("DART_INVALID_TABLE");
                    }

                    for (int y = r; y < r + cell.rows; y++) {
                        for (
                                int x = column;
                                x < column + cell.columns;
                                x++
                        ) {
                            if (grid[y][x] != null
                                    || ++occupied > 100_000) {
                                throw failure("DART_INVALID_TABLE");
                            }

                            grid[y][x] = cell;
                        }
                    }

                    column += cell.columns;
                    width = Math.max(width, column);
                }
            }

            StringBuilder text = new StringBuilder();
            var anchors = new ArrayList<Anchor>();

            for (int r = 0; r < rows; r++) {
                if (r != 0) {
                    text.append('\n');
                }

                for (int c = 0; c < width; c++) {
                    if (c != 0) {
                        text.append('\t');
                    }

                    var cell = grid[r][c];
                    int start = text.length();

                    if (cell != null) {
                        text.append(
                                cell.text.toString()
                                        .strip()
                                        .replace("\n", " ↵ ")
                        );

                        if (text.length() > start) {
                            anchors.add(new Anchor(
                                    start,
                                    text.length(),
                                    r + 1,
                                    c + 1,
                                    cell
                            ));
                        }
                    }

                    if (text.length() > MAX_TEXT) {
                        throw failure("DART_DOCUMENT_LIMIT");
                    }
                }
            }

            return new Rendered(
                    text.toString(),
                    anchors,
                    rows,
                    width
            );
        }

        private void append(
                StringBuilder out,
                char[] value,
                int start,
                int length
        ) {
            int before = out.length();

            for (int i = start; i < start + length; i++) {
                char ch = value[i];

                if (Character.isWhitespace(ch)
                        || ch == '\u00a0') {
                    if (!out.isEmpty()
                            && out.charAt(out.length() - 1) != ' '
                            && out.charAt(out.length() - 1) != '\n') {
                        out.append(' ');
                    }

                } else {
                    out.append(ch);
                }
            }

            if (out.length() > MAX_TEXT) {
                throw failure("DART_DOCUMENT_LIMIT");
            }

            if ((sourceCharacters += out.length() - before)
                    > MAX_TEXT) {
                throw failure("DART_DOCUMENT_LIMIT");
            }
        }

        private void newline(StringBuilder out) {
            while (!out.isEmpty()
                    && out.charAt(out.length() - 1) == ' ') {
                out.setLength(out.length() - 1);
            }

            if (!out.isEmpty()
                    && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
        }

        private void pathBudget(String path) {
            if ((locatorPathCharacters += path.length())
                    > 16_000_000) {
                throw failure("DART_DOCUMENT_LIMIT");
            }
        }
    }

    private static DartFailure failure(String code) {
        return new DartFailure(code, false);
    }
}