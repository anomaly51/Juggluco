package tk.glucodata;

/** Pure validation shared by connection setup and its settings form. */
final class PersonalConnectionPolicy {
    static boolean publicationPending(String publicUrl, boolean serverReady) {
        return !publicUrl.isEmpty() && !serverReady;
    }

    static boolean validLabel(String value) {
        return value!=null&&(value.isEmpty()||(value.length()<=57&&validHostLabel(value)&&!value.startsWith("user-")));
    }
    private static boolean validHostLabel(String value) {
        return value.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
                && !value.matches("(?:www|api|admin|auth|login|internal|argocd|harbor|vault|keycloak|grafana|logs|s3|mail|smtp|imap|ns1|ns2|status|assets|cdn|juggluco|localhost)")
                && !value.matches("(?:.*-)?general[0-9]+");
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
                    && host.endsWith(".api-api-api.com")
                    && validHostLabel(host.substring(0,host.length()-".api-api-api.com".length()))
                    && "https".equals(api.getScheme())
                    && "juggluco-general1.api-api-api.com".equals(api.getHost())
                    && url.getPort()==-1 && url.getUserInfo()==null
                    && "/".equals(url.getPath()) && url.getQuery()==null && url.getFragment()==null;
        } catch(java.net.URISyntaxException error) { return false; }
    }

}
