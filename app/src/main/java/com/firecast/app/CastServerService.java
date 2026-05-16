package com.firecast.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.IOException;

public class CastServerService extends Service {

    private static final String TAG = "FireCast";
    private static final String CHANNEL_ID = "firecast_channel";
    private static final int NOTIF_ID = 1;

    private DialHttpServer dialServer;
    private CastV2Server castV2Server;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        acquireMulticastLock();
        startDialServer();
        startCastV2Server();
        registerMdnsService();
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("FireCastLock");
        multicastLock.setReferenceCounted(true);
        multicastLock.acquire();
    }

    private void startDialServer() {
        dialServer = new DialHttpServer(this);
        try {
            dialServer.start();
            Log.i(TAG, "Serveur DIAL démarré (port 8008)");
        } catch (IOException e) {
            Log.e(TAG, "Erreur DIAL", e);
        }
    }

    private void startCastV2Server() {
        castV2Server = new CastV2Server(this);
        try {
            castV2Server.start();
            Log.i(TAG, "Serveur Cast V2 démarré (port 8009)");
        } catch (Exception e) {
            Log.e(TAG, "Erreur Cast V2", e);
        }
    }

    private void registerMdnsService() {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("FireCast TV");
        serviceInfo.setServiceType("_googlecast._tcp.");
        serviceInfo.setPort(8009);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            serviceInfo.setAttribute("fn", "FireCast TV");
            serviceInfo.setAttribute("md", "FireCast Receiver");
            serviceInfo.setAttribute("ve", "05");
            serviceInfo.setAttribute("ic", "/setup/icon.png");
            serviceInfo.setAttribute("ca", "4101");
            serviceInfo.setAttribute("st", "0");
            serviceInfo.setAttribute("bs", "FA8FCA000001");
            serviceInfo.setAttribute("nf", "1");
            serviceInfo.setAttribute("rs", "");
        }

        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo s, int e) {
                Log.e(TAG, "mDNS échoué: " + e);
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
            @Override public void onServiceRegistered(NsdServiceInfo s) {
                Log.i(TAG, "✅ Visible sur le réseau comme : " + s.getServiceName());
            }
            @Override public void onServiceUnregistered(NsdServiceInfo s) {}
        };

        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

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
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (dialServer != null) dialServer.stop();
        if (castV2Server != null) try { castV2Server.stop(); } catch (Exception ignored) {}
        if (nsdManager != null && registrationListener != null)
            nsdManager.unregisterService(registrationListener);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
