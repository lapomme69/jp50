package com.example.kanaquiz;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int MIC_REQUEST = 2401;
    private static final int SYSTEM_VOICE_REQUEST = 2402;
    private static final String TAG = "KanaQuizVoice";
    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listeningActive = false;
    private boolean systemVoiceLaunched = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setupWebView();
        setupTts();
        requestMicIfNeeded();
    }

    private void setupWebView() {
        webView = new WebView(this);
        setContentView(webView);
        WebSettings w = webView.getSettings();
        w.setJavaScriptEnabled(true);
        w.setDomStorageEnabled(true);
        w.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidVoice");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestMicIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MIC_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                sendState("마이크 권한 허용 완료 · [시작]을 눌러 주세요.");
            } else {
                sendError("마이크 권한이 없습니다. 휴대폰 설정 → 앱 → 일본어 50음도 → 권한 → 마이크를 허용해 주세요.");
            }
        }
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.JAPAN);
                tts.setSpeechRate(.8f);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!ttsReady) sendState("일본어 TTS 음성이 없습니다. 휴대폰 TTS에서 일본어 음성을 설치해 주세요.");
            }
        });
    }

    private Intent makeSystemSpeechIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "일본어 발음을 말해 주세요");
        return i;
    }

    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendError("먼저 마이크 권한을 허용해 주세요.");
            requestMicIfNeeded();
            return;
        }

        listeningActive = true;
        systemVoiceLaunched = false;
        sendState("📱 휴대폰 시스템 음성인식을 실행합니다...");
        sendMeter(0);

        // V23의 SpeechRecognizer 직접 연결을 사용하지 않습니다.
        // 이 버전은 Android가 실제로 사용하는 시스템 Recognition Activity를 직접 호출합니다.
        try {
            Intent i = makeSystemSpeechIntent();
            systemVoiceLaunched = true;
            startActivityForResult(i, SYSTEM_VOICE_REQUEST);
            sendState("🎤 시스템 음성인식 화면을 여는 중입니다. 일본어로 발음해 주세요.");
        } catch (ActivityNotFoundException e) {
            systemVoiceLaunched = false;
            listeningActive = false;
            sendError("시스템 음성인식 화면을 실행할 수 없습니다.");
        } catch (SecurityException e) {
            systemVoiceLaunched = false;
            listeningActive = false;
            sendError("음성인식 실행 권한 오류입니다. 마이크 권한을 확인해 주세요.");
        } catch (Throwable e) {
            systemVoiceLaunched = false;
            listeningActive = false;
            sendError("음성인식 실행 오류: " + e.getClass().getSimpleName());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SYSTEM_VOICE_REQUEST) return;

        systemVoiceLaunched = false;
        if (!listeningActive) return;

        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                sendCandidates(results);
                return;
            }
        }
        sendError("음성인식 결과가 없습니다. 다시 [시작]을 눌러 주세요.");
    }

    private void stopListening() {
        listeningActive = false;
        systemVoiceLaunched = false;
        // 시스템 음성인식 Activity가 열려 있다면 Back으로 닫지 않습니다.
        // 사용자가 시스템 화면에서 완료/취소하도록 둡니다.
    }

    private void speakJapanese(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ttsReady) {
            sendState("일본어 TTS 준비 중입니다...");
            return;
        }
        try {
            tts.stop();
            tts.setLanguage(Locale.JAPAN);
            tts.setSpeechRate(.8f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kana-v25");
        } catch (Throwable e) {
            sendError("일본어 발음을 재생하지 못했습니다.");
        }
    }

    private String quote(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private void sendCandidates(ArrayList<String> list) {
        StringBuilder js = new StringBuilder("window.onNativeSpeechCandidates([");
        for (int n = 0; n < list.size(); n++) {
            if (n > 0) js.append(',');
            js.append(quote(list.get(n)));
        }
        js.append("])" );
        runJs(js.toString());
    }

    private void sendState(String s) { runJs("if(window.onNativeSpeechState)window.onNativeSpeechState(" + quote(s) + ")"); }
    private void sendError(String s) { runJs("if(window.onNativeSpeechError)window.onNativeSpeechError(" + quote(s) + ")"); }
    private void sendMeter(float v) { runJs("if(window.onNativeMicLevel)window.onNativeMicLevel(" + v + ")"); }

    private void runJs(String code) {
        if (webView == null) return;
        webView.post(() -> {
            try { webView.evaluateJavascript(code, null); } catch (Throwable ignored) {}
        });
    }

    public class Bridge {
        @JavascriptInterface public void startListening() { runOnUiThread(() -> startListening()); }
        @JavascriptInterface public void stopListening() { runOnUiThread(() -> stopListening()); }
        @JavascriptInterface public void speak(String text) { runOnUiThread(() -> speakJapanese(text)); }
        @JavascriptInterface public boolean isSystemVoiceActive() { return systemVoiceLaunched; }
    }

    @Override protected void onDestroy() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        try { if (webView != null) webView.destroy(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
