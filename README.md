# BUNCH

가까운 사람들이 찍은 사진을 **번치(Bunch) 공동 사진함**에 모으고,
경험 단위의 **번들(Bundle)**을 함께 완성하는 그룹 아카이빙 서비스의 백엔드다.
웹 코드를 WebView로 감싼 iOS 앱을 대상으로 하며, 공개 웹 화면은 초대장 하나다.

저장소: [Kusitms-34th-Bunch/BUNCH_Server](https://github.com/Kusitms-34th-Bunch/BUNCH_Server)

## 프로젝트 상태

- `main`의 초기 커밋은 실행 가능한 공통 인프라다. 제품 기능·회원 테이블은 포함하지 않는다.
- `dev`에는 제품 문서와 기존 소셜 로그인·JWT·회원·Redis 인증 기반을 통합한다.
- 인증 코드는 도메인 계층과 공통 기술 패키지로 분리한다. 약관·프로필·가입 상태와
  온보딩 기능을 이슈 #5에서 확장한다. 확정 정책·API는 [구현 계약](docs/specs/login-onboarding-api.md)을 따르며 기존 동작과의 차이는
  [인증 Spec](docs/specs/auth.md)을 참고한다.

## 기술 스택

| 항목 | 기술 |
| --- | --- |
| 언어·프레임워크 | Kotlin 1.9.25 · Spring Boot 3.5.4 |
| JVM·빌드 | JDK 21 · Gradle 8.14.3 Kotlin DSL |
| API·실시간 | Spring MVC REST · Spring WebSocket/STOMP |
| 저장소 | PostgreSQL 16 · Redis 7 |
| 스키마 | Flyway · JPA validate |
| 구조 | 루트 단일 모듈 · `com.bunch` |
| 관측 | Actuator · Alloy · Loki · Prometheus · Grafana · Discord |

## 디렉터리 구조

```text
BUNCH_Server/
├── README.md / AGENTS.md / CLAUDE.md / CONTRIBUTING.md
├── .editorconfig / .gitattributes / .gitignore / .env.example
├── compose.yml             # 로컬 DB·Redis
├── deploy/                 # 운영 Compose·모니터링 프로비저닝
├── docs/
│   ├── PRD.md
│   ├── GROUND_RULES.md
│   └── specs/
│       ├── auth.md
│       ├── onboarding.md
│       ├── login-onboarding-api.md
│       ├── bundle.md
│       ├── bundle-api.md
│       ├── photo-foundation.md
│       └── monitoring.md
├── .github/
│   ├── ISSUE_TEMPLATE/
│   ├── workflows/ci.yml
│   └── pull_request_template.md
├── build.gradle.kts / settings.gradle.kts / gradlew / gradlew.bat
├── gradle/wrapper/
└── src/
    ├── main/kotlin/com/bunch/
    │   ├── BunchApplication.kt
    │   ├── auth/             # controller / service / dto
    │   ├── member/           # 약관·프로필·개인 계정·온보딩 조회
    │   ├── bunch/            # 번치·소속·초대
    │   ├── notification/     # 참여 알림 영속성
    │   ├── image/            # 공용 이미지 자산·참조 보호·커밋 후 정리
    │   ├── photo/            # Photo·공동 업로더·번들 연결 기반
    │   ├── bundle/           # 번들 생성·공동 편집·발행
    │   └── global/           # config / security / oidc / redis 등
    ├── main/resources/
    ├── test/kotlin/com/bunch/
    └── test/resources/
```

인증·회원 코드와 회원 마이그레이션·관련 테스트를 포함한다.
새 도메인 패키지와 계층은 실제 기능 개발 때 필요한 경우에만 추가한다.

## 로컬 실행

JDK 21과 Docker Compose가 필요하다. 저장소 루트에서 실행한다.

```bash
cp .env.example .env
# 다음 단계 전에 .env의 JWT_SECRET에 아래 인증 환경 변수 표에 따른 키를 입력한다.
docker compose up -d --wait
set -a
. ./.env
set +a
./gradlew bootRun --args='--spring.profiles.active=local'
```

`.env`에는 신뢰할 수 있는 로컬 값만 둔다. Compose의 `.env`가 앱 프로세스에 자동으로
전달되지는 않으므로 위와 같이 내보내거나 IDE에 직접 지정한다.

- Swagger: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI: <http://localhost:8080/v3/api-docs>
- 운영 프로필에서는 Swagger를 기본 비활성화한다.
- 중지: `docker compose down`. 볼륨 삭제 옵션은 데이터를 지우므로 사용 시 주의한다.

### 공통 환경 변수

| 변수 | 용도 / 로컬 기본값 |
| --- | --- |
| `SERVER_PORT` | 앱 포트 / `8080` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/bunch` |
| `DB_USERNAME` / `DB_PASSWORD` | 로컬 전용 `bunch` / `bunch_local` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `POSTGRES_DB` | Compose 최초 DB / `bunch` |
| `BUNCH_WS_ALLOWED_ORIGINS` | STOMP WebSocket을 허용할 브라우저 Origin 목록(쉼표 구분) / `http://localhost:3000` |

프로필은 `local / test / dev / prod`다. 기존 영속 볼륨에서는 `POSTGRES_DB` 변경만으로
새 DB가 생성되지 않는다. 실제 운영 자격 증명은 커밋하지 않는다.

### 인증 환경 변수

현재 인증 기반 기동에는 아래 설정이 필요하다. 예제의 제공자 값은 기동용이며
실제 로그인에는 해당 앱에 발급된 값을 사용한다.

| 변수 | 현재 구현 용도 |
| --- | --- |
| `JWT_SECRET` | 디코딩 후 64바이트 이상인 Base64 키. `openssl rand -base64 64`로 생성해 로컬 환경에만 보관 |
| `APPLE_CLIENT_ID` | Apple 허용 audience |
| `GOOGLE_CLIENT_ID` | Google 서버용 Client ID |
| `KAKAO_NATIVE_APP_KEY` | Kakao Native App Key / 허용 audience |

실제 SDK 검증에는 실제 제공자 설정이 필요하다. 자동 테스트는 테스트 키·로컬 JWKS를
사용하며 실제 제공자 자격 증명이 필요하지 않다.

## 가입·온보딩 환경 설정

| 변수 | 용도 |
| --- | --- |
| `TERMS_SERVICE_VERSION` / `TERMS_PRIVACY_VERSION` / `TERMS_AGE_VERSION` | 앱의 승인 원문과 일치하는 필수 약관 버전 |
| `TERMS_DEMOGRAPHICS_VERSION` | 분석 목적 선택 개인정보 동의 버전 |
| `CLOUDINARY_CLOUD_NAME` / `CLOUDINARY_API_KEY` / `CLOUDINARY_API_SECRET` | 서버의 프로필 사진 업로드·삭제 |
| `BUNCH_INVITATION_BASE_URL` | 실제 공개 웹·앱에 연결할 초대 URL 접두사. 로컬 기본 `http://localhost:3000/invite` |

약관 버전 미설정 시 관련 API는 503이다. 테스트 프로필의 `v1`은 운영 약관이 아니다.
사진 설정이 없어도 서버는 기동하며, 개인 사진 저장 실패는 기본 이미지로 가입을 완료한다.
번치 사진 변경 실패는 기존 이미지를 유지한다. 서버는 앱이 변환한 JPEG만 받는다.
이전 sid 없는 인증 토큰은 세션 정책 변경 배포 후 다시 로그인해야 한다.
실제 약관 원문·이미지 디자인·초대 도메인/AASA는 앱 연동 시 별도로 준비한다.

## 이미지·Photo 기반

V5는 기존 개인·번치 프로필을 공용 `image_assets`로 백필하고 nullable FK로 전환한다.
파일을 재업로드하지 않으며 기존 `imageUrl`과 기본 이미지의 null 응답을 유지한다.
기존 public ID/URL이 한쪽만 있거나 같은 public ID의 URL이 다르면 마이그레이션은
데이터를 바꾸지 않고 실패한다. 배포 전 해당 데이터를 점검한다.

Photo·공동 업로더 스키마와 조회·최종 삭제 연결은
[사진함 기반 계약](docs/specs/photo-foundation.md)을 따른다. 사진함 HTTP 업로드·목록·중복 병합은
별도 구현 범위다. 파일 정리는 참조가 모두 해제된 DB 커밋 후 시도하며 실패 식별자만 기록한다.
자동 정리 배치·재시도 큐는 제공하지 않는다.

## 번들 클라이언트 프로토콜

번들 REST·STOMP 클라이언트 계약은 [번들 API 계약](docs/specs/bundle-api.md)을 따른다.
REST 기본 경로는 `/api/v1/bunches/{bunchId}/bundles`이고 공동 편집 WebSocket의
STOMP endpoint는 `/ws`다. 서버 이벤트는 `BundleEventType` enum과
`type / bunchId / bundleId / revision / requestId / data` 공통 형식으로 전달한다. REST와 STOMP 모두 기존 access JWT의 `sid`를 검증한 활성
인증 세션을 사용하며, WebSocket 연결의 URL에 토큰을 넣지 않는다.

번들 생성·사진 구성 변경·발행은 `Idempotency-Key` UUID를 사용하고, 상태 변경의
상관관계에는 선택적인 `X-Request-Id` UUID를 사용한다. 제목·날짜·캡션 초안 저장은
`X-Bundle-Screen`과 편집권 UUID를 함께 사용한다. 사진함 업로드·목록·중복 병합 HTTP API는
이 저장소의 번들 API에 포함되지 않으며, 테스트 fixture의 Photo ID를 실제 사진함 API로
간주하지 않는다.

## 검증

실제 PostgreSQL·Redis를 준비하고 최초 한 번 테스트 DB를 만든다.
이미 존재하면 다시 생성하지 않는다.

```bash
docker compose exec -T postgres createdb -U bunch bunch_test
DB_URL=jdbc:postgresql://localhost:5432/bunch_test ./gradlew clean build
```

- 통합 테스트는 `test` 프로필을 사용하며 각 Spring 컨텍스트의 DB 풀은 최대 4개다. 테스트 DB와 Redis에 운영 저장소를 지정하지 않는다.
- 공통 기반은 응답·오류·로깅·DB·Redis 연결과 OpenAPI·접근 제어를 검증한다.
- 제공자 검증·동시 가입·토큰 처리와 HTTP 인증 회귀 테스트를 함께 실행한다.
- 가입·약관·프로필·Cloudinary 로컬 HTTP stub·번치 생성/초대/참여·동시 정원/소속 한도를 함께 검증한다.
- 테스트 저장소 포트가 다르면 `DB_URL`, `REDIS_HOST`, `REDIS_PORT`를 지정한다. 운영 저장소나 다른 프로젝트 데이터를 사용하지 않는다.
- V4→V5의 실제 PostgreSQL 백필·불일치 롤백·재실행, JPA validate, Photo·공동 업로더
  제약·소속 검증·공유 파일 보호·커밋 후 삭제를 검증한다. 사진함 fixture와 실제 API 연동은 구분한다.
- 번들 REST의 상태·버전·멱등성·편집권 충돌과 STOMP의 두 연결 인증·구독·heartbeat·실제
  변경 이벤트·재연결 snapshot을 실제 PostgreSQL·Redis로 검증한다. 사진함 HTTP API가
  준비되기 전에는 fixture 검증을 실제 사진함 연동 완료로 표시하지 않는다.
- 별도 lint·정적 분석 도구는 아직 구성하지 않았다.
- 실제 iOS SDK·WebView·권한·Universal Link 연동은 앱·제공자 설정 준비 후 수동 검증한다.
  자동 테스트 통과와 실제 앱 연동 완료는 별개다.
- 실행 결과는 PR과 완료 보고에 남긴다. 필수 완료 조건은 개발 규칙을 따른다.

## 운영 모니터링

앱은 API `8080`과 관리 `8081`을 분리하며 내부 관리 포트에서 health·prometheus만 제공한다.
운영 Compose는 관리 포트를 공개하지 않는다. Alloy가 앱 요청 로그를 Loki로,
Prometheus가 메트릭을 수집하고 Grafana 대시보드 하나에서 함께 조회한다.

Discord 알림은 두 개다. 최근 1분 요청 종료 로그의 **5xx 한 건부터** 다음 10초 평가에서
알리며 복구 메시지는 보내지 않는다. 앱 메트릭 수집 중단은 1분 유지 후 알리고 복구도 보낸다.
최초 묶음 대기는 0초, 지속 장애 반복은 5분이다. 로그 수집·초기 기동·전송 지연이 더해지므로
요청 후 10초 내 수신 보장은 아니다. 로그·메트릭은 7일, Docker 로그는 `10m × 3`으로 제한한다.
운영 환경변수는 `.env` 하나에 두고 Compose의 `env_file`로 읽는다.
상세 조건·한계는 [모니터링 Spec](docs/specs/monitoring.md)을 따른다.

모니터링 설정을 바꿀 때는 Docker가 실행된 환경에서 다음을 검증한다.
로컬 HTTP stub는 실제 Grafana/Loki를 사용하며 운영 Discord에 전송하지 않는다.

```bash
test -f deploy/.env || cp deploy/.env.example deploy/.env
bash deploy/monitoring/check.sh
python3 deploy/monitoring/smoke_test.py
```

## 기준 문서

| 문서 | 내용 |
| --- | --- |
| [PRD](docs/PRD.md) | 제품 범위·우선순위·도메인·공통 정책 |
| [개발 규칙](docs/GROUND_RULES.md) | 작업 순서·구현·검증·완료 조건 |
| [인증 Spec](docs/specs/auth.md) | 로그인·약관·프로필·가입 완료·세션 |
| [온보딩 Spec](docs/specs/onboarding.md) | 번치 생성·초대 발급·참여·웹 초대장 |
| [로그인·온보딩 API 계약](docs/specs/login-onboarding-api.md) | 이슈 #5 확정 정책·API·데이터 보장 |
| [모니터링 Spec](docs/specs/monitoring.md) | 로그·메트릭·두 Discord 알림 계약 |
| [배포 안내](deploy/README.md) | SSH 사용자 홈(`~`) 준비·CI 분기·설정 복원 |
| [번들 Spec](docs/specs/bundle.md) | 번들 생성·공동 편집·발행과 사진함 연결의 확정 계약 |
| [번들 API 계약](docs/specs/bundle-api.md) | 번들 REST·STOMP 클라이언트 요청·응답·복구 프로토콜 |
| [사진함 기반 계약](docs/specs/photo-foundation.md) | ImageAsset·Photo·공동 업로더 스키마와 연결·최종 삭제 |
| [협업 규칙](CONTRIBUTING.md) | 브랜치·커밋·이슈·PR·리뷰·머지 |
| [AI 지침](AGENTS.md) | 에이전트 작업 원칙과 문서 탐색 |

## 기반 출처와 후속 설정

[springboot-kotlin-initial-template](https://github.com/wingwogus/springboot-kotlin-initial-template)의
`auth-kakao-sdk-oidc-app` 브랜치 `a013e3285e3b93c84c4d7401cacafd7af600d0a2`를
바탕으로 공통 기반을 분리했다. 원본 Git 이력은 가져오지 않았다.

CI는 dev 대상 PR과 dev/main push에서 빌드·테스트한다.
문서만 변경하면 변경 확인만 수행하고 빌드·테스트·배포를 건너뛴다.
CI는 앱과 모니터링 변경을 각각 감지한다. 공통 Compose·환경 예제·CI 변경은 둘 다 검증한다.
main push는 앱 변경일 때만 GHCR `latest` 게시와 앱 교체를 수행한다.
모니터링 변경은 네 모니터링 서비스만 함께 재생성하며, 모니터링 단독 변경은 앱을 재시작하지 않는다.
Compose 파일과 변경된 모니터링 파일은 SSH 사용자 홈(`~`)에 복사하며 `.env`는 보존한다.
앱 단독 변경은 모니터링 파일을 건드리지 않는다. DB·Redis·Tunnel은 자동 갱신하지 않는다.
서버 Compose·환경 변수·SSH Secrets 준비는 [배포 안내](deploy/README.md)를 따른다.
원격 연결과 브랜치 보호는 설정했다. CodeRabbit은 설치되어 있고 Gemini 연결은 후속 작업이다.
확정 마감일은 없다.
