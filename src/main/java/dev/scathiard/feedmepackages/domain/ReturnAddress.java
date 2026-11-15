package dev.scathiard.feedmepackages.domain;

/** Existing address length is measured in UTF-16 units; its UTF-8 wire budget must cover every valid value. */
public final class ReturnAddress {
    public static final int MAX_UNITS = 128;
    public static final int MAX_BYTES = MAX_UNITS * 3;
    private ReturnAddress() {}
    public static boolean valid(String text) {
        if (text == null || text.length() > MAX_UNITS) return false;
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    /**
     * Appends pasted text under exactly the rules the keyboard path uses: one code point at a time, control characters
     * refused, and every accepted code point must leave the whole address valid. A code point that would break the
     * contract is skipped the same way a refused keystroke is, so a paste can never produce a value that typing could
     * not produce (R-016).
     */
    public static String append(String current, String pasted) {
        StringBuilder result = new StringBuilder(current == null ? "" : current);
        if (pasted == null || pasted.isEmpty()) return result.toString();
        for (int index = 0; index < pasted.length(); ) {
            int point = pasted.codePointAt(index);
            index += Character.charCount(point);
            if (point < ' ' || !Character.isValidCodePoint(point)) continue;
            String candidate = result + new String(Character.toChars(point));
            if (!valid(candidate)) continue;
            result.appendCodePoint(point);
        }
        return result.toString();
    }
}
