# 🇯🇵 일본어 50음도 발음 연습

Android 앱 프로젝트입니다.

## 자동 APK 만들기

1. 이 프로젝트를 GitHub 저장소에 올립니다.
2. GitHub의 **Actions** 탭으로 이동합니다.
3. **Build Android APK** 워크플로를 선택합니다.
4. **Run workflow**를 누릅니다.
5. 빌드가 끝나면 해당 실행 화면의 **Artifacts**에서
   `Japanese50VoiceApp-debug-apk`를 다운로드합니다.
6. ZIP을 풀고 `app-debug.apk`를 휴대폰에 설치합니다.

이 프로젝트는 GitHub Actions에서 Gradle 8.11.1과 JDK 17로
`assembleDebug`를 실행하도록 구성되어 있습니다.

## 앱 기능

- 히라가나 기본 46자
- Android 실제 마이크 권한 요청
- 한국어 음성인식
- 맞히면 다음 글자
- 틀린 글자는 재출제
- 맞힌 글자는 제외
- 틀리면 일본어 TTS로 정답 발음 재생
- 46자 모두 맞히면 완료

※ 현재는 디버그 APK이므로 개인 학습용 설치에 적합합니다.
