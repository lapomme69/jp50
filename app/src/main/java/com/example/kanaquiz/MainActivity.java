package com.example.kanaquiz;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingTts = null;
    private boolean shouldListen = false;
    private boolean destroyed = false;
    private static final int MIC_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);
        setupWebView();
        setupTts();
        setupRecognizer();
        requestMicIfNeeded();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // WebView 렌더러가 비정상 종료되어도 앱 전체가 같이 종료되지 않도록 합니다.
                return true;
            }
        });
        web.addJavascriptInterface(new AndroidBridge(), "AndroidVoice");
    }

    private void setupTts() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (destroyed || tts == null) return;
            if (status == TextToSpeech.SUCCESS) {
                int result;
                try {
                    result = tts.setLanguage(Locale.JAPAN);
                    tts.setSpeechRate(0.8f);
                    tts.setPitch(1.0f);
                } catch (Exception e) {
                    ttsReady = false;
                    return;
                }
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED;
                if (ttsReady && pendingTts != null) {
                    String text = pendingTts;
                    pendingTts = null;
                    speakJapanese(text);
                }
            }
        });
    }

    private void setupRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}

                @Override public void onResults(Bundle results) {
                    if (!shouldListen || destroyed) return;
                    ArrayList<String> list = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) sendResult(list.get(0));
                }

                @Override public void onError(int error) {
                    if (!shouldListen || destroyed) return;
                    // ERROR_NO_MATCH 등은 앱을 종료시키지 않고 안내만 합니다.
                    String message;
                    switch (error) {
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            message = "마이크 권한이 필요합니다. 휴대폰 설정에서 마이크를 허용해 주세요."; break;
                        case SpeechRecognizer.ERROR_AUDIO:
                            message = "마이크를 사용할 수 없습니다. 마이크 권한을 확인해 주세요."; break;
                        case SpeechRecognizer.ERROR_NETWORK:
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            message = "음성인식 네트워크 연결을 확인해 주세요."; break;
                        case SpeechRecognizer.ERROR_NO_MATCH:
                            message = "음성을 인식하지 못했습니다. 5초 안에 다시 말해 주세요."; break;
                        default:
                            message = "음성인식 오류입니다. [시작]을 다시 눌러 주세요."; break;
                    }
                    sendError(message);
                }
            });
        } catch (Throwable e) {
            recognizer = null;
        }
    }

    private void requestMicIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
        }
    }

    private void startListening() {
        if (destroyed) return;
        shouldListen = true;

        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            requestMicIfNeeded();
            sendError("마이크 권한을 허용해 주세요.");
            return;
        }

        if (recognizer == null) {
            sendError("이 휴대폰에서 Android 음성인식을 사용할 수 없습니다.");
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "일본어 발음을 말해 주세요");

        try {
            recognizer.cancel();
            recognizer.startListening(intent);
        } catch (Throwable e) {
            sendError("음성인식을 시작할 수 없습니다. 휴대폰의 마이크 권한을 확인해 주세요.");
        }
    }

    private void stopListening() {
        shouldListen = false;
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Throwable ignored) {}
            try { recognizer.cancel(); } catch (Throwable ignored) {}
        }
    }

    private void sendResult(String text) {
        if (web == null || destroyed) return;
        web.post(() -> {
            if (!destroyed && web != null) {
                web.evaluateJavascript("window.onNativeSpeechResult(" + quote(text) + ")", null);
            }
        });
    }

    private void sendError(String message) {
        if (web == null || destroyed) return;
        web.post(() -> {
            if (!destroyed && web != null) {
                web.evaluateJavascript("window.onNativeSpeechError(" + quote(message) + ")", null);
            }
        });
    }

    private String quote(String text) {
        if (text == null) text = "";
        return "\"" + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private void speakJapanese(String text) {
        if (destroyed || text == null || text.isEmpty()) return;
        if (tts == null || !ttsReady) {
            pendingTts = text;
            sendError("일본어 음성 데이터를 준비하고 있습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }
        try {
            tts.stop();
            int result = tts.setLanguage(Locale.JAPAN);
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsReady = false;
                sendError("휴대폰에 일본어 TTS 음성이 없습니다. Google 음성 서비스에서 일본어 음성을 설치해 주세요.");
                return;
            }
            tts.setSpeechRate(0.8f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "japanese-kana");
        } catch (Throwable e) {
            sendError("일본어 발음을 재생할 수 없습니다. 휴대폰 음성 설정을 확인해 주세요.");
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void startListening() {
            runOnUiThread(() -> startListening());
        }
        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(() -> stopListening());
        }
        @JavascriptInterface
        public void speak(String text) {
            runOnUiThread(() -> speakJapanese(text));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_REQUEST && shouldListen && !destroyed) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                sendError("마이크 권한이 거부되었습니다. 휴대폰 설정에서 마이크 권한을 허용해 주세요.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        shouldListen = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        if (tts != null) {
            try { tts.stop(); } catch (Throwable ignored) {}
            try { tts.shutdown(); } catch (Throwable ignored) {}
            tts = null;
        }
        if (web != null) {
            try { web.removeJavascriptInterface("AndroidVoice"); } catch (Throwable ignored) {}
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
