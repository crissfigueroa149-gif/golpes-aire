package cl.medidorsalto;

import android.app.Activity;
import android.content.ContentValues;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import java.util.Locale;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements SensorEventListener {
    private static final String WEB = "https://crissfigueroa149-gif.github.io/golpes-aire/salto/";
    private static final String ASSET = "https://appassets.androidplatform.net/assets/index.html";
    private TextToSpeech tts;
    private boolean ttsListo = false;
    private WebView web;
    private SensorManager sm;
    private Sensor acc;
    private HandlerThread ht;
    private final StringBuilder lote = new StringBuilder();
    private long ultimoEnvio = 0;
    private boolean activo = false;
    private long t0Nano = -1;
    private double offsetMs = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String h = r.getUrl().getHost();
                return !("crissfigueroa149-gif.github.io".equals(h) || "appassets.androidplatform.net".equals(h));
            }
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                // sin internet: usar la copia que viene dentro de la app
                if (r.isForMainFrame() && !ASSET.equals(r.getUrl().toString())) v.loadUrl(ASSET);
            }
        });
        web.addJavascriptInterface(new Puente(), "AndroidSensor");
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            public void onInit(int st) {
                if (st != TextToSpeech.SUCCESS) return;
                int r = tts.setLanguage(new Locale("es", "CL"));
                if (r < 0) r = tts.setLanguage(new Locale("es"));
                ttsListo = r >= 0;
            }
        });
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        web.loadUrl(WEB);
    }

    class Puente {
        @JavascriptInterface
        public boolean disponible() { return acc != null; }

        @JavascriptInterface
        public void hablar(final String texto) {
            if (ttsListo) tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "g");
        }

        @JavascriptInterface
        public void iniciar() {
            runOnUiThread(new Runnable() { public void run() { arrancar(); } });
        }

        @JavascriptInterface
        public void detener() {
            runOnUiThread(new Runnable() { public void run() { parar(); } });
        }

        @JavascriptInterface
        public String guardar(String nombre, String texto) {
            try {
                byte[] datos = texto.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, nombre);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    OutputStream o = getContentResolver().openOutputStream(u);
                    o.write(datos); o.close();
                } else {
                    File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    d.mkdirs();
                    FileOutputStream o = new FileOutputStream(new File(d, nombre));
                    o.write(datos); o.close();
                }
                return "ok";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }
    }

    private void arrancar() {
        if (acc == null || activo) return;
        if (ht == null) { ht = new HandlerThread("acc"); ht.start(); }
        t0Nano = -1;
        // 5000 us = 200 Hz. El sistema puede entregar menos si el telefono no lo permite.
        activo = sm.registerListener(this, acc, 5000, new Handler(ht.getLooper()));
    }

    private void parar() {
        if (activo) { sm.unregisterListener(this); activo = false; }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        double tMs = e.timestamp / 1e6;
        synchronized (lote) {
            lote.append(lote.length() == 0 ? "[" : ",")
                .append('[').append(tMs).append(',').append(e.values[0]).append(',').append(e.values[1]).append(',').append(e.values[2]).append(']');
        }
        long ahora = System.nanoTime();
        if (ahora - ultimoEnvio > 20_000_000L) {   // enviar cada ~20 ms
            ultimoEnvio = ahora;
            final String js;
            synchronized (lote) { lote.append(']'); js = "window.__movNativo&&window.__movNativo(" + lote + ")"; lote.setLength(0); }
            web.post(new Runnable() { public void run() { web.evaluateJavascript(js, null); } });
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }
    @Override protected void onPause() { super.onPause(); parar(); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
