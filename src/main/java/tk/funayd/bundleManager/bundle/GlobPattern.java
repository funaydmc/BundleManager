package tk.funayd.bundleManager.bundle;

import java.util.regex.Pattern;

public final class GlobPattern {

    private final String glob;
    private final Pattern regex;

    public GlobPattern(String glob) {
        this.glob = glob;
        this.regex = Pattern.compile("^" + toRegex(glob) + "$");
    }

    public boolean matches(String value) {
        return regex.matcher(value).matches();
    }

    @Override
    public String toString() {
        return glob;
    }

    private String toRegex(String globPattern) {
        StringBuilder regexBuilder = new StringBuilder();

        for (int i = 0; i < globPattern.length(); i++) {
            char current = globPattern.charAt(i);

            if (current == '*') {
                boolean doubleStar = i + 1 < globPattern.length() && globPattern.charAt(i + 1) == '*';
                if (doubleStar) {
                    regexBuilder.append(".*");
                    i++;
                } else {
                    regexBuilder.append("[^/]*");
                }
                continue;
            }

            if (current == '?') {
                regexBuilder.append("[^/]");
                continue;
            }

            if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                regexBuilder.append('\\');
            }

            regexBuilder.append(current);
        }

        return regexBuilder.toString();
    }
}
