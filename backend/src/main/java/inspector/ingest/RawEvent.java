package inspector.ingest;

import tools.jackson.databind.JsonNode;

public record RawEvent(String type, int seq, long time, JsonNode data, JsonNode root) {

    public String text(final String pointer) {
        final JsonNode node = data.at(pointer);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    public Integer integer(final String pointer) {
        final JsonNode node = data.at(pointer);
        return node.isNumber() ? node.asInt() : null;
    }

    /** §3.6: header fields live on the line root, not under data. */
    public String rootText(final String field) {
        final JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText(null);
    }

    public Long rootLong(final String field) {
        final JsonNode node = root.get(field);
        return node != null && node.isNumber() ? node.asLong() : null;
    }
}
