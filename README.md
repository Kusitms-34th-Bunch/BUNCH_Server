# BUNCH

가까운 사람들이 찍은 사진을 **번치(Bunch) 공동 사진함**에 모으고,
경험 단위의 **번들(Bundle)**을 함께 완성하는 그룹 아카이빙 서비스의 백엔드다.
웹 코드를 WebView로 감싼 iOS 앱을 대상으로 하며, 공개 웹 화면은 초대장 하나다.

저장소: [Kusitms-34th-Bunch/BUNCH_Server](https://github.com/Kusitms-34th-Bunch/BUNCH_Server)

## 프로젝트 상태

- `main`의 초기 커밋은 실행 가능한 공통 인프라다. 제품 기능·회원 테이블은 포함하지 않는다.
- `dev`에는 제품 문서와 기존 소셜 로그인·JWT·회원·Redis 인증 기반을 통합한다.
- 인증 코드는 도메인 계층과 공통 기술 패키지로 분리한다. 약관·프로필·가입 상태와
  온보딩 전체 기능은 후속 구현이다. 제품 확정 정책과 현재 동작의 차이는
  [인증 Spec](docs/specs/auth.md)을 참고한다.

## 기술 스택

| 항목 | 기술 |
| --- | --- |
| 언어·프레임워크 | Kotlin 1.9.25 · Spring Boot 3.5.4 |
| JVM·빌드 | JDK 21 · Gradle 8.14.3 Kotlin DSL |
| 저장소 | PostgreSQL 16 · Redis 7 |
| 스키마 | Flyway · JPA validate |
| 구조 | 루트 단일 모듈 · `com.bunch` |

## 디렉터리 구조

```text
BUNCH_Server/
├── README.md / AGENTS.md / CLAUDE.md / CONTRIBUTING.md
├── .editorconfig / .gitattributes / .gitignore / .env.example
├── compose.yml
├── docs/
│   ├── PRD.md
│   ├── GROUND_RULES.md
│   └── specs/
│       ├── auth.md
│       └── onboarding.md
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
    │   ├── member/           # service / domain
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

## 검증

실제 PostgreSQL·Redis를 준비하고 최초 한 번 테스트 DB를 만든다.
이미 존재하면 다시 생성하지 않는다.

```bash
docker compose exec -T postgres createdb -U bunch bunch_test
DB_URL=jdbc:postgresql://localhost:5432/bunch_test ./gradlew clean build
```

- 통합 테스트는 `test` 프로필을 사용한다. 테스트 DB와 Redis에 운영 저장소를 지정하지 않는다.
- 공통 기반은 응답·오류·로깅·DB·Redis 연결과 OpenAPI·접근 제어를 검증한다.
- 제공자 검증·동시 가입·토큰 처리와 HTTP 인증 회귀 테스트를 함께 실행한다.
- 별도 lint·정적 분석 도구는 아직 구성하지 않았다.
- 실제 iOS SDK·WebView·권한·Universal Link 연동은 앱·제공자 설정 준비 후 수동 검증한다.
  자동 테스트 통과와 실제 앱 연동 완료는 별개다.
- 실행 결과는 PR과 완료 보고에 남긴다. 필수 완료 조건은 개발 규칙을 따른다.

## 기준 문서

| 문서 | 내용 |
| --- | --- |
| [PRD](docs/PRD.md) | 제품 범위·우선순위·도메인·공통 정책 |
| [개발 규칙](docs/GROUND_RULES.md) | 작업 순서·구현·검증·완료 조건 |
| [인증 Spec](docs/specs/auth.md) | 로그인·약관·프로필·가입 완료·세션 |
| [온보딩 Spec](docs/specs/onboarding.md) | 번치 생성·초대 발급·참여·웹 초대장 |
| [협업 규칙](CONTRIBUTING.md) | 브랜치·커밋·이슈·PR·리뷰·머지 |
| [AI 지침](AGENTS.md) | 에이전트 작업 원칙과 문서 탐색 |

## 기반 출처와 후속 설정

[springboot-kotlin-initial-template](https://github.com/wingwogus/springboot-kotlin-initial-template)의
`auth-kakao-sdk-oidc-app` 브랜치 `a013e3285e3b93c84c4d7401cacafd7af600d0a2`를
바탕으로 공통 기반을 분리했다. 원본 Git 이력은 가져오지 않았다.

CI는 dev 대상 PR과 dev push에서 빌드·테스트한다. 자동 배포는 구성하지 않았다.
원격 연결과 브랜치 보호는 설정했다. CodeRabbit은 설치되어 있고 Gemini 연결은 후속 작업이다.
배포는 별도 작업이다.
확정 마감일은 없다.
