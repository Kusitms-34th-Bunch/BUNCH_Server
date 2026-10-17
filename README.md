# BUNCH

가까운 사람들의 사진과 경험을 그룹 단위로 모으는 BUNCH의 백엔드다.
현재 커밋은 공통 인프라만 포함하며 제품 기능은 후속 작업에서 추가한다.

## 실행 환경

Kotlin 1.9.25 · Spring Boot 3.5.4 · JDK 21 · Gradle 8.14.3.
PostgreSQL 16과 Redis 7을 사용하며 Flyway와 JPA validate를 유지한다.
기본 패키지는 `com.bunch`, 루트 단일 Gradle 모듈이다.

```bash
cp .env.example .env
docker compose up -d --wait
./gradlew bootRun --args='--spring.profiles.active=local'
```

로컬 기본값은 `.env.example`을 참고한다. 값 변경 시 앱에도 환경 변수를 전달한다.
Swagger는 <http://localhost:8080/swagger-ui/index.html>에서 확인한다.
운영 프로필에서는 기본 비활성화한다. 제품 API는 아직 없다.

## 테스트

최초 한 번 테스트 DB를 만든다. 이미 존재하면 다시 만들지 않는다.

```bash
docker compose exec -T postgres createdb -U bunch bunch_test
DB_URL=jdbc:postgresql://localhost:5432/bunch_test ./gradlew clean build
```

테스트는 실제 PostgreSQL·Redis와 `test` 프로필을 사용한다.
운영 저장소를 지정하지 않는다. 린트·별도 정적 분석은 아직 구성하지 않았다.

## 문서

[AI 지침](AGENTS.md) · [협업 규칙](CONTRIBUTING.md) · [개발 규칙](docs/GROUND_RULES.md).
PRD와 상세 Spec은 다음 문서 정비 커밋에서 추가한다.

## 출처

[springboot-kotlin-initial-template](https://github.com/wingwogus/springboot-kotlin-initial-template)의
`auth-kakao-sdk-oidc-app` 브랜치 `a013e3285e3b93c84c4d7401cacafd7af600d0a2`를
바탕으로 공통 기반을 분리했다. 원본 Git 이력은 가져오지 않았다.
