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
    private static final int MIC_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new AndroidBridge(), "AndroidVoice");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.JAPAN);
                tts.setSpeechRate(0.8f);
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED;

                if (ttsReady && pendingTts != null) {
                    String text = pendingTts;
                    pendingTts = null;
                    speakJapanese(text);
                }
            }
        });

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> list =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) {
                        sendResult(list.get(0));
                    }
                }

                @Override
                public void onError(int error) {
                    if (!shouldListen) return;

                    String message;
                    switch (error) {
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            message = "마이크 권한이 필요합니다. 휴대폰 설정에서 허용해 주세요.";
                            break;
                        case SpeechRecognizer.ERROR_AUDIO:
                            message = "마이크를 사용할 수 없습니다. 마이크 권한과 음량을 확인해 주세요.";
                            break;
                        case SpeechRecognizer.ERROR_NETWORK:
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            message = "음성인식 네트워크 연결을 확인해 주세요.";
                            break;
                        case SpeechRecognizer.ERROR_NO_MATCH:
                            message = "음성을 인식하지 못했습니다. 다시 말해 주세요.";
                            break;
                        default:
                            message = "음성인식 오류가 발생했습니다. 다시 시작해 주세요.";
                            break;
                    }
                    sendError(message);
                }
            });
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
        }

        web.loadUrl("file:///android_asset/index.html");
    }

    private void startListening() {
        shouldListen = true;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }

        if (recognizer == null) {
            sendError("이 휴대폰에서 Android 음성인식을 사용할 수 없습니다.");
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        // 사용자가 한국어로 '아/이/우/카/키...'라고 말하므로 한국어 인식 사용
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            sendError("음성인식을 시작할 수 없습니다.");
        }
    }

    private void stopListening() {
        shouldListen = false;
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) {}
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
    }

    private void sendResult(String text) {
        if (web == null) return;
        web.post(() -> web.evaluateJavascript(
                "window.onNativeSpeechResult(" + quote(text) + ")", null));
    }

    private void sendError(String message) {
        if (web == null) return;
        web.post(() -> web.evaluateJavascript(
                "window.onNativeSpeechError(" + quote(message) + ")", null));
    }

    private String quote(String text) {
        if (text == null) text = "";
        return "\"" + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private void speakJapanese(String text) {
        if (text == null || text.isEmpty()) return;

        if (!ttsReady || tts == null) {
            pendingTts = text;
            return;
        }

        tts.stop();
        tts.setLanguage(Locale.JAPAN);
        tts.setSpeechRate(0.8f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "japanese-kana");
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
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == MIC_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && shouldListen) {
            startListening();
        }
    }

    @Override
    protected void onDestroy() {
        shouldListen = false;

        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }

        super.onDestroy();
    }
}
