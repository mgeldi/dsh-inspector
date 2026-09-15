package inspector.store;

public final class Paths {

    private Paths() {
    }

    /** Absolute under the project root becomes project-relative; anything else keeps its last
     *  segment only, so a home directory or another user's path can never reach the database. */
    public static String hint(final String absolute, final String projectRoot) {
        if (absolute == null || absolute.isBlank()) {
            return null;
        }
        if (projectRoot != null && !projectRoot.isBlank()) {
            final String root = projectRoot.endsWith("/") ? projectRoot : projectRoot + "/";
            if (absolute.startsWith(root)) {
                return absolute.substring(root.length());
            }
        }
        final int slash = absolute.lastIndexOf('/');
        return slash < 0 ? absolute : absolute.substring(slash + 1);
    }
}
