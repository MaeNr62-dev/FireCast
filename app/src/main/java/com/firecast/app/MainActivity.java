package com.firecast.app;

import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;

public class MainActivity extends FragmentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Afficher l'IP du Fire TV
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(WIFI_SERVICE);
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());

        TextView tvIp = findViewById(R.id.tv_ip);
        tvIp.setText("📡 Adresse IP : " + ip);

        TextView tvStatus = findViewById(R.id.tv_status);
        tvStatus.setText("✅ FireCast est prêt à recevoir !");

        TextView tvName = findViewById(R.id.tv_name);
        tvName.setText("🎬 Nom du récepteur : FireCast TV");

        // Lancer le serveur Cast en arrière-plan
        Intent serviceIntent = new Intent(this, CastServerService.class);
        startForegroundService(serviceIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopService(new Intent(this, CastServerService.class));
    }
}
