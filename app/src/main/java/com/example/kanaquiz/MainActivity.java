package com.example.kanaquiz;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private WebView webView;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady=false;
    private static final int MIC_REQUEST=1801;
    private final Handler main=new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        setupWebView();
        setupTts();
        setupRecognizer();
        if(android.os.Build.VERSION.SDK_INT>=23 &&
           checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);
        }
    }

    private void setupWebView(){
        webView=new WebView(this); setContentView(webView);
        WebSettings w=webView.getSettings();
        w.setJavaScriptEnabled(true); w.setDomStorageEnabled(true);
        w.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(),"AndroidVoice");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setupTts(){
        tts=new TextToSpeech(this,status->{
            try{
                if(status==TextToSpeech.SUCCESS){
                    int r=tts.setLanguage(Locale.JAPAN);
                    tts.setSpeechRate(.8f);
                    ttsReady=r!=TextToSpeech.LANG_MISSING_DATA &&
                             r!=TextToSpeech.LANG_NOT_SUPPORTED;
                }
            }catch(Throwable ignored){}
        });
    }

    private void setupRecognizer(){
        if(!SpeechRecognizer.isRecognitionAvailable(this))return;
        recognizer=SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener(){
            @Override public void onReadyForSpeech(Bundle b){sendMeter(12);}
            @Override public void onBeginningOfSpeech(){sendMeter(40);}
            @Override public void onRmsChanged(float rms){
                // Real microphone level from Android SpeechRecognizer.
                float v=Math.max(0,Math.min(100,(rms+2f)*8f));
                sendMeter(v);
            }
            @Override public void onBufferReceived(byte[] b){}
            @Override public void onEndOfSpeech(){sendMeter(5);}
            @Override public void onPartialResults(Bundle b){}
            @Override public void onEvent(int i,Bundle b){}
            @Override public void onResults(Bundle b){
                ArrayList<String>a=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(a!=null&&!a.isEmpty())sendResult(a.get(0));
                else sendError("인식 결과가 없습니다.");
            }
            @Override public void onError(int e){
                String m;
                if(e==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)m="마이크 권한이 필요합니다.";
                else if(e==SpeechRecognizer.ERROR_NO_MATCH)m="음성을 들었지만 글자로 인식하지 못했습니다.";
                else if(e==SpeechRecognizer.ERROR_SPEECH_TIMEOUT)m="말소리가 감지되지 않았습니다.";
                else if(e==SpeechRecognizer.ERROR_NETWORK)m="음성인식 네트워크 오류입니다.";
                else m="음성인식 오류 코드: "+e;
                sendError(m);
            }
        });
    }

    private void startListening(){
        try{
            if(android.os.Build.VERSION.SDK_INT>=23 &&
               checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST); return;
            }
            if(recognizer==null){sendError("이 휴대폰에서 Android 음성인식을 사용할 수 없습니다.");return;}
            recognizer.cancel();
            Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"ko-KR");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
            recognizer.startListening(i);
        }catch(Throwable e){sendError("마이크를 시작할 수 없습니다.");}
    }

    private void stopListening(){
        try{if(recognizer!=null)recognizer.stopListening();}catch(Throwable ignored){}
        try{if(recognizer!=null)recognizer.cancel();}catch(Throwable ignored){}
    }

    private void speakJapanese(String text){
        if(text==null||text.isEmpty())return;
        if(!ttsReady){main.postDelayed(()->speakJapanese(text),500);return;}
        try{
            tts.stop(); tts.setLanguage(Locale.JAPAN); tts.setSpeechRate(.8f);
            tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"kana-v18");
        }catch(Throwable ignored){}
    }

    private String q(String s){
        if(s==null)s="";
        return "\""+s.replace("\\","\\\\").replace("\"","\\\"")
          .replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    private void sendResult(String s){main.post(()->{try{
        webView.evaluateJavascript("window.onNativeSpeechResult("+q(s)+")",null);
    }catch(Throwable ignored){}});}
    private void sendError(String s){main.post(()->{try{
        webView.evaluateJavascript("window.onNativeSpeechError("+q(s)+")",null);
    }catch(Throwable ignored){}});}
    private void sendMeter(float value){main.post(()->{try{
        webView.evaluateJavascript("window.onNativeMicLevel("+value+")",null);
    }catch(Throwable ignored){}});}

    public class Bridge{
        @JavascriptInterface public void startListening(){main.post(()->startListening());}
        @JavascriptInterface public void stopListening(){main.post(()->stopListening());}
        @JavascriptInterface public void speak(String text){main.post(()->speakJapanese(text));}
    }

    @Override protected void onDestroy(){
        try{if(recognizer!=null)recognizer.destroy();}catch(Throwable ignored){}
        try{if(tts!=null){tts.stop();tts.shutdown();}}catch(Throwable ignored){}
        try{if(webView!=null)webView.destroy();}catch(Throwable ignored){}
        super.onDestroy();
    }
}
