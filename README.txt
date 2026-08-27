일본어 50음도 퀴즈 Android 프로젝트

중요:
- GitHub에는 ZIP 파일 하나를 올리는 것이 아니라 ZIP을 압축 해제한 뒤 이 프로젝트의 '내용 전체'를 올립니다.
- app 폴더, build.gradle, settings.gradle, .github 폴더가 저장소 루트에 있어야 합니다.
- AndroidManifest.xml에 마이크 권한을 넣었습니다.
- MainActivity.java에서 WebView의 마이크 권한 요청을 Android RECORD_AUDIO 권한과 연결했습니다.
- app/src/main/assets/index.html은 PC용 퀴즈 로직을 포함합니다.

GitHub Actions:
저장소에 올린 후 Actions에서 Build APK 워크플로를 실행하면 debug APK를 artifact로 받을 수 있습니다.
