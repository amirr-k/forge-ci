package dev.forgeci.core.glob;

import java.util.regex.Pattern;

/**
 * Matches a {@code forgeci.yml} input glob against a repository-relative path. Deliberately not
 * {@code java.nio.file.PathMatcher} — its {@code **} semantics vary subtly across platforms, and a
 * hand-rolled translation is easy to pin down with unit tests.
 *
 * <p>Supported syntax: {@code **} matches any sequence of characters, including {@code /}; {@code
 * *} matches any sequence of characters except {@code /}; {@code ?} matches exactly one character
 * except {@code /}. Everything else is a literal.
 */
public final class GlobMatcher {

    private GlobMatcher() {}

    public static boolean matches(String glob, String path) {
        return Pattern.matches(toRegex(glob), path);
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i += 2;
                } else {
                    regex.append("[^/]*");
                    i += 1;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i += 1;
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
                i += 1;
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
