package com.dadot.server.infrastructure.dart;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DART의 비표준 본문 표기만 정규화한다.
 * 보관하는 원본 바이트는 변경하지 않는다.
 */
final class DartMarkupReader extends Reader {

    private static final Set<String> TAGS = Set.of(
            "DOCUMENT", "DOCUMENT-NAME", "FORMULA-VERSION",
            "COMPANY-NAME", "SUMMARY", "EXTRACTION", "BODY",
            "COVER", "COVER-TITLE", "TABLE-GROUP", "TABLE",
            "COLGROUP", "COL", "TBODY", "THEAD", "TFOOT",
            "TR", "TD", "TH", "TE", "TU", "P", "SPAN", "TITLE",
            "SECTION-1", "SECTION-2", "SECTION-3", "SECTION-4",
            "SECTION-5", "SECTION-6", "LIBRARY", "PGBRK",
            "A", "IMAGE", "IMG", "IMG-CAPTION", "BR",
            "B", "I", "U", "SUP", "SUB", "DIV", "FONT",
            "STRONG", "EM", "HR"
    );

    private static final Set<String> VOID = Set.of(
            "BR", "IMG", "COL", "HR", "PGBRK"
    );

    private static final Set<String> ATTRIBUTES = Set.of(
            "ACODE", "AREGCIK", "ROWSPAN", "COLSPAN"
    );

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\G\\s*([A-Za-z_][\\w:.-]*)\\s*=\\s*(['\"])"
    );

    private static final Pattern NEXT_ATTRIBUTE = Pattern.compile(
            "\\s+[A-Za-z_][\\w:.-]*\\s*="
    );

    private final PushbackReader input;

    private String pending = "";
    private int position;
    private boolean cdata;
    private int cdataBrackets;
    private boolean first = true;

    DartMarkupReader(Reader input) {
        this.input = new PushbackReader(input, 512);
    }

    @Override
    public int read(char[] target, int offset, int length)
            throws IOException {
        java.util.Objects.checkFromIndexSize(
                offset,
                length,
                target.length
        );

        if (length == 0) {
            return 0;
        }

        if (pending == null) {
            return -1;
        }

        int count = 0;

        while (count < length) {
            if (position == pending.length()) {
                pending = token();
                position = 0;

                if (pending == null) {
                    return count == 0 ? -1 : count;
                }

                if (pending.isEmpty()) {
                    continue;
                }
            }

            int n = Math.min(
                    length - count,
                    pending.length() - position
            );

            pending.getChars(
                    position,
                    position + n,
                    target,
                    offset + count
            );

            position += n;
            count += n;
        }

        return count;
    }

    private String token() throws IOException {
        int value = input.read();

        if (value == -1) {
            return null;
        }

        char ch = (char) value;

        if (first) {
            first = false;

            if (ch == '\ufeff') {
                return "";
            }
        }

        if (cdata) {
            if (ch == ']') {
                cdataBrackets++;
            } else {
                if (ch == '>' && cdataBrackets >= 2) {
                    cdata = false;
                }

                cdataBrackets = 0;
            }

            return Character.toString(ch);
        }

        if (ch == '&') {
            return entity();
        }

        if (ch != '<') {
            return Character.toString(ch);
        }

        int next = input.read();

        if (next == -1) {
            return "&lt;";
        }

        if (next == '!') {
            return declaration();
        }

        if (next == '?') {
            // 인코딩은 바이트 디코더에서 처리한다.
            readUntil("?>", 1024);
            return "";
        }

        boolean closing = next == '/';

        StringBuilder name = new StringBuilder();

        int current = closing ? input.read() : next;

        while (current != -1
                && ((current >= 'A' && current <= 'Z')
                || (current >= 'a' && current <= 'z')
                || (current >= '0' && current <= '9')
                || current == '-')) {
            if (name.length() == 100) {
                throw invalid();
            }

            name.append((char) current);
            current = input.read();
        }

        if (current != -1) {
            input.unread(current);
        }

        String tag = name.toString();

        if (!TAGS.contains(tag)) {
            return "&lt;" + (closing ? "/" : "") + tag;
        }

        String tail = tagTail();

        if (closing) {
            if (!tail.matches("\\s*>")) {
                throw invalid();
            }

            return VOID.contains(tag)
                    ? ""
                    : "</" + tag + ">";
        }

        List<String> attributes = attributes(tail, tag);

        return "<"
                + tag
                + String.join("", attributes)
                + (VOID.contains(tag)
                || tail.matches("(?s).*/\\s*>")
                ? "/>"
                : ">");
    }

    private String declaration() throws IOException {
        int first = input.read();
        int second = input.read();

        if (first == '-' && second == '-') {
            readUntil("-->", 1_000_000);
            return "";
        }

        if (first == '[' && second == 'C') {
            StringBuilder rest = new StringBuilder();

            for (int i = 0; i < 5; i++) {
                rest.append((char) input.read());
            }

            if (!rest.toString().equals("DATA[")) {
                throw invalid();
            }

            cdata = true;
            cdataBrackets = 0;

            return "<![CDATA[";
        }

        throw new IOException(
                "DART DTD/entity declarations are forbidden"
        );
    }

    private String entity() throws IOException {
        StringBuilder value = new StringBuilder();

        while (value.length() < 32) {
            int ch = input.read();

            if (ch == ';') {
                String text = value.toString();

                if (Set.of(
                        "amp", "lt", "gt", "apos", "quot"
                ).contains(text)
                        || text.matches("#[0-9]+|#x[0-9A-Fa-f]+")) {
                    return "&" + text + ";";
                }

                if (text.equals("nbsp")) {
                    return "\u00a0";
                }

                if (text.equals("reg")) {
                    return "\u00ae";
                }

                return "&amp;" + text + ";";
            }

            if (ch == -1) {
                break;
            }

            if (!(Character.isLetterOrDigit(ch) || ch == '#')) {
                input.unread(ch);
                break;
            }

            value.append((char) ch);
        }

        return "&amp;" + value;
    }

    private String tagTail() throws IOException {
        StringBuilder value = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < 16384; i++) {
            int n = input.read();

            if (n == -1) {
                throw invalid();
            }

            char ch = (char) n;
            value.append(ch);

            if (quote == 0) {
                if (ch == '>') {
                    return value.toString();
                }

                if ((ch == '\'' || ch == '"')
                        && value.substring(0, value.length() - 1)
                        .stripTrailing()
                        .endsWith("=")) {
                    quote = ch;
                }

            } else if (ch == quote && closingQuoteAhead()) {
                quote = 0;
            }
        }

        throw invalid();
    }

