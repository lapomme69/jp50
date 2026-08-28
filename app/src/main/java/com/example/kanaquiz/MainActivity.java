package com.example.kanaquiz;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
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

    private WebView webView;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listeningActive = false;
    private boolean waitingFinalResult = false;
    private boolean systemVoiceLaunched = false;
    private boolean gotNativeCallback = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable watchdog;

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

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MIC_REQUEST) {
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
                if (!ttsReady) sendState("일본어 음성 데이터가 없습니다. 휴대폰 TTS에서 일본어 음성을 설치해 주세요.");
            }
        });
    }

    private boolean canUseRecognizer() {
        return SpeechRecognizer.isRecognitionAvailable(this);
    }

    private Intent makeSpeechIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        return i;
    }

    private void createRecognizer() {
        destroyRecognizer();
        if (!canUseRecognizer()) {
            sendState("이 기기의 기본 음성인식 서비스를 사용할 수 없습니다.");
            return;
        }
        try {
            // 특정 Google/Samsung 서비스를 강제로 지정하지 않습니다.
            // Android가 현재 기기에 맞는 기본 RecognitionService를 선택하게 합니다.
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        } catch (Throwable e) {
            recognizer = null;
            sendError("Android 음성인식기를 만들 수 없습니다: " + e.getClass().getSimpleName());
            return;
        }

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) {
                gotNativeCallback = true;
                sendState("🎤 실제 Android 음성인식 준비 완료 · 일본어로 발음해 주세요.");
                sendMeter(8);
            }

            @Override public void onBeginningOfSpeech() {
                gotNativeCallback = true;
                sendState("🎤 실제 말소리 감지됨 · 듣고 있습니다.");
                sendMeter(35);
            }

            @Override public void onRmsChanged(float rms) {
                gotNativeCallback = true;
                float v = Math.max(0, Math.min(100, (rms + 2f) * 8f));
                sendMeter(v);
            }

            @Override public void onBufferReceived(byte[] b) {}

            @Override public void onEndOfSpeech() {
                gotNativeCallback = true;
                sendMeter(3);
                sendState("말소리 종료 · 최종 결과를 확인하고 있습니다.");
            }

            @Override public void onPartialResults(Bundle b) {
                gotNativeCallback = true;
                ArrayList<String> a = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (a != null && !a.isEmpty()) sendPartial(a.get(0));
            }

            @Override public void onResults(Bundle b) {
                gotNativeCallback = true;
                startingFinished();
                ArrayList<String> a = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (a != null && !a.isEmpty()) {
                    sendCandidates(a);
                } else if (listeningActive && !waitingFinalResult && !systemVoiceLaunched) {
                    launchSystemVoice();
                }
            }

            @Override public void onError(int e) {
                startingFinished();
                if (!listeningActive || waitingFinalResult || systemVoiceLaunched) return;

                String m;
                switch (e) {
                    case SpeechRecognizer.ERROR_AUDIO:
                        m = "마이크 오디오 오류입니다. 다른 앱이 마이크를 사용 중인지 확인해 주세요."; break;
                    case SpeechRecognizer.ERROR_CLIENT:
                        m = "Android 음성인식 연결 오류입니다. 시스템 음성인식으로 전환합니다."; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        m = "마이크 권한이 없습니다."; break;
                    case SpeechRecognizer.ERROR_NETWORK:
                        m = "네트워크 오류입니다. 시스템 음성인식으로 전환합니다."; break;
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        m = "네트워크 시간초과입니다. 시스템 음성인식으로 전환합니다."; break;
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        m = "음성을 인식하지 못했습니다. 다시 말해 주세요."; break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        m = "말소리가 감지되지 않았습니다. 다시 말해 주세요."; break;
                    default:
                        m = "Android 음성인식 오류 코드: " + e; break;
                }
                sendError(m);

                if (e == SpeechRecognizer.ERROR_CLIENT || e == SpeechRecognizer.ERROR_NETWORK ||
                        e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    main.postDelayed(() -> launchSystemVoice(), 250);
                }
            }

            @Override public void onEvent(int i, Bundle b) {}
        });
    }

    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendError("먼저 마이크 권한을 허용해 주세요.");
            requestMicIfNeeded();
            return;
        }

        listeningActive = true;
        waitingFinalResult = false;
        systemVoiceLaunched = false;
        gotNativeCallback = false;
        sendState("Android 기본 음성인식에 연결하고 있습니다...");

        if (!canUseRecognizer()) {
            sendState("기본 Android 음성인식 서비스를 찾지 못했습니다. 시스템 음성인식을 엽니다.");
            launchSystemVoice();
            return;
        }

        createRecognizer();
        if (recognizer == null) {
            launchSystemVoice();
            return;
        }

        try {
            recognizer.startListening(makeSpeechIntent());
        } catch (Throwable e) {
            sendError("Android 음성인식을 시작할 수 없습니다. 시스템 음성인식으로 전환합니다.");
            launchSystemVoice();
            return;
        }

        // V23의 서비스 순환 방식 대신, 기본 서비스가 실제 콜백을 주는지 충분히 기다립니다.
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = () -> {
            if (listeningActive && !waitingFinalResult && !systemVoiceLaunched && !gotNativeCallback) {
                sendState("기본 엔진 응답 없음 · 휴대폰 시스템 음성인식으로 전환합니다.");
                launchSystemVoice();
            }
        };
        main.postDelayed(watchdog, 2800);
    }

    private void launchSystemVoice() {
        if (!listeningActive || waitingFinalResult || systemVoiceLaunched) return;
        systemVoiceLaunched = true;
        if (watchdog != null) main.removeCallbacks(watchdog);
        try {
            Intent i = makeSpeechIntent();
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "일본어 발음을 말해 주세요");
            startActivityForResult(i, SYSTEM_VOICE_REQUEST);
            sendState("📱 휴대폰 시스템 음성인식 화면에서 일본어를 말해 주세요.");
        } catch (Throwable e) {
            systemVoiceLaunched = false;
            sendError("휴대폰 시스템 음성인식을 실행할 수 없습니다. Google 앱의 음성인식 서비스를 확인해 주세요.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SYSTEM_VOICE_REQUEST) return;
        if (!listeningActive) return;

        systemVoiceLaunched = false;
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                sendCandidates(results);
                return;
            }
        }

        if (!waitingFinalResult) {
            sendError("시스템 음성인식 결과가 없습니다. 다시 시도해 주세요.");
        }
    }

    private void startingFinished() {
        if (watchdog != null) main.removeCallbacks(watchdog);
    }

    private void stopListeningForResult() {
        waitingFinalResult = true;
        if (watchdog != null) main.removeCallbacks(watchdog);
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Throwable ignored) {}
        }
    }

    private void stopListening() {
        listeningActive = false;
        waitingFinalResult = false;
        systemVoiceLaunched = false;
        if (watchdog != null) main.removeCallbacks(watchdog);
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Throwable ignored) {}
            try { recognizer.cancel(); } catch (Throwable ignored) {}
        }
    }

    private void destroyRecognizer() {
        try { if (recognizer != null) recognizer.destroy(); } catch (Throwable ignored) {}
        recognizer = null;
    }

    private void speakJapanese(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ttsReady) {
            main.postDelayed(() -> speakJapanese(text), 400);
            return;
        }
        try {
            tts.stop();
            tts.setLanguage(Locale.JAPAN);
            tts.setSpeechRate(.8f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kana-v24");
        } catch (Throwable ignored) {}
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

    private void sendPartial(String s) { runJs("if(window.onNativeSpeechPartial)window.onNativeSpeechPartial(" + quote(s) + ")"); }
    private void sendState(String s) { runJs("if(window.onNativeSpeechState)window.onNativeSpeechState(" + quote(s) + ")"); }
    private void sendError(String s) { runJs("if(window.onNativeSpeechError)window.onNativeSpeechError(" + quote(s) + ")"); }
    private void sendMeter(float v) { runJs("if(window.onNativeMicLevel)window.onNativeMicLevel(" + v + ")"); }

    private void runJs(String code) {
        main.post(() -> {
            try { if (webView != null) webView.evaluateJavascript(code, null); } catch (Throwable ignored) {}
        });
    }

    public class Bridge {
        @JavascriptInterface public void startListening() { main.post(() -> startListening()); }
        @JavascriptInterface public void stopListening() { main.post(() -> stopListening()); }
        @JavascriptInterface public void stopListeningForResult() { main.post(() -> stopListeningForResult()); }
        @JavascriptInterface public void speak(String text) { main.post(() -> speakJapanese(text)); }
        @JavascriptInterface public boolean isSystemVoiceActive() { return systemVoiceLaunched; }
    }

    @Override protected void onDestroy() {
        if (watchdog != null) main.removeCallbacks(watchdog);
        destroyRecognizer();
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        try { if (webView != null) webView.destroy(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
