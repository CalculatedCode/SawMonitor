package com.calculatedcode.sawmonitor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
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
    private EditText highThreshold;
    private EditText holdSeconds;
    private SeekBar monitorVolume;
    private TextView volumeLabel;
    private TextView dbText;
    private TextView statusText;
    private TextView routeText;
    private ProgressBar meter;

    private final List<DeviceChoice> inputChoices = new ArrayList<>();
    private final List<DeviceChoice> outputChoices = new ArrayList<>();
    private AlarmPlayer testAlarm;

    private final Runnable uiUpdater = new Runnable() {
        @Override public void run() {
            double db = MonitorService.currentDb;
            dbText.setText(String.format(Locale.US, "%.1f dBFS", db));
            statusText.setText(MonitorService.statusText);
            routeText.setText("Actual input: " + MonitorService.actualInput +
                    "\nActual output: " + MonitorService.actualOutput);

            int meterValue = (int) Math.round(Math.max(0.0, Math.min(100.0, db + 100.0)));
            meter.setProgress(meterValue);

            if (MonitorService.alarmActive) {
                statusText.setText("⚠ " + MonitorService.statusText);
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
        subtitle.setText("Listen to a call-capable Bluetooth speaker microphone on your phone and alarm when the sound level stays too quiet or too loud.");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        root.addView(subtitle);

        root.addView(sectionLabel("Microphone input"));
        inputSpinner = new Spinner(this);
        root.addView(inputSpinner, matchWrap());

        root.addView(sectionLabel("Monitoring / alarm output"));
        outputSpinner = new Spinner(this);
        root.addView(outputSpinner, matchWrap());

        Button refresh = new Button(this);
        refresh.setText("Refresh audio devices");
        refresh.setOnClickListener(v -> refreshDevices());
        root.addView(refresh, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("For a JBL with a call microphone, pair it in Android Bluetooth settings first, then choose the Bluetooth call audio / SCO input. Phone speaker is selected by default for output.");
        hint.setPadding(0, dp(8), 0, dp(16));
        root.addView(hint);

        routeText = new TextView(this);
        routeText.setText("Actual input: —\nActual output: —");
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

        highThreshold = numericField("-3");
        root.addView(sectionLabel("High alarm threshold (dBFS)"));
        root.addView(highThreshold, matchWrap());

        holdSeconds = numericField("3");
        root.addView(sectionLabel("Time outside threshold before alarm (seconds)"));
        root.addView(holdSeconds, matchWrap());

        root.addView(sectionLabel("Live monitor volume"));
        monitorVolume = new SeekBar(this);
        monitorVolume.setMax(100);
        monitorVolume.setProgress(75);
        root.addView(monitorVolume, matchWrap());

        volumeLabel = new TextView(this);
        volumeLabel.setText("75%");
        root.addView(volumeLabel);
        monitorVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeLabel.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

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
        double high;
        double hold;
        try {
            low = Double.parseDouble(lowThreshold.getText().toString().trim());
            high = Double.parseDouble(highThreshold.getText().toString().trim());
            hold = Double.parseDouble(holdSeconds.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Check the threshold and delay values", Toast.LENGTH_LONG).show();
            return;
        }
        if (low >= high) {
            Toast.makeText(this, "Low threshold must be lower than high threshold", Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, MonitorService.class);
        i.setAction(MonitorService.ACTION_START);
        i.putExtra("inputDeviceId", input.id);
        i.putExtra("outputDeviceId", output.id);
        i.putExtra("lowThreshold", low);
        i.putExtra("highThreshold", high);
        i.putExtra("holdMillis", Math.max(250L, (long) (hold * 1000.0)));
        i.putExtra("monitorVolume", monitorVolume.getProgress() / 100f);

        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
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
