package com.example.kanaquiz;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;

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
                int result = tts.setLanguage(Locale.JAPAN);
                tts.setSpeechRate(0.78f);
                tts.setPitch(1.0f);
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED;
                runJs("window.onTtsReady && window.onTtsReady(" + ttsReady + ");");
            } else {
                ttsReady = false;
                runJs("window.onTtsReady && window.onTtsReady(false);");
            }
        });
    }

    private void runJs(String js) {
        if (webView != null) webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private class Bridge {
        @JavascriptInterface
        public void speak(String text) {
            if (text == null || text.trim().isEmpty()) return;
            runOnUiThread(() -> {
                if (!ttsReady || tts == null) {
                    runJs("window.onTtsError && window.onTtsError('일본어 음성이 준비되지 않았습니다. 휴대폰의 TTS 설정에서 일본어 음성을 설치해 주세요.');");
                    return;
                }
                try {
                    tts.stop();
                    tts.setLanguage(Locale.JAPAN);
                    tts.setSpeechRate(0.78f);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {}
                        @Override public void onDone(String utteranceId) {
                            runJs("window.onSpoken && window.onSpoken(" + quote(text) + ");");
                        }
                        @Override public void onError(String utteranceId) {
                            runJs("window.onTtsError && window.onTtsError('일본어 발음을 재생하지 못했습니다.');");
                        }
                    });
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kana-v34");
                } catch (Throwable e) {
                    runJs("window.onTtsError && window.onTtsError('일본어 발음을 재생하지 못했습니다.');");
                }
            });
        }

        @JavascriptInterface
        public boolean isReady() { return ttsReady; }
    }

    private String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    @Override protected void onDestroy() {
        if (tts != null) {
            try { tts.stop(); } catch (Throwable ignored) {}
            try { tts.shutdown(); } catch (Throwable ignored) {}
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