    private boolean closingQuoteAhead() throws IOException {
        char[] ahead = new char[256];
        int n = 0;

        while (n < ahead.length) {
            int ch = input.read();

            if (ch == -1) {
                break;
            }

            ahead[n++] = (char) ch;

            if (ch == '>' || ch == '=' || ch == '<') {
                break;
            }
        }

        if (n != 0) {
            input.unread(ahead, 0, n);
        }

        String text = new String(ahead, 0, n);

        return text.matches("(?s)\\s*/?>.*")
                || NEXT_ATTRIBUTE.matcher(text).lookingAt();
    }

    private List<String> attributes(String tail, String tag)
            throws IOException {
        var result = new ArrayList<String>();
        var seen = new java.util.HashSet<String>();

        int offset = 0;

        while (!tail.substring(offset).matches("\\s*/?>")) {
            var matcher = ATTRIBUTE.matcher(tail);
            matcher.region(offset, tail.length());

            if (!matcher.lookingAt()) {
                throw invalid();
            }

            String name = matcher.group(1);
            char quote = matcher.group(2).charAt(0);
            int end = matcher.end();

            while (end < tail.length()) {
                if (tail.charAt(end) == quote) {
                    String rest = tail.substring(end + 1);

                    if (rest.matches("(?s)\\s*/?>")
                            || NEXT_ATTRIBUTE.matcher(rest)
                            .lookingAt()) {
                        break;
                    }
                }

                end++;
            }

            if (end == tail.length() || !seen.add(name)) {
                throw invalid();
            }

            String value = tail.substring(matcher.end(), end);

            if (ATTRIBUTES.contains(name)
                    && (!name.equals("ACODE")
                    || tag.equals("DOCUMENT-NAME"))) {
                if (!value.matches("[0-9]{1,20}")) {
                    throw invalid();
                }

                result.add(" " + name + "=\"" + value + "\"");
            }

            offset = end + 1;
        }

        return result;
    }

    private void readUntil(String suffix, int limit)
            throws IOException {
        int matched = 0;

        for (int i = 0; i < limit; i++) {
            int ch = input.read();

            if (ch == -1) {
                throw invalid();
            }

            matched = ch == suffix.charAt(matched)
                    ? matched + 1
                    : (ch == suffix.charAt(0) ? 1 : 0);

            if (matched == suffix.length()) {
                return;
            }
        }

        throw invalid();
    }

    private IOException invalid() {
        return new IOException("Unsupported DART markup");
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}