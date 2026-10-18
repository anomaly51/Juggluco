package tk.glucodata;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public, credential-free checks on THIS phone's network, never the server's DNS cache. */
final class PersonalLinkVerifier {
    interface Progress { void reached(int percent,int message); }
    interface Transport {
        void resolve(String host) throws Exception;
        String get(String url) throws Exception;
    }
    static final class Network implements Transport {
        public void resolve(String host) throws Exception {
            if(InetAddress.getAllByName(host).length==0) throw new java.net.UnknownHostException();
        }
        public String get(String url) throws Exception {
            HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control","no-cache");
            connection.setRequestProperty("Cookie","");
            // Deliberately no Authorization, cookies or owner credential.
            try {
                if(connection.getResponseCode()!=200) throw new IOException("Public link unavailable");
                try(InputStream input=connection.getInputStream(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];
                    int count;
                    while((count=input.read(buffer))!=-1) {
                        if(output.size()+count>2*1024*1024) throw new IOException("Public response too large");
                        output.write(buffer,0,count);
                    }
                    return output.toString(StandardCharsets.UTF_8.name());
                }
            } finally { connection.disconnect(); }
        }
    }

    static long verify(String viewer,String base,String path,Progress progress,Transport transport) throws Exception {
        URI uri=new URI(viewer);
        boolean domain=PersonalConnectionPolicy.validViewerUrl(viewer,base);
        if(!domain && (!PersonalConnectionPolicy.validViewerPath(path)||!viewer.equals(base+path)))
            throw new IOException("Invalid public link");
        String origin=domain?viewer.substring(0,viewer.length()-1):base;
        String handle=path.substring(path.indexOf('=')+1);
        String api=domain?"/v1/viewer":"/v1/shares/"+handle;
        progress.reached(40,R.string.personal_connection_check_dns);
        transport.resolve(uri.getHost());
        progress.reached(55,R.string.personal_connection_check_https);
        JSONObject endpoint=new JSONObject(transport.get(origin+(domain?api+"/ready":api+"/session")));
        if(domain? !endpoint.optBoolean("ready")||!uri.getHost().equals(endpoint.optString("host")):
                !"public".equals(endpoint.optString("access_mode"))) throw new IOException("Wrong public endpoint");
        progress.reached(70,R.string.personal_connection_check_page);
        String html=transport.get(viewer);
        if(!html.contains("id=\"root\"")) throw new IOException("Viewer page not available");
        Set<String> assets=new LinkedHashSet<>();
        Matcher matcher=Pattern.compile("(?:src|href)=[\"'](/viewer/assets/[A-Za-z0-9._-]+\\.(?:js|css))[\"']").matcher(html);
        boolean script=false;
        while(matcher.find()) { assets.add(matcher.group(1)); script|=matcher.group(1).endsWith(".js"); }
        if(!script||assets.size()>12) throw new IOException("Viewer assets not available");
        for(String asset:assets) if(transport.get(origin+asset).trim().isEmpty()) throw new IOException("Empty viewer asset");
        JSONObject manifest=new JSONObject(transport.get(origin+"/viewer/personal.webmanifest"+(domain?"":"?profile="+handle)));
        if(!manifest.optString("start_url").equals(domain?"/":path)) throw new IOException("Wrong viewer manifest");
        progress.reached(85,R.string.personal_connection_check_data);
        JSONObject snapshot=new JSONObject(transport.get(origin+api+"/snapshot"));
        if(!"v1".equals(snapshot.optString("api_version"))||!snapshot.has("current_glucose")
                ||snapshot.optJSONArray("glucose_history")==null||snapshot.optJSONArray("intake_events")==null)
            throw new IOException("Invalid public data response");
        JSONObject current=snapshot.optJSONObject("current_glucose");
        progress.reached(95,R.string.personal_connection_check_final);
        return current==null?0:current.optLong("measured_at_ms",0);
    }
}
