package tk.glucodata;

/** Pure validation shared by connection setup and its settings form. */
final class PersonalConnectionPolicy {
    static boolean validLabel(String value) {
        return value!=null&&(value.isEmpty()||value.matches("[a-z0-9][a-z0-9-]{0,23}"));
    }
    static boolean validViewerPath(String path) {
        return path!=null&&path.matches("/viewer/\\?profile=(?:[a-z0-9][a-z0-9-]{0,23}-)?[A-Za-z0-9_-]{22}");
    }
}
