package com.luminis2697.wifi_audiotx;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "WifiAudioTX";
    private static final int REQ_REC_AUDIO = 1001;

    private static final int DISCOVERY_PORT = 50006; // descubrimiento
    private static final int AUDIO_PORT = 50005;      // audio
    private static final int DISCOVERY_TIMEOUT_MS = 1500;
    private static final int DISCOVERY_ROUNDS = 3;

    private Spinner spinner;
    private TextView statusLbl;
    private Button refreshBtn, startBtn, stopBtn;

    private final List<ServerInfo> servers = new ArrayList<>();
    private ArrayAdapter<String> spinnerAdapter;

    private volatile boolean discovering = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinner = findViewById(R.id.server_spinner);
        statusLbl = findViewById(R.id.status_label);
        refreshBtn = findViewById(R.id.refresh_button);
        startBtn = findViewById(R.id.start_button);
        stopBtn = findViewById(R.id.stop_button);

        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        spinner.setAdapter(spinnerAdapter);

        refreshBtn.setOnClickListener(v -> discoverServers());

        startBtn.setOnClickListener(v -> {
            int idx = spinner.getSelectedItemPosition();
            if (idx < 0 || idx >= servers.size()) {
                statusLbl.setText("Selecciona un servidor");
                return;
            }
            ServerInfo s = servers.get(idx);
            Intent svc = new Intent(this, AudioUdpService.class);
            svc.putExtra("server_ip", s.host);
            svc.putExtra("server_port", s.port);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_REC_AUDIO);
            } else {
                startForegroundServiceCompat(svc);
            }
        });

        stopBtn.setOnClickListener(v -> stopService(new Intent(this, AudioUdpService.class)));

        discoverServers();
    }

    private void startForegroundServiceCompat(Intent svc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
    }

    private void discoverServers() {
        if (discovering) return;
        discovering = true;
        statusLbl.setText("Buscando servidores...");
        servers.clear();
        spinnerAdapter.clear();

        new Thread(() -> {
            Set<String> seen = new HashSet<>();
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress(0)); // puerto efímero
                sock.setBroadcast(true);
                sock.setSoTimeout(DISCOVERY_TIMEOUT_MS);

                byte[] probe = "DISCOVER_WIFI_AUDIO".getBytes();
                List<InetAddress> bcasts = new ArrayList<>();
                InetAddress globalBcast = InetAddress.getByName("255.255.255.255");
                bcasts.add(globalBcast);
                InetAddress wifiBcast = getWifiBroadcast();
                if (wifiBcast != null && !wifiBcast.equals(globalBcast)) bcasts.add(wifiBcast);

                for (int round = 0; round < DISCOVERY_ROUNDS; round++) {
                    for (InetAddress dst : bcasts) {
                        DatagramPacket pkt = new DatagramPacket(probe, probe.length, new InetSocketAddress(dst, DISCOVERY_PORT));
                        try { sock.send(pkt); } catch (Exception ignore) {}
                    }

                    long end = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS;
                    while (System.currentTimeMillis() < end) {
                        byte[] buf = new byte[2048];
                        DatagramPacket resp = new DatagramPacket(buf, buf.length);
                        try {
                            sock.receive(resp);
                        } catch (Exception e) {
                            break;
                        }
                        String payload = new String(resp.getData(), 0, resp.getLength()).trim();
                        ServerInfo info = parseServer(payload, resp);
                        if (info != null) {
                            String key = info.host + ":" + info.port;
                            if (seen.add(key)) {
                                servers.add(info);
                                runOnUiThread(() -> {
                                    spinnerAdapter.add(info.display());
                                    spinnerAdapter.notifyDataSetChanged();
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "discoverServers error", e);
            } finally {
                if (sock != null) sock.close();
                discovering = false;
                runOnUiThread(() -> {
                    if (servers.isEmpty()) statusLbl.setText("Sin servidores");
                    else statusLbl.setText("Servidores encontrados: " + servers.size());
                });
            }
        }, "discovery").start();
    }

    private @Nullable InetAddress getWifiBroadcast() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            DhcpInfo dhcp = wm.getDhcpInfo();
            if (dhcp == null) return null;
            int bc = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
            byte[] quads = new byte[4];
            for (int k = 0; k < 4; k++) quads[k] = (byte) ((bc >> (k * 8)) & 0xFF);
            InetAddress addr = InetAddress.getByAddress(quads);
            return (addr instanceof Inet4Address) ? addr : null;
        } catch (Exception e) {
            return null;
        }
    }

    private @Nullable ServerInfo parseServer(String payload, DatagramPacket resp) {
        try {
            if (payload.startsWith("{")) {
                JSONObject o = new JSONObject(payload);
                String name = o.optString("name", "Wifi-AudioRT");
                String host = o.optString("host", resp.getAddress().getHostAddress());
                int port = o.optInt("port", AUDIO_PORT);
                return new ServerInfo(name, host, port);
            } else {
                String s = payload.replaceAll("\\s+", " ").trim();
                if (s.startsWith("WIFI_AUDIO_SERVER")) {
                    String[] t = s.split(" ");
                    if (t.length >= 4) {
                        String name = t[1];
                        String host = t[2];
                        int port = safePort(t[3], AUDIO_PORT);
                        return new ServerInfo(name, host, port);
                    }
                }
            }
        } catch (Exception ignore) {}
        return new ServerInfo("Wifi-AudioRT", resp.getAddress().getHostAddress(), AUDIO_PORT);
    }

    private int safePort(String s, int def) {
        try {
            int p = Integer.parseInt(s);
            if (p >= 1 && p <= 65535) return p;
        } catch (Exception ignore) {}
        return def;
    }

    private ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {}
        @Override public void onServiceDisconnected(ComponentName name) {}
    };

    // ----------------- ServerInfo -----------------
    private static class ServerInfo {
        final String name;
        final String host;
        final int port;
        ServerInfo(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
        String display() {
            return name + " (" + host + ":" + port + ")";
        }
    }
}
