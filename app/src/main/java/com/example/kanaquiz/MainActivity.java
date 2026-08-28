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
    private boolean waitingForPermission=false;
    private boolean listeningActive=false;
    private static final int MIC_REQUEST=1901;
    private final Handler main=new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        setupWebView();
        setupTts();
        setupRecognizer();
        requestMicIfNeeded();
    }

    private void setupWebView(){
        webView=new WebView(this);
        setContentView(webView);
        WebSettings w=webView.getSettings();
        w.setJavaScriptEnabled(true);
        w.setDomStorageEnabled(true);
        w.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(),"AndroidVoice");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestMicIfNeeded(){
        if(android.os.Build.VERSION.SDK_INT>=23 &&
          checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            waitingForPermission=true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==MIC_REQUEST){
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED){
                sendError("마이크 권한이 허용되었습니다. [시작]을 눌러 주세요.");
            }else{
                sendError("마이크 권한이 거부되었습니다. 휴대폰 설정 → 앱 → 일본어 50음도 → 권한 → 마이크를 허용해 주세요.");
            }
            waitingForPermission=false;
        }
    }

    private void setupTts(){
        tts=new TextToSpeech(this,status->{
            if(status==TextToSpeech.SUCCESS){
                int r=tts.setLanguage(Locale.JAPAN);
                tts.setSpeechRate(.8f);
                ttsReady=(r!=TextToSpeech.LANG_MISSING_DATA && r!=TextToSpeech.LANG_NOT_SUPPORTED);
            }
        });
    }

    private void setupRecognizer(){
        if(!SpeechRecognizer.isRecognitionAvailable(this)){
            return;
        }
        recognizer=SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener(){
            @Override public void onReadyForSpeech(Bundle b){
                sendMeter(12); sendState("Android 음성인식 준비 완료");
            }

            @Override public void onBeginningOfSpeech(){
                sendMeter(55); sendState("말소리 감지됨");
            }

            @Override public void onRmsChanged(float rms){
                // Android가 실제 마이크에서 받은 음량값을 UI로 전달합니다.
                float v=Math.max(0,Math.min(100,(rms+2f)*9f));
                sendMeter(v);
            }

            @Override public void onBufferReceived(byte[] b){}
            @Override public void onEndOfSpeech(){sendMeter(8); sendState("말소리 종료 · 결과 기다리는 중");}
            @Override public void onEvent(int i,Bundle b){}

            @Override public void onPartialResults(Bundle b){
                ArrayList<String>a=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(a!=null&&!a.isEmpty()){
                    // 짧은 한 음절은 최종 결과를 기다리면 놓칠 수 있으므로
                    // 부분 인식 결과가 정답이면 즉시 전달합니다.
                    sendPartial(a.get(0));
                }
            }

            @Override public void onResults(Bundle b){
                ArrayList<String>a=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(a!=null&&!a.isEmpty()){
                    sendCandidates(a);
                }else if(listeningActive){
                    restartListeningSoon();
                }
            }

            @Override public void onError(int e){
                if(!listeningActive) return;

                // 5초 동안 계속 듣도록, 일시적인 NO_MATCH / TIMEOUT / CLIENT
                // 오류가 발생해도 짧게 쉬었다가 다시 듣습니다.
                if(e==SpeechRecognizer.ERROR_NO_MATCH || e==SpeechRecognizer.ERROR_SPEECH_TIMEOUT){
                    sendState("인식 결과 없음 (다시 듣는 중)");
                    restartListeningSoon();
                    return;
                }
                if(e==SpeechRecognizer.ERROR_CLIENT){
                    sendError("Android 음성인식 서비스가 중단되었습니다. 오류코드: 5");
                    restartListeningSoon();
                    return;
                }

                String m;
                if(e==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    m="마이크 권한이 필요합니다.";
                else if(e==SpeechRecognizer.ERROR_NETWORK)
                    m="음성인식 네트워크 오류입니다.";
                else
                    m="음성인식 오류 코드: "+e;

                sendError(m);
            }
        });
    }

    private void restartListeningSoon(){
        if(!listeningActive || recognizer==null) return;
        main.postDelayed(()->{
            if(!listeningActive || recognizer==null) return;
            try{
                recognizer.cancel();
                Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR");
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"ko-KR");
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
                recognizer.startListening(i);
            }catch(Throwable ignored){}
        },120);
    }

    private void startListening(){
        if(android.os.Build.VERSION.SDK_INT>=23 &&
          checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            sendError("먼저 마이크 권한을 허용해 주세요.");
            requestMicIfNeeded();
            return;
        }
        if(recognizer==null){
            sendError("이 휴대폰의 Android 음성인식 서비스를 사용할 수 없습니다.");
            return;
        }

        listeningActive=true;
        try{
            recognizer.cancel();
            Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"ko-KR");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
            recognizer.startListening(i);
        }catch(Throwable e){
            sendError("마이크를 시작할 수 없습니다.");
        }
    }

    private void stopListening(){
        listeningActive=false;
        try{if(recognizer!=null)recognizer.stopListening();}catch(Throwable ignored){}
        try{if(recognizer!=null)recognizer.cancel();}catch(Throwable ignored){}
    }

    private void speakJapanese(String text){
        if(text==null||text.isEmpty())return;
        if(!ttsReady){
            main.postDelayed(()->speakJapanese(text),500);
            return;
        }
        try{
            tts.stop();
            tts.setLanguage(Locale.JAPAN);
            tts.setSpeechRate(.8f);
            tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"kana-v20");
        }catch(Throwable ignored){}
    }

    private String quote(String s){
        if(s==null)s="";
        return "\""+s.replace("\\","\\\\").replace("\"","\\\"")
          .replace("\n","\\n").replace("\r","\\r")+"\"";
    }

    private void sendCandidates(ArrayList<String> list){
        StringBuilder js=new StringBuilder("window.onNativeSpeechCandidates([");
        for(int n=0;n<list.size();n++){
            if(n>0) js.append(',');
            js.append(quote(list.get(n)));
        }
        js.append("])" );
        String code=js.toString();
        main.post(()->{try{webView.evaluateJavascript(code,null);}catch(Throwable ignored){}});
    }

    private void sendResult(String s){
        main.post(()->{try{webView.evaluateJavascript("window.onNativeSpeechResult("+quote(s)+")",null);}catch(Throwable ignored){}});
    }
    private void sendPartial(String s){
        main.post(()->{try{webView.evaluateJavascript("if(window.onNativeSpeechPartial)window.onNativeSpeechPartial("+quote(s)+")",null);}catch(Throwable ignored){}});
    }
    private void sendState(String s){
        main.post(()->{try{webView.evaluateJavascript("if(window.onNativeSpeechState)window.onNativeSpeechState("+quote(s)+")",null);}catch(Throwable ignored){}});
    }
    private void sendError(String s){
        main.post(()->{try{webView.evaluateJavascript("window.onNativeSpeechError("+quote(s)+")",null);}catch(Throwable ignored){}});
    }
    private void sendMeter(float v){
        main.post(()->{try{webView.evaluateJavascript("window.onNativeMicLevel("+v+")",null);}catch(Throwable ignored){}});
    }

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
