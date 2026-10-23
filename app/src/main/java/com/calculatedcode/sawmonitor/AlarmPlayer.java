package com.calculatedcode.sawmonitor;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class AlarmPlayer {
    private final Context context;
    private final int outputDeviceId;
    private volatile boolean running = false;
    private Thread thread;
    private AudioTrack track;
    private int previousAlarmVolume = -1;

    public AlarmPlayer(Context context, int outputDeviceId) {
        this.context = context.getApplicationContext();
        this.outputDeviceId = outputDeviceId;
    }

    public synchronized void startLoop() {
        if (running) return;
        running = true;

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                previousAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            } catch (Exception ignored) {}
        }

        thread = new Thread(() -> {
            final int sampleRate = 44100;
            final int seconds = 1;
            short[] waveform = new short[sampleRate * seconds];

            for (int i = 0; i < waveform.length; i++) {
                double t = i / (double) sampleRate;
                double value;
                if (t < 0.24) {
                    value = Math.sin(2.0 * Math.PI * 880.0 * t);
                } else if (t < 0.34) {
                    value = 0.0;
                } else if (t < 0.58) {
                    value = Math.sin(2.0 * Math.PI * 1320.0 * t);
                } else {
                    value = 0.0;
                }
                waveform[i] = (short) (value * 28000);
            }

            int min = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(min, waveform.length * 2);

            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();

                AudioDeviceInfo output = findOutputDevice(outputDeviceId);
                if (output != null) {
                    track.setPreferredDevice(output);
                }

                track.play();
                while (running) {
                    track.write(waveform, 0, waveform.length, AudioTrack.WRITE_BLOCKING);
                }
            } catch (Exception ignored) {
            } finally {
                if (track != null) {
                    try { track.pause(); } catch (Exception ignored) {}
                    try { track.flush(); } catch (Exception ignored) {}
                    try { track.stop(); } catch (Exception ignored) {}
                    try { track.release(); } catch (Exception ignored) {}
                    track = null;
                }
            }
        }, "SawMonitorAlarm");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) {}
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && previousAlarmVolume >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0);
            } catch (Exception ignored) {}
        }
        previousAlarmVolume = -1;
    }

    private AudioDeviceInfo findOutputDevice(int id) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getId() == id) return d;
            }
        } catch (SecurityException ignored) {}
        return null;
    }
}
