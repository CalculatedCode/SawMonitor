package com.calculatedcode.sawmonitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AudioManager audioManager;
    private Spinner inputSpinner;
    private Spinner outputSpinner;
    private EditText lowThreshold;
    private EditText holdSeconds;
    private TextView dbText;
    private TextView statusText;
    private TextView routeText;
    private ProgressBar meter;
    private Button liveAudioButton;
    private SeekBar liveVolume;
    private TextView liveVolumeLabel;

    private final List<DeviceChoice> inputChoices = new ArrayList<>();
    private final List<DeviceChoice> outputChoices = new ArrayList<>();
    private AlarmPlayer testAlarm;

    private final Runnable uiUpdater = new Runnable() {
        @Override public void run() {
            double db = MonitorService.currentDb;
            dbText.setText(String.format(Locale.US, "%.1f dBFS", db));
            statusText.setText(MonitorService.statusText);
            routeText.setText("Actual input: " + MonitorService.actualInput +
                    "\nOutput: " + MonitorService.actualOutput +
                    "\nCapture mode: " + MonitorService.captureMode +
                    "\nRoute stable: " + (MonitorService.routeStable ? "yes" : "no"));

            int meterValue = (int) Math.round(Math.max(0.0, Math.min(100.0, db + 100.0)));
            meter.setProgress(meterValue);

            if (MonitorService.alarmActive) {
                statusText.setText("⚠ " + MonitorService.statusText);
            }

            if (liveAudioButton != null) {
                liveAudioButton.setText(MonitorService.liveMonitorEnabled
                        ? "Live audio: ON"
                        : "Live audio: OFF");
            }

            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        buildUi();
        requestNeededPermissions();
        refreshDevices();
        handler.post(uiUpdater);
    }

    private void buildUi() {
        int pad = dp(16);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Rock Saw Monitor");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Background level monitor for a call-capable Bluetooth speaker microphone. Other apps can play normal media; Saw Monitor only makes sound if the input stays too quiet.");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        root.addView(subtitle);

        root.addView(sectionLabel("Microphone input"));
        inputSpinner = new Spinner(this);
        root.addView(inputSpinner, matchWrap());

        Button prepareBluetooth = new Button(this);
        prepareBluetooth.setText("Prepare Bluetooth for monitoring");
        prepareBluetooth.setOnClickListener(v -> showBluetoothSetupDialog());
        root.addView(prepareBluetooth, matchWrap());

        TextView prepareHint = new TextView(this);
        prepareHint.setText("Required for using the JBL microphone while TikTok, YouTube, or other media plays from the phone.");
        prepareHint.setPadding(0, dp(4), 0, dp(10));
        root.addView(prepareHint);

        root.addView(sectionLabel("Alarm / live audio output"));
        outputSpinner = new Spinner(this);
        root.addView(outputSpinner, matchWrap());

        liveAudioButton = new Button(this);
        liveAudioButton.setText("Live audio: OFF");
        liveAudioButton.setOnClickListener(v -> toggleLiveAudio());
        root.addView(liveAudioButton, matchWrap());

        root.addView(sectionLabel("Live audio volume"));
        liveVolume = new SeekBar(this);
        liveVolume.setMax(100);
        liveVolume.setProgress(60);
        root.addView(liveVolume, matchWrap());

        liveVolumeLabel = new TextView(this);
        liveVolumeLabel.setText("60%");
        root.addView(liveVolumeLabel);

        liveVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                liveVolumeLabel.setText(progress + "%");
                if (fromUser && MonitorService.monitoring && MonitorService.liveMonitorEnabled) {
                    sendLiveAudioState(true);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        TextView liveHint = new TextView(this);
        liveHint.setText("Optional. OFF only measures the sound level. ON also plays the JBL microphone through the selected output. No audio focus is requested, so normal media can continue playing.");
        liveHint.setPadding(0, dp(4), 0, dp(10));
        root.addView(liveHint);

        Button refresh = new Button(this);
        refresh.setText("Refresh audio devices");
        refresh.setOnClickListener(v -> refreshDevices());
        root.addView(refresh, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("For a JBL with a call microphone: keep Phone calls ON and turn Media audio OFF in Android Bluetooth settings. Then choose the Bluetooth call audio / SCO input here. With Live audio OFF, the phone stays silent unless the alarm fires.");
        hint.setPadding(0, dp(8), 0, dp(16));
        root.addView(hint);

        routeText = new TextView(this);
        routeText.setText("Actual input: —\nOutput: —\nCapture mode: —\nRoute stable: no");
        routeText.setPadding(0, 0, 0, dp(12));
        root.addView(routeText);

        dbText = new TextView(this);
        dbText.setText("-120.0 dBFS");
        dbText.setTextSize(34);
        dbText.setGravity(Gravity.CENTER_HORIZONTAL);
        dbText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(dbText, matchWrap());

        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        meter.setProgress(0);
        root.addView(meter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        TextView dbNote = new TextView(this);
        dbNote.setText("Level is dBFS (digital level), not calibrated dB SPL. Set thresholds after watching the normal saw level for a few seconds.");
        dbNote.setPadding(0, dp(6), 0, dp(14));
        root.addView(dbNote);

        lowThreshold = numericField("-45");
        root.addView(sectionLabel("Low alarm threshold (dBFS)"));
        root.addView(lowThreshold, matchWrap());

        holdSeconds = numericField("3");
        root.addView(sectionLabel("Time outside threshold before alarm (seconds)"));
        root.addView(holdSeconds, matchWrap());

        statusText = new TextView(this);
        statusText.setText("Stopped");
        statusText.setTextSize(18);
        statusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusText.setPadding(0, dp(16), 0, dp(8));
        root.addView(statusText);

        LinearLayout row1 = horizontalRow();
        Button start = new Button(this);
        start.setText("Start monitoring");
        start.setOnClickListener(v -> startMonitoring());
        row1.addView(start, weighted());

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> stopMonitoring());
        row1.addView(stop, weighted());
        root.addView(row1, matchWrap());

        LinearLayout row2 = horizontalRow();
        Button silence = new Button(this);
        silence.setText("Silence alarm");
        silence.setOnClickListener(v -> silenceAlarm());
        row2.addView(silence, weighted());

        Button test = new Button(this);
        test.setText("Test alarm");
        test.setOnClickListener(v -> testAlarm());
        row2.addView(test, weighted());
        root.addView(row2, matchWrap());

        TextView safety = new TextView(this);
        safety.setText("This app is a supplemental monitor only. Do not use it in place of the saw's guards, shutoff, or other safety controls.");
        safety.setPadding(0, dp(18), 0, dp(8));
        root.addView(safety);

        setContentView(scroll);
    }

    private void showBluetoothSetupDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Prepare JBL for Saw Monitor")
                .setMessage(
                        "Android does not let Saw Monitor toggle this profile setting itself.\n\n" +
                        "In Bluetooth settings:\n" +
                        "1. Tap the settings/gear next to your JBL.\n" +
                        "2. Turn Media audio OFF.\n" +
                        "3. Leave Phone calls ON.\n" +
                        "4. Return to Saw Monitor and start monitoring.\n\n" +
                        "This keeps the JBL microphone available for Saw Monitor while TikTok, YouTube, and other media play through the phone.")
                .setPositiveButton("Open Bluetooth settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (audioManager != null && inputSpinner != null && outputSpinner != null) {
            refreshDevices();
        }
    }

    private void requestNeededPermissions() {
        List<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!p.isEmpty()) {
            requestPermissions(p.toArray(new String[0]), 42);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42) refreshDevices();
    }

    private void refreshDevices() {
        inputChoices.clear();
        outputChoices.clear();

        if (audioManager == null) return;
        try {
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                inputChoices.add(new DeviceChoice(d.getId(), d.getType(), MonitorService.deviceLabel(d)));
            }
            for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                outputChoices.add(new DeviceChoice(d.getId(), d.getType(), MonitorService.deviceLabel(d)));
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permission is required to list devices", Toast.LENGTH_LONG).show();
        }

        ArrayAdapter<DeviceChoice> inAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, inputChoices);
        inAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        inputSpinner.setAdapter(inAdapter);

        ArrayAdapter<DeviceChoice> outAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, outputChoices);
        outAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        outputSpinner.setAdapter(outAdapter);

        for (int i = 0; i < inputChoices.size(); i++) {
            if (inputChoices.get(i).type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                inputSpinner.setSelection(i);
                break;
            }
        }
        for (int i = 0; i < outputChoices.size(); i++) {
            if (outputChoices.get(i).type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                outputSpinner.setSelection(i);
                break;
            }
        }
    }

    private void startMonitoring() {
        if (inputChoices.isEmpty() || outputChoices.isEmpty()) {
            Toast.makeText(this, "No selectable input/output device", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            return;
        }

        DeviceChoice input = (DeviceChoice) inputSpinner.getSelectedItem();
        DeviceChoice output = (DeviceChoice) outputSpinner.getSelectedItem();

        double low;
        double hold;
        try {
            low = Double.parseDouble(lowThreshold.getText().toString().trim());
            hold = Double.parseDouble(holdSeconds.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Check the threshold and delay values", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, MonitorService.class);
        i.setAction(MonitorService.ACTION_START);
        i.putExtra("inputDeviceId", input.id);
        i.putExtra("outputDeviceId", output.id);
        i.putExtra("lowThreshold", low);
        i.putExtra("holdMillis", Math.max(250L, (long) (hold * 1000.0)));
        i.putExtra("liveMonitorEnabled", MonitorService.liveMonitorEnabled);
        i.putExtra("monitorVolume", liveVolume.getProgress() / 100f);

        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void toggleLiveAudio() {
        boolean enable = !MonitorService.liveMonitorEnabled;
        sendLiveAudioState(enable);
    }

    private void sendLiveAudioState(boolean enable) {
        if (outputChoices.isEmpty()) return;

        DeviceChoice output = (DeviceChoice) outputSpinner.getSelectedItem();
        Intent i = new Intent(this, MonitorService.class);
        i.setAction(MonitorService.ACTION_SET_LIVE_AUDIO);
        i.putExtra("enabled", enable);
        i.putExtra("outputDeviceId", output.id);
        i.putExtra("monitorVolume", liveVolume.getProgress() / 100f);

        if (MonitorService.monitoring) {
            startService(i);
        } else {
            MonitorService.liveMonitorEnabled = enable;
            MonitorService.monitorVolume = liveVolume.getProgress() / 100f;
            liveAudioButton.setText(enable ? "Live audio: ON" : "Live audio: OFF");
            Toast.makeText(this,
                    enable ? "Live audio will start with monitoring" : "Live audio disabled",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void stopMonitoring() {
        stopService(new Intent(this, MonitorService.class));
    }

    private void silenceAlarm() {
        Intent i = new Intent(this, MonitorService.class);
        i.setAction(MonitorService.ACTION_ACK);
        startService(i);
    }

    private void testAlarm() {
        if (outputChoices.isEmpty()) return;
        DeviceChoice output = (DeviceChoice) outputSpinner.getSelectedItem();
        if (testAlarm != null) testAlarm.stop();
        testAlarm = new AlarmPlayer(this, output.id);
        testAlarm.startLoop();
        handler.postDelayed(() -> {
            if (testAlarm != null) {
                testAlarm.stop();
                testAlarm = null;
            }
        }, 2300);
    }

    private TextView sectionLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(15);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setPadding(0, dp(10), 0, dp(4));
        return v;
    }

    private EditText numericField(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(uiUpdater);
        if (testAlarm != null) testAlarm.stop();
        super.onDestroy();
    }

    private static class DeviceChoice {
        final int id;
        final int type;
        final String label;

        DeviceChoice(int id, int type, String label) {
            this.id = id;
            this.type = type;
            this.label = label;
        }

        @Override public String toString() {
            return label;
        }
    }
}
