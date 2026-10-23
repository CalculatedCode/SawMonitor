package com.calculatedcode.sawmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;
import java.util.Locale;

public class MonitorService extends Service {
    public static final String ACTION_START = "com.calculatedcode.sawmonitor.START";
    public static final String ACTION_ACK = "com.calculatedcode.sawmonitor.ACK";

    public static volatile double currentDb = -120.0;
    public static volatile String statusText = "Stopped";
    public static volatile String actualInput = "—";
    public static volatile String actualOutput = "—";
    public static volatile boolean alarmActive = false;
    public static volatile boolean monitoring = false;

    private static final String CHANNEL_ID = "saw_monitor";
    private static final int NOTIFICATION_ID = 101;

    private volatile boolean running = false;
    private Thread monitorThread;
    private AudioRecord recorder;
    private AudioTrack monitorTrack;
    private AlarmPlayer alarmPlayer;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;

    private int inputDeviceId;
    private int outputDeviceId;
    private double lowThreshold;
    private double highThreshold;
    private long holdMillis;
    private float monitorVolume;

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SawMonitor:AudioWakeLock");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_ACK.equals(action)) {
            silenceAlarm();
            return START_STICKY;
        }

        inputDeviceId = intent == null ? -1 : intent.getIntExtra("inputDeviceId", -1);
        outputDeviceId = intent == null ? -1 : intent.getIntExtra("outputDeviceId", -1);
        lowThreshold = intent == null ? -45.0 : intent.getDoubleExtra("lowThreshold", -45.0);
        highThreshold = intent == null ? -3.0 : intent.getDoubleExtra("highThreshold", -3.0);
        holdMillis = intent == null ? 3000L : intent.getLongExtra("holdMillis", 3000L);
        monitorVolume = intent == null ? 0.75f : intent.getFloatExtra("monitorVolume", 0.75f);

        Notification n = buildNotification("Starting audio monitor…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

        restartMonitorThread();
        return START_STICKY;
    }

    private synchronized void restartMonitorThread() {
        stopAudio();
        running = true;
        monitoring = true;
        statusText = "Starting…";
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(); } catch (Exception ignored) {}
        }
        monitorThread = new Thread(this::monitorLoop, "SawMonitorCapture");
        monitorThread.start();
    }

    private void monitorLoop() {
        long belowSince = 0L;
        long aboveSince = 0L;
        long graceUntil = System.currentTimeMillis() + 5000L;
        double smoothedRms = 0.0;

        try {
            AudioDeviceInfo selectedInput = findDevice(inputDeviceId, true);
            AudioDeviceInfo selectedOutput = findDevice(outputDeviceId, false);

            if (selectedInput == null) {
                statusText = "Selected microphone is no longer available";
                return;
            }
            if (selectedOutput == null) {
                selectedOutput = findBuiltInSpeaker();
            }

            boolean bluetoothSco = selectedInput.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
            configureBluetoothInput(selectedInput, bluetoothSco);

            int sampleRate = bluetoothSco ? 16000 : 48000;
            int minRecord = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minRecord <= 0) {
                sampleRate = 16000;
                minRecord = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
            }
            if (minRecord <= 0) throw new IllegalStateException("No supported recording sample rate");

            recorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minRecord * 2, sampleRate))
                    .build();

            recorder.setPreferredDevice(selectedInput);

            int minPlay = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            monitorTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minPlay * 2, sampleRate))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            if (selectedOutput != null) {
                monitorTrack.setPreferredDevice(selectedOutput);
            }
            monitorTrack.setVolume(Math.max(0f, Math.min(1f, monitorVolume)));

            recorder.startRecording();
            monitorTrack.play();
            Thread.sleep(250);

            AudioDeviceInfo routedIn = recorder.getRoutedDevice();
            AudioDeviceInfo routedOut = monitorTrack.getRoutedDevice();
            actualInput = routedIn == null ? deviceLabel(selectedInput) : deviceLabel(routedIn);
            actualOutput = routedOut == null
                    ? (selectedOutput == null ? "System default" : deviceLabel(selectedOutput))
                    : deviceLabel(routedOut);

            statusText = "Monitoring";
            updateNotification();

            short[] buffer = new short[Math.max(1024, minRecord / 2)];

            while (running) {
                int read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) continue;

                double sum = 0.0;
                for (int i = 0; i < read; i++) {
                    double v = buffer[i];
                    sum += v * v;
                }
                double rms = Math.sqrt(sum / read);
                if (smoothedRms == 0.0) smoothedRms = rms;
                else smoothedRms = 0.22 * rms + 0.78 * smoothedRms;

                currentDb = 20.0 * Math.log10(Math.max(smoothedRms / 32767.0, 0.000001));

                if (monitorTrack != null) {
                    monitorTrack.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING);
                    AudioDeviceInfo ro = monitorTrack.getRoutedDevice();
                    if (ro != null) actualOutput = deviceLabel(ro);
                }
                AudioDeviceInfo ri = recorder.getRoutedDevice();
                if (ri != null) actualInput = deviceLabel(ri);

                long now = System.currentTimeMillis();
                if (now < graceUntil || alarmActive) continue;

                if (currentDb < lowThreshold) {
                    if (belowSince == 0L) belowSince = now;
                    if (now - belowSince >= holdMillis) {
                        triggerAlarm("TOO QUIET", currentDb);
                    }
                } else {
                    belowSince = 0L;
                }

                if (currentDb > highThreshold) {
                    if (aboveSince == 0L) aboveSince = now;
                    if (now - aboveSince >= holdMillis) {
                        triggerAlarm("TOO LOUD", currentDb);
                    }
                } else {
                    aboveSince = 0L;
                }
            }
        } catch (SecurityException e) {
            statusText = "Microphone/Bluetooth permission denied";
        } catch (Exception e) {
            statusText = "Audio error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            stopAudioObjects();
            monitoring = false;
            if (!alarmActive) {
                currentDb = -120.0;
            }
            updateNotification();
        }
    }

    private void configureBluetoothInput(AudioDeviceInfo selectedInput, boolean bluetoothSco) throws InterruptedException {
        if (audioManager == null || !bluetoothSco) return;

        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioDeviceInfo> communicationDevices = audioManager.getAvailableCommunicationDevices();
                for (AudioDeviceInfo d : communicationDevices) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        audioManager.setCommunicationDevice(d);
                        Thread.sleep(700);
                        break;
                    }
                }
            } catch (SecurityException ignored) {}
        } else {
            try {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
                Thread.sleep(1200);
            } catch (Exception ignored) {}
        }
    }

    private synchronized void triggerAlarm(String reason, double db) {
        if (alarmActive) return;
        alarmActive = true;
        statusText = reason + " — " + String.format(Locale.US, "%.1f dBFS", db);
        alarmPlayer = new AlarmPlayer(this, outputDeviceId);
        alarmPlayer.startLoop();
        updateNotification();
    }

    private synchronized void silenceAlarm() {
        if (alarmPlayer != null) {
            alarmPlayer.stop();
            alarmPlayer = null;
        }
        alarmActive = false;
        statusText = monitoring ? "Monitoring — alarm acknowledged" : "Stopped";
        updateNotification();
    }

    private AudioDeviceInfo findDevice(int id, boolean input) {
        if (audioManager == null) return null;
        int flag = input ? AudioManager.GET_DEVICES_INPUTS : AudioManager.GET_DEVICES_OUTPUTS;
        try {
            for (AudioDeviceInfo d : audioManager.getDevices(flag)) {
                if (d.getId() == id) return d;
            }
        } catch (SecurityException ignored) {}
        return null;
    }

    private AudioDeviceInfo findBuiltInSpeaker() {
        if (audioManager == null) return null;
        try {
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return d;
            }
        } catch (SecurityException ignored) {}
        return null;
    }

    public static String deviceLabel(AudioDeviceInfo d) {
        if (d == null) return "Unknown device";
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: type = "Phone mic"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: type = "Phone speaker"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: type = "Bluetooth call audio"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: type = "Bluetooth A2DP"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: type = "Wired headset"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: type = "Wired headphones"; break;
            case AudioDeviceInfo.TYPE_USB_DEVICE: type = "USB audio"; break;
            case AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB headset"; break;
            case AudioDeviceInfo.TYPE_TELEPHONY: type = "Telephony"; break;
            default: type = "Audio type " + d.getType();
        }
        String product;
        try {
            CharSequence name = d.getProductName();
            product = name == null ? "" : name.toString().trim();
        } catch (SecurityException e) {
            product = "";
        }
        if (product.isEmpty()) return type;
        return product + " — " + type;
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent ackIntent = new Intent(this, MonitorService.class);
        ackIntent.setAction(ACTION_ACK);
        PendingIntent ack = PendingIntent.getService(
                this, 1, ackIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(alarmActive ? "Saw Monitor — ALARM" : "Saw Monitor")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Silence alarm", ack).build())
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(statusText));
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Saw monitoring", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the Bluetooth microphone monitor running");
        nm.createNotificationChannel(ch);
    }

    private synchronized void stopAudio() {
        running = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
        }
        if (monitorTrack != null) {
            try { monitorTrack.stop(); } catch (Exception ignored) {}
        }
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        stopAudioObjects();
        silenceAlarm();
    }

    private void stopAudioObjects() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (monitorTrack != null) {
            try { monitorTrack.pause(); } catch (Exception ignored) {}
            try { monitorTrack.flush(); } catch (Exception ignored) {}
            try { monitorTrack.stop(); } catch (Exception ignored) {}
            try { monitorTrack.release(); } catch (Exception ignored) {}
            monitorTrack = null;
        }

        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= 31) {
                try { audioManager.clearCommunicationDevice(); } catch (Exception ignored) {}
            } else {
                try { audioManager.setBluetoothScoOn(false); } catch (Exception ignored) {}
                try { audioManager.stopBluetoothSco(); } catch (Exception ignored) {}
            }
            try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        stopAudio();
        monitoring = false;
        statusText = "Stopped";
        actualInput = "—";
        actualOutput = "—";
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
