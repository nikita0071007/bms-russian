package ru.gusevmade.bmsrussian;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import java.util.Locale;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 10;
    private static final int REQ_ENABLE = 11;

    private WebView web;
    private BmsBle ble;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0039A6);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0039A6);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("ru", "RU"));
                    ttsReady = true;
                }
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }
        });

        ble = new BmsBle(this, new BmsBle.Sink() {
            @Override
            public void emit(final String json) {
                web.post(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript("window.onBle&&window.onBle(" + json + ")", null);
                    }
                });
            }
        });
        web.addJavascriptInterface(new Bridge(), "BmsNative");

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private class Bridge {
        @JavascriptInterface
        public void connect() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { ensurePermissionsThenStart(); }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { ble.stop(); }
            });
        }

        @JavascriptInterface
        public void setTextZoom(final int percent) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { web.getSettings().setTextZoom(percent); }
            });
        }

        @JavascriptInterface
        public void setBrightness(final float v) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v >= 0.99f
                            ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                            : Math.max(0.05f, v);
                    getWindow().setAttributes(lp);
                }
            });
        }

        @JavascriptInterface
        public void speak(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bms");
                }
            });
        }

        @JavascriptInterface
        public void vibrate() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Vibrator vb = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (vb != null) vb.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 200, 400}, -1));
                }
            });
        }

        @JavascriptInterface
        public void copyText(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("BMS", text));
                }
            });
        }
    }

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private void ensurePermissionsThenStart() {
        String[] need = neededPermissions();
        boolean all = true;
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) all = false;
        }
        if (all) startBle();
        else requestPermissions(need, REQ_PERM);
    }

    private void startBle() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null && !a.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            return;
        }
        ble.start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM) return;
        boolean ok = results.length > 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) startBle();
        else web.evaluateJavascript(
                "window.onBle&&window.onBle({type:'state',on:false,text:'Нет разрешения Bluetooth. Разрешите в настройках приложения.'})", null);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_ENABLE) {
            if (result == RESULT_OK) ble.start();
            else web.evaluateJavascript(
                    "window.onBle&&window.onBle({type:'state',on:false,text:'Bluetooth не включён'})", null);
        }
    }

    @Override
    protected void onDestroy() {
        if (ble != null) ble.stop();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
