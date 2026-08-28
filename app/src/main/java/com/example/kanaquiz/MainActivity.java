package com.example.kanaquiz;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.ComponentName;
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
    private boolean waitingFinalResult=false;
    private ComponentName recognitionService=null;
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
            sendError("이 휴대폰에서 사용할 수 있는 Android 음성인식 서비스가 없습니다. Google 앱의 음성검색 기능을 확인해 주세요.");
            return;
        }
        // Android 11+에서는 사용 가능한 RecognitionService를 직접 찾습니다.
        // 가능하면 Google 음성인식 서비스를 우선 사용하여 삼성 기기에서
        // 기본 음성엔진이 멈춰 있는 경우를 피합니다.
        Intent query=new Intent("android.speech.RecognitionService");
        try{
            java.util.List<ResolveInfo> services=getPackageManager().queryIntentServices(query, PackageManager.MATCH_ALL);
            for(ResolveInfo ri:services){
                if(ri.serviceInfo==null) continue;
                String pkg=ri.serviceInfo.packageName;
                if(pkg.contains("googlequicksearchbox") || pkg.contains("google")){
                    recognitionService=new ComponentName(pkg,ri.serviceInfo.name);
                    break;
                }
            }
            if(recognitionService==null && !services.isEmpty()){
                ResolveInfo ri=services.get(0);
                recognitionService=new ComponentName(ri.serviceInfo.packageName,ri.serviceInfo.name);
            }
        }catch(Throwable ignored){}
        createRecognizer();
    }

    private void createRecognizer(){
        try{
            if(recognizer!=null) recognizer.destroy();
        }catch(Throwable ignored){}
        try{
            if(recognitionService!=null)
                recognizer=SpeechRecognizer.createSpeechRecognizer(this, recognitionService);
            else
                recognizer=SpeechRecognizer.createSpeechRecognizer(this);
        }catch(Throwable e){
            recognizer=null;
            sendError("Android 음성인식 엔진을 만들 수 없습니다: "+e.getClass().getSimpleName());
            return;
        }
        recognizer.setRecognitionListener(new RecognitionListener(){
            @Override public void onReadyForSpeech(Bundle b){
                sendMeter(12); sendState("Android 음성인식 준비 완료");
            }
            @Override public void onBeginningOfSpeech(){ sendMeter(55); sendState("말소리 감지됨"); }
            @Override public void onRmsChanged(float rms){
                float v=Math.max(0,Math.min(100,(rms+2f)*9f)); sendMeter(v);
            }
            @Override public void onBufferReceived(byte[] b){}
            @Override public void onEndOfSpeech(){ sendMeter(8); sendState("말소리 종료 · 결과 기다리는 중"); }
            @Override public void onEvent(int i,Bundle b){}
            @Override public void onPartialResults(Bundle b){
                ArrayList<String>a=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(a!=null&&!a.isEmpty()) sendPartial(a.get(0));
            }
            @Override public void onResults(Bundle b){
                ArrayList<String>a=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(a!=null&&!a.isEmpty()) sendCandidates(a);
                else if(listeningActive) restartListeningSoon();
            }
            @Override public void onError(int e){
                if(!listeningActive) return;
                if(waitingFinalResult){
                    sendError("최종 음성 결과를 기다리는 중입니다. 오류 코드: "+e);
                    return;
                }
                String m;
                switch(e){
                    case SpeechRecognizer.ERROR_AUDIO: m="마이크 오디오 오류입니다. 다른 앱이 마이크를 사용 중인지 확인해 주세요."; break;
                    case SpeechRecognizer.ERROR_CLIENT: m="음성인식 서비스 연결 오류입니다. Google 앱을 최신 상태로 확인해 주세요."; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: m="마이크 권한이 없습니다."; break;
                    case SpeechRecognizer.ERROR_NETWORK: m="음성인식 네트워크 오류입니다."; break;
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: m="음성인식 네트워크 시간초과입니다."; break;
                    case SpeechRecognizer.ERROR_NO_MATCH: m="음성 결과 없음 · 다시 듣는 중"; break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: m="말소리가 감지되지 않음 · 다시 듣는 중"; break;
                    default: m="Android 음성인식 오류 코드: "+e; break;
                }
                sendError(m);
                if(e==SpeechRecognizer.ERROR_NO_MATCH || e==SpeechRecognizer.ERROR_SPEECH_TIMEOUT || e==SpeechRecognizer.ERROR_CLIENT){
                    restartListeningSoon();
                }
            }
        });
    }

    private Intent makeSpeechIntent(){
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"ko-KR");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,getPackageName());
        return i;
    }

    private void restartListeningSoon(){
        if(!listeningActive) return;
        main.postDelayed(()->{
            if(!listeningActive) return;
            try{
                if(recognizer==null) createRecognizer();
                if(recognizer==null) return;
                recognizer.cancel();
                recognizer.startListening(makeSpeechIntent());
            }catch(Throwable e){
                sendError("음성인식을 다시 시작할 수 없습니다.");
            }
        },300);
    }

    private void startListening(){
        if(android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            sendError("먼저 마이크 권한을 허용해 주세요."); requestMicIfNeeded(); return;
        }
        if(recognizer==null){
            setupRecognizer();
            if(recognizer==null){ sendError("사용 가능한 Android 음성인식 엔진이 없습니다."); return; }
        }
        listeningActive=true;
        waitingFinalResult=false;
        sendState("음성인식 엔진 연결 중...");
        try{
            recognizer.cancel();
            recognizer.startListening(makeSpeechIntent());
        }catch(Throwable e){
            sendError("마이크를 시작할 수 없습니다: "+e.getClass().getSimpleName());
            restartListeningSoon();
        }
    }

    private void stopListeningForResult(){
        if(recognizer==null) return;
        waitingFinalResult=true;
        try{ recognizer.stopListening(); }catch(Throwable ignored){}
    }

    private void stopListening(){
        listeningActive=false;
        waitingFinalResult=false;
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
            tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"kana-v22.1");
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
        @JavascriptInterface public void stopListeningForResult(){main.post(()->stopListeningForResult());}
        @JavascriptInterface public void speak(String text){main.post(()->speakJapanese(text));}
    }

    @Override protected void onDestroy(){
        try{if(recognizer!=null)recognizer.destroy();}catch(Throwable ignored){}
        try{if(tts!=null){tts.stop();tts.shutdown();}}catch(Throwable ignored){}
        try{if(webView!=null)webView.destroy();}catch(Throwable ignored){}
        super.onDestroy();
    }
}
