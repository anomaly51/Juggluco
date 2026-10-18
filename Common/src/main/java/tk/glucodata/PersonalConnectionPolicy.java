package tk.glucodata;

/** Pure validation shared by connection setup and its settings form. */
final class PersonalConnectionPolicy {
    static boolean validLabel(String value) {
        return value!=null&&(value.isEmpty()||value.matches("[a-z0-9][a-z0-9-]{0,23}"));
    }
    static boolean validViewerPath(String path) {
        return path!=null&&path.matches("/viewer/\\?profile=(?:[a-z0-9][a-z0-9-]{0,23}-)?[A-Za-z0-9_-]{22}");
    }

    static boolean validViewerUrl(String value,String base) {
        if(value==null||base==null) return false;
        try {
            java.net.URI url=new java.net.URI(value);
            java.net.URI api=new java.net.URI(base);
            String host=url.getHost();
            // Production viewer names are in our explicit zone, not arbitrary
            // redirects returned in a server response. Local/custom servers can
            // still return the legacy relative viewer_path.
            return "https".equals(url.getScheme()) && host!=null
                    && host.matches("(?:gluco-(?:[a-z0-9][a-z0-9-]{0,23}-)?[a-z2-7]{26}|g-(?:[a-z0-9][a-z0-9-]{0,23}-)?[a-z2-7]{12})\\.api-api-api\\.com")
                    && "https".equals(api.getScheme())
                    && "juggluco-general1.api-api-api.com".equals(api.getHost())
                    && url.getPort()==-1 && url.getUserInfo()==null
                    && "/".equals(url.getPath()) && url.getQuery()==null && url.getFragment()==null;
        } catch(java.net.URISyntaxException error) { return false; }
    }

}
