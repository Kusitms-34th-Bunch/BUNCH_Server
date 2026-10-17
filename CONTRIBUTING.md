# 프로젝트 협업 컨벤션

- `main`은 운영 기준, `dev`는 개발 통합 브랜치다.
- 초기 루트 커밋 이후 작업 브랜치 → dev → main으로 진행한다.
- 브랜치는 `prefix/이슈번호-description-with-dash-english` 형식이다.
  `00`은 초기 프로젝트·문서 설정에만 쓰는 예약 번호이며 실제 이슈 번호가 아니다.
- 커밋은 `prefix(domain): 한국어 설명`, 이슈·PR은 `prefix: 한국어 설명`이다.
- prefix는 feat/fix/docs/refactor/test/chore/ci를 사용한다.
- 본문은 제목과 빈 줄로 구분하고 한 줄 72자 이내로 작성한다.
- 필요한 결정 배경·검증은 Lore Git trailer(`Constraint`, `Rejected`, `Confidence`,
  `Scope-risk`, `Directive`, `Tested`, `Not-tested`, `Related`)로 기록한다.
- 관련 없는 변경을 분리하고 비밀값·로컬 실행 기록을 제외한다.
- 기능 작업은 Spec·이슈·테스트·구현·문서·검증·PR 순서로 진행한다.
- 작업 PR은 Squash merge, dev → main은 Merge commit을 사용한다.
- 필수 CI 통과, 작성자 외 팀원 1명 승인, 미해결 변경 요청·리뷰 대화 처리가 필요하다.
- CodeRabbit·Gemini 지적을 검토하고 미반영 시 이유를 남긴다.
- main/dev 직접 push, 원격 저장소 생성·배포는 별도 요청 없이 수행하지 않는다.
- CI는 dev 대상 PR과 dev push에서 빌드·테스트하며 자동 배포는 하지 않는다.
- 실제 GitHub 이슈·PR 양식은 `.github/`를 따른다.

릴리스 PR은 병합 대상 dev HEAD의 CI 통과 기록을 확인한다. main에서는 CI를 중복 실행하지 않는다.
