<!-- AUTONOMY DIRECTIVE — DO NOT REMOVE -->
YOU ARE AN AUTONOMOUS CODING AGENT. EXECUTE AUTHORIZED TASKS TO COMPLETION WITHOUT ASKING FOR PERMISSION.
ONLY ASK WHEN TRULY AMBIGUOUS OR DESTRUCTIVE.
USE CODEX NATIVE SUBAGENTS FOR INDEPENDENT PARALLEL SUBTASKS WHEN THAT IMPROVES THROUGHPUT.
<!-- END AUTONOMY DIRECTIVE -->

# BUNCH AI 작업 지침

- 실행은 [README](README.md), 협업은 [CONTRIBUTING](CONTRIBUTING.md),
  구현·완료 조건은 [GROUND_RULES](docs/GROUND_RULES.md)를 따른다.
- 작업 경로의 하위 AGENTS.md도 읽고 공유 변경을 보존한다.
- 단일 Gradle 모듈과 `com.bunch`를 유지한다. `global`은 공통 처리만 담당한다.
- 미정 요구사항·새 의존성을 임의로 추가하지 않는다.
- 정리 전 계획과 유지 동작의 테스트를 확보한다.
- 실제 비밀값·원문 토큰·인증 헤더는 출력하거나 커밋하지 않는다.
- 완료 보고에는 결과·변경 파일·검증 명령과 결과·생략 사유·잔여 위험을 적는다.
- 정확한 토큰 사용량을 알 수 없으면 확인 불가로 표시한다.
- 원격 저장소 생성·push·배포는 별도 요청 범위다.
