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
    private SpeechRecognizer speechRecognizer;
    private boolean recognizerReady = false;
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
            sendError("마이크 권한이 없습니다. 먼저 마이크 권한을 허용해 주세요.");
            requestMicIfNeeded();
            return;
        }
        listeningActive = true;
        systemVoiceLaunched = false;
        sendState("🎤 실제 Android 음성인식을 시작합니다...");
        sendMeter(0);
        try {
            startNativeRecognizer();
        } catch (Throwable e) {
            // 어떤 예외가 나도 앱을 종료시키지 않고 사용자에게 알려 줍니다.
            listeningActive = false;
            sendError("음성인식을 시작할 수 없습니다: " + e.getClass().getSimpleName());
        }
    }

    private void startNativeRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // RecognitionService가 전혀 없을 때만 시스템 Recognition Activity를 시도합니다.
            launchSystemRecognizerSafely();
            return;
        }
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Throwable ignored) {}
            speechRecognizer = null;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { recognizerReady=true; sendState("🎤 마이크 준비 완료 · 일본어로 발음해 주세요"); }
            @Override public void onBeginningOfSpeech() { sendState("🔊 실제 음성을 듣고 있습니다..."); }
            @Override public void onRmsChanged(float rmsdB) {
                float v = Math.max(0f, Math.min(100f, (rmsdB + 2f) * 4f));
                sendMeter(v);
            }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { sendState("음성 분석 중..."); }
            @Override public void onError(int error) {
                if (!listeningActive) return;
                // 네트워크/일시 오류 등은 앱을 죽이지 않고 시스템 인식 화면으로 한 번만 전환합니다.
                if (!systemVoiceLaunched && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                    launchSystemRecognizerSafely();
                } else {
                    listeningActive=false;
                    sendError("Android 음성인식 오류 코드: " + error + " · 다시 시작해 주세요.");
                }
            }
            @Override public void onResults(Bundle results) {
                if (!listeningActive) return;
                ArrayList<String> list = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) sendCandidates(list);
                else { listeningActive=false; sendError("음성인식 결과가 없습니다."); }
            }
            @Override public void onPartialResults(Bundle results) {
                if (!listeningActive || results == null) return;
                ArrayList<String> list=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) sendState("👂 인식 중: " + list.get(0));
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognizerReady=false;
        speechRecognizer.startListening(i);
        sendState("📱 음성인식 엔진에 연결하는 중...");
    }

    private void launchSystemRecognizerSafely() {
        if (!listeningActive) return;
        try {
            Intent i = makeSystemSpeechIntent();
            if (i.resolveActivity(getPackageManager()) == null) {
                listeningActive=false;
                sendError("휴대폰에 사용할 수 있는 시스템 음성인식 서비스가 없습니다.");
                return;
            }
            systemVoiceLaunched=true;
            sendState("📱 휴대폰 시스템 음성인식을 실행합니다...");
            startActivityForResult(i, SYSTEM_VOICE_REQUEST);
        } catch (Throwable e) {
            systemVoiceLaunched=false;
            listeningActive=false;
            sendError("시스템 음성인식을 실행하지 못했습니다: " + e.getClass().getSimpleName());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SYSTEM_VOICE_REQUEST) return;
        systemVoiceLaunched=false;
        if (!listeningActive) return;
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> results=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) { sendCandidates(results); return; }
        }
        listeningActive=false;
        sendError("음성인식 결과가 없습니다. 다시 [시작]을 눌러 주세요.");
    }

    private void stopListening() {
        listeningActive = false;
        systemVoiceLaunched = false;
        try { if (speechRecognizer != null) speechRecognizer.cancel(); } catch (Throwable ignored) {}
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
        try { if (speechRecognizer != null) speechRecognizer.destroy(); } catch (Throwable ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        try { if (webView != null) webView.destroy(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
