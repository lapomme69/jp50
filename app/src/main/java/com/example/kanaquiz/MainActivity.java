package com.example.kanaquiz;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int utteranceNo = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setupWebView();
        setupTts();
    }

    private void setupWebView() {
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidTTS");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = setJapaneseTtsLanguage(tts);
                tts.setSpeechRate(0.78f);
                tts.setPitch(1.0f);
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            } else ttsReady = false;
        });
    }

    private int setJapaneseTtsLanguage(TextToSpeech engine) {
        int r = engine.setLanguage(Locale.JAPAN);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            r = engine.setLanguage(Locale.JAPANESE);
        }
        return r;
    }

    private void runJs(String js) {
        if (webView != null) webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private class Bridge {
        @JavascriptInterface public void speak(String text) {
            if (text == null || text.trim().isEmpty()) return;
            runOnUiThread(() -> {
                if (!ttsReady || tts == null) { runJs("window.onTtsError && window.onTtsError('TTS');"); return; }
                try {
                    tts.stop();
                    int langResult = setJapaneseTtsLanguage(tts);
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) { runJs("window.onTtsError && window.onTtsError('Japanese TTS is unavailable');"); return; }
                    tts.setSpeechRate(0.78f);
                    final String id = "kana-" + (++utteranceNo);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {}
                        @Override public void onDone(String utteranceId) { runJs("window.onSpoken && window.onSpoken(" + quote(text) + ");"); }
                        @Override public void onError(String utteranceId) { runJs("window.onTtsError && window.onTtsError('TTS');"); }
                    });
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
                } catch (Throwable e) { runJs("window.onTtsError && window.onTtsError('TTS');"); }
            });
        }

        @JavascriptInterface public void exitApp() {
            runOnUiThread(() -> {
                try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
                finishAndRemoveTask();
            });
        }

        @JavascriptInterface public void translate(String korean) {
            if (korean == null || korean.trim().isEmpty()) return;
            executor.execute(() -> {
                try {
                    String q = URLEncoder.encode(korean, "UTF-8");
                    URL url = new URL("https://api.mymemory.translated.net/get?q=" + q + "&langpair=ko|ja");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(7000); c.setReadTimeout(7000); c.setRequestMethod("GET");
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close(); c.disconnect();
                    String result = extract(sb.toString(), "translatedText");
                    if (result == null || result.trim().isEmpty()) throw new Exception("empty");
                    String japanese = unescapeJson(result);
                    String furiganaHtml;
                    try {
                        furiganaHtml = fetchFuriganaHtml(japanese);
                    } catch (Throwable furiganaError) {
                        furiganaHtml = "<span class=\"jp-main\">" + escapeHtml(japanese) + "</span>";
                    }
                    runJs("window.onTranslated && window.onTranslated(" + quote(japanese) + "," + quote(furiganaHtml) + ");");
                } catch (Throwable e) { runJs("window.onTranslateError && window.onTranslateError();"); }
            });
        }
    }


    private String fetchFuriganaHtml(String japanese) throws Exception {
        URL url = new URL("https://shirabe.dev/api/v1/text/furigana");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(7000); c.setReadTimeout(7000); c.setRequestMethod("POST");
        c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        JSONObject req = new JSONObject(); req.put("text", japanese);
        byte[] body = req.toString().getBytes("UTF-8");
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close(); c.disconnect();
        JSONObject root = new JSONObject(sb.toString());
        JSONArray tokens = root.optJSONArray("tokens");
        if (tokens == null) throw new Exception("no tokens");
        StringBuilder html = new StringBuilder();
        for (int i=0;i<tokens.length();i++) {
            JSONObject tok = tokens.getJSONObject(i);
            String surface = tok.optString("surface", "");
            String reading = tok.optString("reading", "");
            if (surface.isEmpty()) continue;
            if (!reading.isEmpty() && !reading.equals(surface) && containsKanji(surface)) {
                html.append("<ruby>").append(escapeHtml(surface)).append("<rt>").append(escapeHtml(reading)).append("</rt></ruby>");
            } else {
                html.append(escapeHtml(surface));
            }
        }
        if (html.length()==0) throw new Exception("empty html");
        return html.toString();
    }

    private boolean containsKanji(String s) {
        for (int i=0;i<s.length();i++) {
            char ch=s.charAt(i);
            if ((ch>='\u3400'&&ch<='\u4DBF')||(ch>='\u4E00'&&ch<='\u9FFF')||(ch>='\uF900'&&ch<='\uFAFF')) return true;
        }
        return false;
    }

    private String escapeHtml(String s) {
        if (s==null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }

    private String extract(String json, String key) {
        Pattern p = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
        Matcher m = p.matcher(json); return m.find() ? m.group(1) : null;
    }
    private String unescapeJson(String s) {
        if (s == null) return "";
        String x = s.replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .replace("\\\\", "\\");
        Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(x);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            char ch = (char) Integer.parseInt(m.group(1), 16);
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(ch)));
        }
        m.appendTail(out);
        return out.toString();
    }
    private String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        if (tts != null) { try { tts.stop(); } catch (Throwable ignored) {} try { tts.shutdown(); } catch (Throwable ignored) {} }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
