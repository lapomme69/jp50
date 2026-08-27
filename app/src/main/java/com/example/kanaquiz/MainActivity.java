package com.example.kanaquiz;
import android.Manifest; import android.app.Activity; import android.content.Intent; import android.content.pm.PackageManager; import android.os.Bundle;
import android.speech.RecognitionListener; import android.speech.RecognizerIntent; import android.speech.SpeechRecognizer; import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface; import android.webkit.WebSettings; import android.webkit.WebView; import android.webkit.WebViewClient; import android.widget.Toast;
import java.util.ArrayList; import java.util.Locale;

public class MainActivity extends Activity {
 WebView web; SpeechRecognizer sr; TextToSpeech tts; static final int MIC=1001;
 @Override public void onCreate(Bundle b){super.onCreate(b); web=new WebView(this); setContentView(web);
  WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
  web.setWebViewClient(new WebViewClient()); web.addJavascriptInterface(new Bridge(),"AndroidVoice");
  tts=new TextToSpeech(this,x->{if(x==TextToSpeech.SUCCESS){tts.setLanguage(Locale.JAPAN);tts.setSpeechRate(.8f);}});
  if(SpeechRecognizer.isRecognitionAvailable(this)){sr=SpeechRecognizer.createSpeechRecognizer(this);sr.setRecognitionListener(new RecognitionListener(){
   public void onReadyForSpeech(Bundle x){} public void onBeginningOfSpeech(){} public void onRmsChanged(float x){} public void onBufferReceived(byte[] x){} public void onEndOfSpeech(){}
   public void onPartialResults(Bundle x){} public void onEvent(int a,Bundle b){} public void onError(int e){}
   public void onResults(Bundle r){ArrayList<String> m=r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(m!=null&&!m.isEmpty())web.post(()->web.evaluateJavascript("window.onNativeSpeechResult("+quote(m.get(0))+")",null));}
  });}
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC);
  web.loadUrl("file:///android_asset/index.html");
 }
 String quote(String x){return "\""+x.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
 public class Bridge {
  @JavascriptInterface public void startListening(){runOnUiThread(()->{if(sr==null)return;if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC);return;}
   Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ja-JP");i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);sr.startListening(i);});}
  @JavascriptInterface public void stopListening(){runOnUiThread(()->{if(sr!=null)try{sr.stopListening();}catch(Exception e){}});}
  @JavascriptInterface public void speak(String x){runOnUiThread(()->{if(tts!=null){tts.stop();tts.setLanguage(Locale.JAPAN);tts.setSpeechRate(.8f);tts.speak(x,TextToSpeech.QUEUE_FLUSH,null,"kana");}});}
 }
 @Override protected void onDestroy(){if(sr!=null)sr.destroy();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}