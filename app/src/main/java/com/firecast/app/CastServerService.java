package com.firecast.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.IOException;

/**
 * Service qui tourne en fond sur le Fire TV :
 * 1. Lance un serveur HTTP sur le port 8008 (protocole DIAL utilisé par Chromecast)
 * 2. Annonce le Fire TV sur le réseau local via mDNS (NSD) pour que
 *    les applis Cast puissent le détecter automatiquement
 */
public class CastServerService extends Service {

    private static final String TAG = "FireCast";
    private static final String CHANNEL_ID = "firecast_channel";
    private static final int NOTIF_ID = 1;

    private DialHttpServer dialServer;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        startDialServer();
        registerMdnsService();
    }

    // ── Serveur HTTP DIAL (port 8008) ─────────────────────────────────────────

    private void startDialServer() {
        dialServer = new DialHttpServer(this);
        try {
            dialServer.start();
            Log.i(TAG, "Serveur DIAL démarré sur le port 8008");
        } catch (IOException e) {
            Log.e(TAG, "Impossible de démarrer le serveur DIAL", e);
        }
    }

    // ── Annonce mDNS (NSD) ────────────────────────────────────────────────────

    private void registerMdnsService() {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("FireCast TV");
        serviceInfo.setServiceType("_googlecast._tcp.");
        serviceInfo.setPort(8009);

        // Attributs Chromecast requis pour la découverte
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            serviceInfo.setAttribute("fn", "FireCast TV");
            serviceInfo.setAttribute("md", "FireCast");
            serviceInfo.setAttribute("ve", "05");
            serviceInfo.setAttribute("ic", "/setup/icon.png");
            serviceInfo.setAttribute("ca", "4101");
            serviceInfo.setAttribute("st", "0");
            serviceInfo.setAttribute("bs", "FA8FCA5B2C5B");
        }

        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo s, int e) {
                Log.e(TAG, "Échec enregistrement mDNS: " + e);
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
            @Override public void onServiceRegistered(NsdServiceInfo s) {
                Log.i(TAG, "mDNS enregistré : " + s.getServiceName());
            }
            @Override public void onServiceUnregistered(NsdServiceInfo s) {}
        };

        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        nsdManager.registerService(serviceInfo,
                NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FireCast actif")
                .setContentText("En attente d'un cast...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "FireCast", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (dialServer != null) dialServer.stop();
        if (nsdManager != null && registrationListener != null) {
            nsdManager.unregisterService(registrationListener);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
