# Android 개발 안내

사용 방법·실행 한계는 [프로젝트 README](../README.md), 저장·전송·삭제 범위는 [개인정보 처리 설명](../PRIVACY.md), 사용 라이브러리는 [제3자 고지](../THIRD_PARTY_NOTICES.md)를 확인합니다. 이 앱은 포스웰과 제휴하거나 공식 승인을 받은 앱이 아닙니다.

## 버전과 환경

버전은 [`app/build.gradle.kts`](app/build.gradle.kts)의 `versionName`/`versionCode` 한 곳에서 정의합니다. Android 빌드가 이 값을 패키지 정보로 생성하며 앱 정보 화면은 설치된 패키지의 버전을 표시합니다. 문서의 별도 숫자로 버전을 판단하지 않습니다.

JDK 17 이상, Gradle Wrapper와 빌드 설정에 지정된 Android SDK가 필요합니다. 최소 지원 SDK는 빌드 설정의 `minSdk`를 따릅니다. 로컬 SDK 경로·서명 암호는 저장소에 추가하지 않습니다.

## 로컬 검사

`android-app` 폴더에서 실행합니다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

위 명령은 JVM 시험·정적 검사·디버그 APK 빌드입니다. 실제 사이트 주문 성공이나 잠금·Doze·재부팅 이후 실행을 증명하지 않습니다. 가상 기기 계측과 실제 기기 시험은 별도의 범위와 장비가 필요합니다. 실제 계정·주문을 사용하는 opt-in 시험을 일반 검사에 자동 포함하지 않습니다.

개발용 debug APK와 공개 배포용 release APK를 구분합니다. release의 서명·최종 Manifest·APK 내용·해시와 배포 문서를 확인한 뒤 배포하며, 서명이 다른 기존 debug 설치본의 데이터 이전은 별도로 검증해야 합니다.

## 공개용 로컬 묶음

[`Export-PublicStaging.ps1`](../scripts/Export-PublicStaging.ps1)은 현재 소스와 공개 문서만 허용 목록으로 복사합니다. 기본 실행은 미리보기이며 실제 복사는 `-Apply`로 명시합니다. 계정·쿠키·기기 자료·연구 원문·서명키·빌드 캐시는 포함하지 않습니다. Git 사용자 설정·remote·commit·push와 저장소 가시성은 변경하지 않습니다.

```powershell
pwsh -File ..\scripts\Export-PublicStaging.ps1
```

복사 후 생성된 `PUBLIC_MANIFEST.json`은 상대 경로별 SHA-256을 기록합니다. APK를 포함할 때는 검사한 빌드 파일을 별도로 지정하고, source version과 APK package/version이 일치하는지 검사합니다. 기존 묶음의 허용 목록 밖 추적 파일은 자동 삭제하지 않고 보고합니다.

## 공개 배포 서명

release 패키징에는 `POSWEL_RELEASE_KEYSTORE`, `POSWEL_RELEASE_STORE_PASSWORD`, `POSWEL_RELEASE_KEY_ALIAS`, `POSWEL_RELEASE_KEY_PASSWORD` 네 환경변수가 모두 필요합니다. 모두 비어 있으면 debug 검사는 가능하지만 `packageRelease`는 `verifyPublicSigning`에서 실패합니다. 일부만 설정하는 경우도 오류입니다.

키 생성·보관은 배포 책임자가 별도로 결정합니다. 키 파일이나 암호를 코드·명령행 인자·문서·공개 묶음에 기록하지 않습니다. 동일 패키지의 기존 debug 설치본은 다른 release 키로 덮어 설치할 수 없으므로, 실제 기기를 초기화하거나 삭제하기 전에 사용자 데이터 보존과 서버 주문내역 확인 절차를 따로 확정해야 합니다.
