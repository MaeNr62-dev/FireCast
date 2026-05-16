package com.firecast.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Serveur WebSocket Cast V2 sur le port 8009.
 * C'est le vrai protocole utilisé par Google Cast pour communiquer
 * avec le récepteur (notre Fire TV).
 */
public class CastV2Server extends WebSocketServer {

    private static final String TAG = "FireCast.V2";

    // Namespaces Cast V2
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT  = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER   = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA      = "urn:x-cast:com.google.cast.media";

    private final Context context;
    private final Map<WebSocket, String> sessions = new HashMap<>();
    private int mediaSessionId = 1;

    public CastV2Server(Context context) {
        super(new InetSocketAddress(8009));
        this.context = context;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.i(TAG, "✅ Téléphone connecté : " + conn.getRemoteSocketAddress());
        sessions.put(conn, "sender-0");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.i(TAG, "Déconnexion : " + reason);
        sessions.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        try {
            // Lire le préfixe 4 octets
            byte[] data = message.array();
            int msgLen = ByteBuffer.wrap(data, 0, 4).getInt();
            byte[] protoData = new byte[msgLen];
            System.arraycopy(data, 4, protoData, 0, msgLen);

            CastMessageParser.CastMessage msg = CastMessageParser.decode(protoData);
            if (msg.namespace == null) return;

            Log.d(TAG, "Message reçu [" + msg.namespace + "] : " + msg.payload);
            handleMessage(conn, msg);

        } catch (Exception e) {
            Log.e(TAG, "Erreur parsing message", e);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // Cast V2 utilise des messages binaires uniquement
    }

    private void handleMessage(WebSocket conn, CastMessageParser.CastMessage msg) throws Exception {
        switch (msg.namespace) {

            case NS_CONNECTION:
                // Connexion établie — envoyer statut récepteur
                sendReceiverStatus(conn, msg.sourceId);
                break;

            case NS_HEARTBEAT:
                // Répondre au ping
                JSONObject ping = new JSONObject(msg.payload);
                if ("PING".equals(ping.optString("type"))) {
                    sendMessage(conn, "receiver-0", msg.sourceId,
                            NS_HEARTBEAT, "{\"type\":\"PONG\"}");
                }
                break;

            case NS_RECEIVER:
                JSONObject recv = new JSONObject(msg.payload);
                String type = recv.optString("type");

                if ("GET_STATUS".equals(type)) {
                    sendReceiverStatus(conn, msg.sourceId);
                } else if ("LAUNCH".equals(type)) {
                    // Lancer une app Cast (YouTube, etc.)
                    String appId = recv.optString("appId");
                    Log.i(TAG, "LAUNCH app : " + appId);
                    sendLaunchStatus(conn, msg.sourceId, appId, recv.optInt("requestId"));
                }
                break;

            case NS_MEDIA:
                JSONObject media = new JSONObject(msg.payload);
                if ("LOAD".equals(media.optString("type"))) {
                    // Charger et lire le média
                    JSONObject mediaInfo = media.optJSONObject("media");
                    if (mediaInfo != null) {
                        String url = mediaInfo.optString("contentId");
                        Log.i(TAG, "LOAD media : " + url);
                        launchPlayer(url);
                        sendMediaStatus(conn, msg.sourceId, media.optInt("requestId"), url);
                    }
                }
                break;
        }
    }

    private void sendReceiverStatus(WebSocket conn, String destinationId) throws Exception {
        String status = "{\"type\":\"RECEIVER_STATUS\",\"status\":{"
                + "\"applications\":[{"
                + "\"appId\":\"CC1AD845\","
                + "\"displayName\":\"FireCast TV\","
                + "\"namespaces\":[{\"name\":\"" + NS_MEDIA + "\"}],"
                + "\"sessionId\":\"session-1\","
                + "\"statusText\":\"Prêt\","
                + "\"transportId\":\"transport-1\""
                + "}],"
                + "\"isActiveInput\":true,"
                + "\"isStandBy\":false,"
                + "\"volume\":{\"level\":1.0,\"muted\":false}"
                + "}}";
        sendMessage(conn, "receiver-0", destinationId, NS_RECEIVER, status);
    }

    private void sendLaunchStatus(WebSocket conn, String destinationId,
                                   String appId, int requestId) throws Exception {
        String status = "{\"type\":\"RECEIVER_STATUS\",\"requestId\":" + requestId + ",\"status\":{"
                + "\"applications\":[{"
                + "\"appId\":\"" + appId + "\","
                + "\"displayName\":\"FireCast TV\","
                + "\"namespaces\":[{\"name\":\"" + NS_MEDIA + "\"}],"
                + "\"sessionId\":\"session-1\","
                + "\"statusText\":\"Prêt\","
                + "\"transportId\":\"transport-1\""
                + "}],"
                + "\"volume\":{\"level\":1.0,\"muted\":false}"
                + "}}";
        sendMessage(conn, "receiver-0", destinationId, NS_RECEIVER, status);
    }

    private void sendMediaStatus(WebSocket conn, String destinationId,
                                  int requestId, String url) throws Exception {
        String status = "{\"type\":\"MEDIA_STATUS\",\"requestId\":" + requestId + ","
                + "\"status\":[{"
                + "\"mediaSessionId\":" + mediaSessionId++ + ","
                + "\"playbackRate\":1,"
                + "\"playerState\":\"PLAYING\","
                + "\"currentTime\":0,"
                + "\"supportedMediaCommands\":15,"
                + "\"volume\":{\"level\":1.0,\"muted\":false},"
                + "\"media\":{\"contentId\":\"" + url + "\","
                + "\"streamType\":\"BUFFERED\","
                + "\"contentType\":\"video/mp4\"}"
                + "}]}";
        sendMessage(conn, "receiver-0", destinationId, NS_MEDIA, status);
    }

    private void sendMessage(WebSocket conn, String sourceId, String destinationId,
                              String namespace, String payload) throws Exception {
        byte[] encoded = CastMessageParser.encode(sourceId, destinationId, namespace, payload);
        conn.send(ByteBuffer.wrap(encoded));
    }

    private void launchPlayer(String url) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra("url", url);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "Erreur WebSocket", ex);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Serveur Cast V2 démarré sur le port 8009");
    }
}
