# MinePortal 데스크톱 클라이언트

마인크래프트 서버에 **사용자 본인의 PC/네트워크에서 직접** 접속해 채팅을 주고받는 네이티브 데스크톱 프로그램입니다.
웹 대시보드와 달리 접속 IP가 사용자 본인의 것이므로, "동일 IP 다중 접속 차단" / "데이터센터 IP 자동 밴" 정책이 있는
서버에서도 문제없이 사용할 수 있습니다.

- **로그인**: Microsoft 계정 (Device Code Flow)
- **프로토콜/채팅**: [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib) `1.21.7-1` (Minecraft **1.21.7** 프로토콜)
- **인증**: [MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth) `4.1.1`
- **UI**: Java Swing
- **자동 업데이트**: GitHub Releases

## 요구 사항

- Java **17 이상** (개발/빌드 확인 환경: JDK 21)

## 실행

빌드된 fat jar 실행:

```bat
java -jar mineportal-client.jar
```

동작 순서:

1. **로그인** 버튼 → Microsoft 로그인 창이 뜨고, 표시된 URL을 브라우저에서 열어 코드를 입력합니다.
   - "브라우저 열기" 버튼은 코드가 미리 채워진 주소(`...?otc=코드`)를 엽니다.
   - 로그인 세션은 `~/.mineportal/session.json`에 저장되어 다음 실행 시 자동 로그인됩니다.
2. **서버 목록**에서 "추가" 로 호스트/포트/버전을 등록합니다. (`~/.mineportal/servers.json`에 저장)
   - `버전` 필드는 참고용 메모입니다. 이 빌드는 번들된 프로토콜(1.21.7)로만 접속합니다.
3. 서버 선택 후 **접속** → 채팅 로그가 표시되고, 하단 입력창에서 메시지를 전송할 수 있습니다.

> 참고: 이 클라이언트는 채팅 서명(secure chat)을 하지 않고 메시지를 전송합니다.
> "보안 채팅 강제(enforce-secure-profile)"가 켜진 서버에서는 메시지 전송이 거부될 수 있습니다.

## 빌드 (fat jar)

프로젝트 루트(`desktop-client/`)에서:

```bat
gradlew.bat shadowJar
```

산출물: `build/libs/mineportal-client.jar` (모든 의존성이 포함된 실행 가능한 fat jar)

`gradlew.bat build` 를 실행하면 컴파일 + fat jar 생성이 함께 수행됩니다.

## 릴리즈 배포 (수동)

앱은 시작 시 `https://api.github.com/repos/rdyjun/mineportal/releases/latest` 를 조회하여
`tag_name` 을 현재 앱 버전과 비교합니다. **태그 이름이 곧 앱이 인식하는 버전**입니다.

배포 절차:

1. `desktop-client/build.gradle` 의 `version` 값을 올립니다. (예: `1.0.0` → `1.0.1`)
   - 이 값이 jar 안 `version.properties` 의 `app.version` 으로 박혀 나갑니다.
2. `gradlew.bat shadowJar` 로 `build/libs/mineportal-client.jar` 를 생성합니다.
3. GitHub 저장소에서 **새 릴리즈**를 만들고, 태그를 새 버전과 동일하게 지정합니다.
   - 태그는 `1.0.1` 또는 `v1.0.1` 형식 모두 인식합니다. (앞의 `v` 는 비교 시 무시)
4. 릴리즈 자산(assets)으로 `mineportal-client.jar` 를 업로드합니다.
   - 자동 업데이트는 이름이 `.jar` 로 끝나는 **첫 번째 자산**을 내려받습니다.

### 자동 업데이트 동작

- 새 버전 감지 시 다이얼로그가 뜹니다: **"다운로드 및 재시작" / "릴리즈 페이지 열기" / "나중에"**.
- "다운로드 및 재시작" 선택 시:
  - 새 jar 를 `현재jar.new` 로 내려받고,
  - 현재 프로세스가 종료되길 기다렸다가 jar 를 교체하고 재실행하는 배치 스크립트를 실행한 뒤,
  - 앱을 종료합니다.
- IDE/클래스 디렉토리에서 실행 중이거나 jar 자산이 없으면 자동 교체 대신 **릴리즈 페이지를 여는 것으로 폴백**합니다.
- 자동 교체 스크립트는 Windows(`cmd`/`javaw`) 기준입니다.

## 로컬 데이터 위치

- 서버 목록: `~/.mineportal/servers.json`
- 로그인 세션: `~/.mineportal/session.json`

(`~` 는 사용자 홈 디렉토리. Windows에서는 `C:\Users\<사용자>`)
