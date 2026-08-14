package com.minis.ax3000t;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MtdPartition {
    private static final Pattern LINE = Pattern.compile("mtd(\\d+):\\s*([0-9a-fA-F]+)\\s+[0-9a-fA-F]+\\s+\\\"([^\\\"]+)\\\"");

    public final int index;
    public final long size;
    public final String label;

    public MtdPartition(int index, long size, String label) {
        this.index = index;
        this.size = size;
        this.label = label == null ? "" : label;
    }

    public static MtdPartition parseLine(String line) {
        Matcher matcher = LINE.matcher(line == null ? "" : line);
        if (!matcher.find()) return null;
        return new MtdPartition(
                Integer.parseInt(matcher.group(1)),
                Long.parseLong(matcher.group(2), 16),
                matcher.group(3));
    }

    public boolean isAggregate() {
        String normalized = label.toLowerCase(java.util.Locale.US);
        return normalized.equals("all") || normalized.contains("nmbm");
    }

    public String safeLabel() {
        String value = label.replaceAll("[^A-Za-z0-9._-]", "_");
        return value.isEmpty() ? "unknown" : value;
    }

    @Override
    public String toString() {
        return "mtd" + index + " " + label + " (" + size + " bytes)";
    }
}
