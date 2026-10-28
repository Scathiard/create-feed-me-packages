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
}
