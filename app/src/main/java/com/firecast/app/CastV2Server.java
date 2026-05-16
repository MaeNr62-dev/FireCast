package com.firecast.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Serveur WebSocket Cast V2 avec SSL sur le port 8009.
 */
public class CastV2Server extends WebSocketServer {

    private static final String TAG = "FireCast.V2";
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT  = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER   = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA      = "urn:x-cast:com.google.cast.media";

    private final Context context;
    private final Map<WebSocket, String> sessions = new HashMap<>();
    private int mediaSessionId = 1;

    public CastV2Server(Context context) throws Exception {
        super(new InetSocketAddress(8009));
        this.context = context;
        setReuseAddr(true);
        setupSSL();
    }

    private void setupSSL() throws Exception {
        // Charger le certificat depuis les assets
        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream certStream = context.getAssets().open("firecast.p12");
        ks.load(certStream, "firecast123".toCharArray());
        certStream.close();

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "firecast123".toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        setWebSocketFactory(new DefaultSSLWebSocketServerFactory(sslContext));
        Log.i(TAG, "SSL configuré ✅");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.i(TAG, "✅ Téléphone connecté via SSL : " + conn.getRemoteSocketAddress());
        sessions.put(conn, "sender-0");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.i(TAG, "Déconnexion");
        sessions.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        try {
            byte[] data = message.array();
            if (data.length < 4) return;
            int msgLen = ByteBuffer.wrap(data, 0, 4).getInt();
            if (msgLen <= 0 || msgLen > data.length - 4) return;
            byte[] protoData = new byte[msgLen];
            System.arraycopy(data, 4, protoData, 0, msgLen);

            CastMessageParser.CastMessage msg = CastMessageParser.decode(protoData);
            if (msg.namespace == null) return;

            Log.d(TAG, "[" + msg.namespace + "] " + msg.payload);
            handleMessage(conn, msg);

        } catch (Exception e) {
            Log.e(TAG, "Erreur parsing", e);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {}

    private void handleMessage(WebSocket conn, CastMessageParser.CastMessage msg) throws Exception {
        switch (msg.namespace) {
            case NS_CONNECTION:
                sendReceiverStatus(conn, msg.sourceId);
                break;

            case NS_HEARTBEAT:
                JSONObject ping = new JSONObject(msg.payload);
                if ("PING".equals(ping.optString("type"))) {
                    sendMsg(conn, "receiver-0", msg.sourceId,
                            NS_HEARTBEAT, "{\"type\":\"PONG\"}");
                }
                break;

            case NS_RECEIVER:
                JSONObject recv = new JSONObject(msg.payload);
                String type = recv.optString("type");
                if ("GET_STATUS".equals(type)) {
                    sendReceiverStatus(conn, msg.sourceId);
                } else if ("LAUNCH".equals(type)) {
                    sendLaunchStatus(conn, msg.sourceId,
                            recv.optString("appId"), recv.optInt("requestId"));
                }
                break;

            case NS_MEDIA:
                JSONObject media = new JSONObject(msg.payload);
                if ("LOAD".equals(media.optString("type"))) {
                    JSONObject mediaInfo = media.optJSONObject("media");
                    if (mediaInfo != null) {
                        String url = mediaInfo.optString("contentId");
                        launchPlayer(url);
                        sendMediaStatus(conn, msg.sourceId,
                                media.optInt("requestId"), url);
                    }
                }
                break;
        }
    }

    private void sendReceiverStatus(WebSocket conn, String dest) throws Exception {
        String s = "{\"type\":\"RECEIVER_STATUS\",\"status\":{"
                + "\"applications\":[{\"appId\":\"CC1AD845\","
                + "\"displayName\":\"FireCast TV\","
                + "\"namespaces\":[{\"name\":\"" + NS_MEDIA + "\"}],"
                + "\"sessionId\":\"session-1\",\"statusText\":\"Prêt\","
                + "\"transportId\":\"transport-1\"}],"
                + "\"isActiveInput\":true,\"isStandBy\":false,"
                + "\"volume\":{\"level\":1.0,\"muted\":false}}}";
        sendMsg(conn, "receiver-0", dest, NS_RECEIVER, s);
    }

    private void sendLaunchStatus(WebSocket conn, String dest,
                                   String appId, int reqId) throws Exception {
        String s = "{\"type\":\"RECEIVER_STATUS\",\"requestId\":" + reqId + ",\"status\":{"
                + "\"applications\":[{\"appId\":\"" + appId + "\","
                + "\"displayName\":\"FireCast TV\","
                + "\"namespaces\":[{\"name\":\"" + NS_MEDIA + "\"}],"
                + "\"sessionId\":\"session-1\",\"statusText\":\"Prêt\","
                + "\"transportId\":\"transport-1\"}],"
                + "\"volume\":{\"level\":1.0,\"muted\":false}}}";
        sendMsg(conn, "receiver-0", dest, NS_RECEIVER, s);
    }

    private void sendMediaStatus(WebSocket conn, String dest,
                                  int reqId, String url) throws Exception {
        String s = "{\"type\":\"MEDIA_STATUS\",\"requestId\":" + reqId + ","
                + "\"status\":[{\"mediaSessionId\":" + mediaSessionId++ + ","
                + "\"playbackRate\":1,\"playerState\":\"PLAYING\","
                + "\"currentTime\":0,\"supportedMediaCommands\":15,"
                + "\"volume\":{\"level\":1.0,\"muted\":false},"
                + "\"media\":{\"contentId\":\"" + url + "\","
                + "\"streamType\":\"BUFFERED\",\"contentType\":\"video/mp4\"}}]}";
        sendMsg(conn, "receiver-0", dest, NS_MEDIA, s);
    }

    private void sendMsg(WebSocket conn, String src, String dest,
                          String ns, String payload) throws Exception {
        byte[] encoded = CastMessageParser.encode(src, dest, ns, payload);
        conn.send(ByteBuffer.wrap(encoded));
    }

    private void launchPlayer(String url) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra("url", url);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    @Override public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "Erreur WebSocket", ex);
    }

    @Override public void onStart() {
        Log.i(TAG, "✅ Cast V2 SSL actif sur port 8009");
    }
}
