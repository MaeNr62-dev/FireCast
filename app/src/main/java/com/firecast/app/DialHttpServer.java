package com.firecast.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import fi.iki.elonen.NanoHTTPD;
import java.util.Map;

/**
 * Serveur HTTP DIAL sur le port 8008.
 * Le protocole DIAL (Discovery and Launch) est la couche de découverte
 * utilisée par Chromecast. Ce serveur répond aux requêtes des applis
 * Cast pour lancer des médias sur le Fire TV.
 */
public class DialHttpServer extends NanoHTTPD {

    private static final String TAG = "FireCast.DIAL";
    private final Context context;

    public DialHttpServer(Context context) {
        super(8008);
        this.context = context;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Log.d(TAG, method + " " + uri);

        // Réponse à la découverte initiale (GET /)
        if (uri.equals("/") || uri.equals("/ssdp/device-desc.xml")) {
            return deviceDescription();
        }

        // Requête pour lancer une app Cast (YouTube, etc.)
        if (uri.startsWith("/apps/") && method == Method.POST) {
            return launchApp(session, uri);
        }

        // Statut d'une app
        if (uri.startsWith("/apps/") && method == Method.GET) {
            return appStatus(uri);
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND,
                "text/plain", "Not found");
    }

    private Response deviceDescription() {
        String xml = "<?xml version=\"1.0\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device>"
                + "<deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>"
                + "<friendlyName>FireCast TV</friendlyName>"
                + "<manufacturer>FireCast</manufacturer>"
                + "<modelName>FireCast Receiver</modelName>"
                + "<UDN>uuid:firecast-0000-0000-0000-000000000001</UDN>"
                + "<serviceList/>"
                + "</device>"
                + "</root>";

        Response r = newFixedLengthResponse(Response.Status.OK, "application/xml", xml);
        r.addHeader("Application-URL", "http://localhost:8008/apps/");
        return r;
    }

    private Response launchApp(IHTTPSession session, String uri) {
        String appName = uri.replace("/apps/", "").split("\\?")[0];
        Log.i(TAG, "Lancement de l'app : " + appName);

        // Extraire l'URL du média depuis les paramètres
        Map<String, String> params = session.getParms();
        String mediaUrl = params.get("content");

        if (mediaUrl != null && !mediaUrl.isEmpty()) {
            // Lancer le lecteur vidéo
            Intent intent = new Intent(context, PlayerActivity.class);
            intent.putExtra("url", mediaUrl);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }

        Response r = newFixedLengthResponse(Response.Status.CREATED, "text/plain", "");
        r.addHeader("Location", "http://localhost:8008" + uri + "/run");
        return r;
    }

    private Response appStatus(String uri) {
        String appName = uri.replace("/apps/", "");
        String xml = "<service xmlns=\"urn:dial-multiscreen-org:schemas:dial\">"
                + "<name>" + appName + "</name>"
                + "<options allowStop=\"true\"/>"
                + "<state>stopped</state>"
                + "</service>";
        return newFixedLengthResponse(Response.Status.OK, "application/xml", xml);
    }
}
