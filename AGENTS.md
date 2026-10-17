<!-- AUTONOMY DIRECTIVE — DO NOT REMOVE -->
YOU ARE AN AUTONOMOUS CODING AGENT. EXECUTE AUTHORIZED TASKS TO COMPLETION WITHOUT ASKING FOR PERMISSION.
ONLY ASK WHEN TRULY AMBIGUOUS OR DESTRUCTIVE.
USE CODEX NATIVE SUBAGENTS FOR INDEPENDENT PARALLEL SUBTASKS WHEN THAT IMPROVES THROUGHPUT.
<!-- END AUTONOMY DIRECTIVE -->

# BUNCH AI 작업 지침

## 기준 문서

| 목적 | 기준 문서 |
| --- | --- |
| 실행·환경 변수·검증 환경 준비 | [README](README.md) |
| 브랜치·커밋·이슈·PR·리뷰·머지 | [CONTRIBUTING](CONTRIBUTING.md) |
| 개발 순서·구현 규칙·필수 완료 조건 | [GROUND_RULES](docs/GROUND_RULES.md) |
| 제품 목표·기능 ID·우선순위·공통 정책 | [PRD](docs/PRD.md) |
| 로그인·가입 계약 | [인증 Spec](docs/specs/auth.md) |
| 번치 생성·초대·참여 계약 | [온보딩 Spec](docs/specs/onboarding.md) |

작업 경로의 하위 AGENTS.md도 읽는다. 공통 규칙을 복제하지 않는다.
제품 정책은 PRD와 관련 Spec을 확인하고, 충돌은 PRD의 원문 우선순위를 따른다.
미정 항목은 해당 Spec에서 확정한 뒤 관련 구현을 시작한다.

## 작업 원칙

- 승인된 범위에서 직접 진행하고 병렬 작업은 파일 소유 범위를 분명히 한다.
- 공유 작업 공간의 다른 변경을 덮어쓰지 않는다. 추적 여부만으로 작업 소유자를 판단하지 않는다.
- 루트 단일 Gradle 모듈과 `com.bunch`를 유지한다. 도메인 패키지는 필요할 때만 만든다.
- 공통 처리는 `global`, 인증은 `auth`, 현재 개인 계정 영속성은 `member` 경계다.
  제품의 Membership(그룹 소속)과 현재 `member` 패키지(개인 계정)를 혼동하지 않는다.
- 정리·리팩토링 전 계획과 유지 동작의 테스트를 확보한다.
- 승인되지 않은 의존성·미정 제품 정책을 추가하지 않는다.
- 실제 비밀값·원문 토큰·인증 헤더를 출력하거나 커밋하지 않는다.
- 계약 변경 시 해당 Spec·테스트·필요한 마이그레이션을 함께 갱신한다.
- 커밋 형식과 Lore trailer는 CONTRIBUTING을 따른다.

## 검증과 완료 보고

필수 완료 조건과 검증 범위는 GROUND_RULES, 실행 명령과 환경 준비는 README를 따른다.
자동 테스트와 실제 iOS SDK·WebView 수동 검증을 구분한다.

완료 보고에는 결과, 변경 파일, 실제 실행 명령·결과, 생략 사유, 잔여 위험을 남긴다.
정확한 토큰 사용량이 없으면 확인 불가로 표시하고 민감한 데이터를 별도 기록하지 않는다.
원격 저장소 생성·push·배포는 별도 요청 범위다.
