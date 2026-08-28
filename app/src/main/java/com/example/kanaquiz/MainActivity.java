package com.example.kanaquiz;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listeningActive = false;
    private boolean waitingFinalResult = false;
    private boolean recognizerReady = false;
    private boolean starting = false;
    private int serviceIndex = -1;
    private List<ComponentName> recognitionServices = new ArrayList<>();

    private static final int MIC_REQUEST = 1901;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable startWatchdog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setupWebView();
        setupTts();
        setupRecognitionServices();
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
                sendState("마이크 권한이 허용되었습니다. [시작]을 눌러 주세요.");
            } else {
                sendError("마이크 권한이 거부되었습니다. 휴대폰 설정 → 앱 → 일본어 50음도 → 권한 → 마이크를 허용해 주세요.");
            }
        }
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.JAPAN);
                tts.setSpeechRate(.8f);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!ttsReady) sendState("일본어 음성 데이터가 없습니다. TTS 설정에서 일본어 음성을 설치해 주세요.");
            }
        });
    }

    /**
     * 기기에 설치된 실제 RecognitionService를 조사합니다.
     * 기본 서비스가 응답하지 않는 기기에서는 Google/Samsung 등의 사용 가능한 서비스를
     * 순서대로 재시도하여 '엔진 연결 중' 무한 대기를 막습니다.
     */
    private void setupRecognitionServices() {
        recognitionServices.clear();
        Intent query = new Intent(RecognitionServiceAction());
        PackageManager pm = getPackageManager();
        List<ResolveInfo> list = pm.queryIntentServices(query, PackageManager.MATCH_ALL);

        for (ResolveInfo ri : list) {
            if (ri.serviceInfo == null) continue;
            ComponentName c = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            if (!containsService(c)) recognitionServices.add(c);
        }

        // Google/Samsung 서비스를 앞쪽으로 올리되, 존재하는 경우에만 사용합니다.
        for (int i = 0; i < recognitionServices.size(); i++) {
            String p = recognitionServices.get(i).getPackageName().toLowerCase(Locale.ROOT);
            if (p.contains("googlequicksearchbox") || p.contains("samsung")) {
                ComponentName c = recognitionServices.remove(i);
                recognitionServices.add(0, c);
                break;
            }
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this) || recognitionServices.isEmpty()) {
            sendError("이 휴대폰에서 사용할 수 있는 Android 음성인식 서비스가 없습니다. Google 앱/음성인식 서비스를 확인해 주세요.");
        }
    }

    private String RecognitionServiceAction() {
        return "android.speech.RecognitionService";
    }

    private boolean containsService(ComponentName c) {
        for (ComponentName x : recognitionServices) if (x.equals(c)) return true;
        return false;
    }

    private void createRecognizer(ComponentName service) {
        destroyRecognizer();
        try {
            if (service != null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this, service);
            } else {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
        } catch (Throwable e) {
            recognizer = null;
            sendError("Android 음성인식 엔진을 만들 수 없습니다: " + e.getClass().getSimpleName());
            return;
        }

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) {
                starting = false;
                recognizerReady = true;
                sendMeter(12);
                sendState("Android 음성인식 준비 완료 · 일본어로 발음해 주세요");
            }
            @Override public void onBeginningOfSpeech() {
                starting = false;
                recognizerReady = true;
                sendMeter(55);
                sendState("말소리 감지됨 · 계속 듣는 중");
            }
            @Override public void onRmsChanged(float rms) {
                // 일부 삼성/Google 조합에서는 onReadyForSpeech보다 RMS가 먼저 옵니다.
                starting = false;
                recognizerReady = true;
                float v = Math.max(0, Math.min(100, (rms + 2f) * 8f));
                sendMeter(v);
                if (v >= 8 && listeningActive && !waitingFinalResult)
                    sendState("실제 마이크 입력 감지 중 · 일본어를 말해 주세요");
            }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {
                sendMeter(5);
                sendState("말소리 종료 · 최종 결과 확인 중");
            }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> a = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (a != null && !a.isEmpty()) sendPartial(a.get(0));
            }
            @Override public void onResults(Bundle b) {
                starting = false;
                ArrayList<String> a = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (a != null && !a.isEmpty()) sendCandidates(a);
                else if (listeningActive && !waitingFinalResult) restartListeningSoon();
            }
            @Override public void onError(int e) {
                starting = false;
                if (!listeningActive) return;
                if (waitingFinalResult) {
                    sendError("최종 음성 결과를 받지 못했습니다. 다시 시도해 주세요. (오류 " + e + ")");
                    return;
                }
                String m;
                switch (e) {
                    case SpeechRecognizer.ERROR_AUDIO: m = "마이크 오디오 오류입니다. 다른 앱이 마이크를 사용 중인지 확인해 주세요."; break;
                    case SpeechRecognizer.ERROR_CLIENT: m = "음성인식 서비스 연결 오류입니다. 다른 음성인식 엔진으로 다시 연결합니다."; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: m = "마이크 권한이 없습니다."; break;
                    case SpeechRecognizer.ERROR_NETWORK: m = "음성인식 네트워크 오류입니다. 인터넷 연결을 확인해 주세요."; break;
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: m = "음성인식 네트워크 시간초과입니다."; break;
                    case SpeechRecognizer.ERROR_NO_MATCH: m = "음성을 인식하지 못했습니다. 다시 듣습니다."; break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: m = "말소리가 감지되지 않았습니다. 다시 듣습니다."; break;
                    default: m = "Android 음성인식 오류 코드: " + e; break;
                }
                sendError(m);
                if (e == SpeechRecognizer.ERROR_CLIENT || e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    tryNextServiceOrRestart();
                }
            }
            @Override public void onEvent(int i, Bundle b) {}
        });
    }

    private Intent makeSpeechIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        return i;
    }

    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendError("먼저 마이크 권한을 허용해 주세요.");
            requestMicIfNeeded();
            return;
        }
        if (recognitionServices.isEmpty()) setupRecognitionServices();
        if (recognitionServices.isEmpty()) {
            sendError("사용 가능한 Android 음성인식 엔진이 없습니다.");
            return;
        }

        listeningActive = true;
        waitingFinalResult = false;
        recognizerReady = false;
        starting = true;
        serviceIndex = -1;
        sendState("Android 기본 음성인식 엔진 연결 중...");
        createRecognizer(null);
        launchRecognizer("Android 기본 음성인식 엔진 연결 중...");
    }

    private void startWithService(int index) {
        if (!listeningActive) return;
        if (index >= recognitionServices.size()) {
            // 마지막으로 Android 기본 생성 방식도 시도합니다.
            serviceIndex = recognitionServices.size();
            createRecognizer(null);
            launchRecognizer("기본 Android 음성인식 엔진으로 다시 연결 중...");
            return;
        }
        serviceIndex = index;
        ComponentName service = recognitionServices.get(index);
        createRecognizer(service);
        String pkg = service.getPackageName();
        sendState("음성인식 엔진 연결 중... " + pkg);
        launchRecognizer(null);
    }

    private void launchRecognizer(String extraState) {
        if (recognizer == null) {
            tryNextServiceOrRestart();
            return;
        }
        if (extraState != null) sendState(extraState);
        try {
            recognizer.cancel();
            recognizer.startListening(makeSpeechIntent());
        } catch (Throwable e) {
            sendError("마이크를 시작할 수 없습니다. 다른 음성인식 엔진으로 다시 시도합니다.");
            tryNextServiceOrRestart();
            return;
        }

        if (startWatchdog != null) main.removeCallbacks(startWatchdog);
        startWatchdog = () -> {
            if (listeningActive && starting && !recognizerReady && !waitingFinalResult) {
                sendError("음성인식 엔진 응답 없음 · 다른 Android 음성인식 엔진으로 전환합니다.");
                tryNextServiceOrRestart();
            }
        };
        // 1.8초 안에 ready/RMS가 오지 않으면 다음 서비스로 전환합니다.
        main.postDelayed(startWatchdog, 1800);
    }

    private void tryNextServiceOrRestart() {
        if (!listeningActive || waitingFinalResult) return;
        int next = serviceIndex + 1;
        if (next < recognitionServices.size()) {
            main.postDelayed(() -> startWithService(next), 250);
        } else {
            main.postDelayed(() -> {
                if (!listeningActive || waitingFinalResult) return;
                // 모든 후보를 한 번씩 시도한 후 첫 후보부터 다시 시작합니다.
                if (!recognitionServices.isEmpty()) startWithService(0);
                else restartListeningSoon();
            }, 500);
        }
    }

    private void restartListeningSoon() {
        if (!listeningActive || waitingFinalResult) return;
        main.postDelayed(() -> {
            if (!listeningActive || waitingFinalResult) return;
            try {
                if (recognizer == null) startWithService(Math.max(0, serviceIndex));
                else launchRecognizer(null);
            } catch (Throwable e) {
                tryNextServiceOrRestart();
            }
        }, 300);
    }

    private void stopListeningForResult() {
        if (recognizer == null) return;
        waitingFinalResult = true;
        starting = false;
        if (startWatchdog != null) main.removeCallbacks(startWatchdog);
        try { recognizer.stopListening(); } catch (Throwable ignored) {}
    }

    private void stopListening() {
        listeningActive = false;
        waitingFinalResult = false;
        starting = false;
        if (startWatchdog != null) main.removeCallbacks(startWatchdog);
        try { if (recognizer != null) recognizer.stopListening(); } catch (Throwable ignored) {}
        try { if (recognizer != null) recognizer.cancel(); } catch (Throwable ignored) {}
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
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kana-v23");
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
        main.post(() -> { try { if (webView != null) webView.evaluateJavascript(code, null); } catch (Throwable ignored) {} });
    }

    public class Bridge {
        @JavascriptInterface public void startListening() { main.post(() -> startListening()); }
        @JavascriptInterface public void stopListening() { main.post(() -> stopListening()); }
        @JavascriptInterface public void stopListeningForResult() { main.post(() -> stopListeningForResult()); }
        @JavascriptInterface public void speak(String text) { main.post(() -> speakJapanese(text)); }
    }

    @Override protected void onDestroy() {
        if (startWatchdog != null) main.removeCallbacks(startWatchdog);
        destroyRecognizer();
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        try { if (webView != null) webView.destroy(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
