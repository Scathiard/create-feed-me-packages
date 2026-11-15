package dev.scathiard.feedmepackages.domain;

/** Existing address length is measured in UTF-16 units; its UTF-8 wire budget must cover every valid value. */
public final class ReturnAddress {
    public static final int MAX_UNITS = 128;
    public static final int MAX_BYTES = MAX_UNITS * 3;
    private ReturnAddress() {}
    /** Clipboard and keyboard use the same single-line, whole-code-point length limit. */
    public static String appendInput(String current, String input) {
        var result = new StringBuilder(current);
        for (int i = 0; i < input.length();) {
            int code = input.codePointAt(i);
            int units = Character.charCount(code);
            i += units;
            if (Character.isISOControl(code) || code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE)
                continue;
            if (result.length() == MAX_UNITS) break;
            if (result.length() + units > MAX_UNITS) continue;
            result.appendCodePoint(code);
        }
        return result.toString();
    }

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
}
