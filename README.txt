일본어 50음도 퀴즈 V39.0 - 일본어 번역 / 일본어 TTS / 화면 개선

변경 사항
- 문장익히기에서 번역 버튼을 눌렀을 때만 한국어→일본어 번역을 실행합니다.
- 번역 API의 \uXXXX 유니코드를 정상적으로 일본어 문자로 복원합니다.
- 읽기 기능은 Android TextToSpeech에 일본어 Locale(JAPAN/JAPANESE)을 명시적으로 적용합니다.
- 하단 글자별/전체/문장익히기 버튼을 화면 하단 슬라이드 영역과 겹치지 않도록 약 55px 위로 이동했습니다.
- 기존 글자별/전체/문장익히기 기능은 유지합니다.
- 음성인식 기능은 사용하지 않습니다.

GitHub Actions
- V39.0 APK를 assembleDebug로 빌드합니다.
- artifact: japanese-kana-quiz-apk-v37-0
