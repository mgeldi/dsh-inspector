package inspector.ingest;

public enum Convention {
    V0, V3;

    public static final String FILE_V0 = "session.jsonl.zstd";
    public static final String FILE_V3 = "session.v3.jsonl.zstd";

    public static boolean isSessionFile(final String fileName) {
        return FILE_V0.equals(fileName) || FILE_V3.equals(fileName);
    }

    /** Filename is the convention. §3.4: the header `version` field is the schema version and
     *  is 0 in v0 and 3 in v3 — a free cross-check, asserted in Task 13, not the detector. */
    public static Convention fromFileName(final String fileName) {
        if (FILE_V3.equals(fileName)) {
            return V3;
        }
        if (FILE_V0.equals(fileName)) {
            return V0;
        }
        throw new IllegalArgumentException("not a session file: " + fileName);
    }
}
