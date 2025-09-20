package com.luminis2697.wifi_audiotx;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

public class AudioUdpService extends Service {

    private static final String CHANNEL_ID = "WifiAudioTX";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final BinderImpl binder = new BinderImpl();
    private OnStateListener stateListener;

    public class BinderImpl extends Binder {
        public void setOnStateListener(OnStateListener l) { stateListener = l; }
        public void stopStreaming() { stopSelf(); }
    }
    public interface OnStateListener { void onState(String s); }
    private void emit(String s) { if (stateListener != null) stateListener.onState(s); }

    private MediaProjectionManager mpm;
    private MediaProjection projection;
    private AudioRecord recorder;
    private Thread worker;

    private String targetIp;
    private int targetPort;

    @Override
    public void onCreate() {
        super.onCreate();
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent.getIntExtra("RESULT_CODE", -1);
        Intent data = intent.getParcelableExtra("DATA");
        targetIp = intent.getStringExtra("SERVER_IP");
        targetPort = intent.getIntExtra("SERVER_PORT", 50005);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Wifi-AudioTX")
                .setContentText("Transmitiendo audio")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        startForeground(1, n);

        if (resultCode != -1 && data != null && targetIp != null) {
            projection = mpm.getMediaProjection(resultCode, data);
            startWorker();
        } else {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startWorker() {
        if (worker != null) return;

        worker = new Thread(() -> {
            emit("Conectando...");
            try (DatagramSocket udp = new DatagramSocket()) {
                udp.connect(new InetSocketAddress(targetIp, targetPort));

                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
                int buf = Math.max(min, 960 * 4); // ~20ms estéreo 16-bit

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    emit("Sin permiso de audio");
                    stopSelf();
                    return;
                }

                AudioPlaybackCaptureConfiguration cfg = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
                }

                AudioFormat fmt = new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(ENCODING)
                        .setChannelMask(CHANNEL_MASK)
                        .build();

                AudioRecord.Builder b = new AudioRecord.Builder()
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(buf);
                if (cfg != null) b.setAudioPlaybackCaptureConfig(cfg);
                recorder = b.build();

                recorder.startRecording();
                emit("Conectado");

                byte[] pcm = new byte[buf];
                while (!Thread.currentThread().isInterrupted()) {
                    int n = recorder.read(pcm, 0, pcm.length);
                    if (n > 0) {
                        DatagramPacket p = new DatagramPacket(pcm, n);
                        udp.send(p);
                    }
                }
            } catch (Exception e) {
                emit("Error: " + e.getMessage());
            } finally {
                cleanup();
                stopSelf();
            }
        }, "wifi-audio-udp");
        worker.start();
    }

    private void cleanup() {
        try { if (recorder != null) { recorder.stop(); recorder.release(); } } catch (Exception ignored) {}
        recorder = null;
        projection = null;
        worker = null;
        emit("Desconectado");
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Wifi-AudioTX", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }
}
