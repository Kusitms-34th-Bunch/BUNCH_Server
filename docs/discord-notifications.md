# GitHub → Discord 알림

GitHub Actions에서 Discord 채널 웹훅으로 알림을 보낸다. 서버 앱·DB 설정과는 독립적이며
Python 표준 라이브러리만 사용한다. 팀의
[클라이언트 알림](https://github.com/Kusitms-34th-Bunch/BUNCH_Client/tree/main/.github/workflows)을
서버 브랜치 구조에 맞춰 구성했다.

## 채널과 Secret 설정

1. Discord의 알림 받을 채널에서 **채널 편집 → 연동 → 웹후크 → 새 웹후크**를 선택한다.
2. 웹후크 이름을 `GitHub`로 지정하고 URL을 복사한다.
3. GitHub 저장소 **Settings → Secrets and variables → Actions → New repository secret**에
   이름 `DISCORD_WEBHOOK_URL`, 값으로 복사한 URL을 등록한다.
4. 워크플로와 공용 스크립트를 `dev`에 통합한 뒤, 저장소 릴리스 절차에 따라 기본 브랜치
   `main`에도 반영한다. 이슈와 `pull_request_target` 이벤트는 기본 브랜치의 구성을 사용하므로
   `dev`에만 추가한 상태로 전체 알림이 활성화됐다고 판단하지 않는다.
5. 공개 저장소의 Actions 이벤트 정책에서 `pull_request_target`이 허용되는지 확인한다.
   정책이 실행을 막는다면 저장소 관리자가 해당 알림 워크플로의 허용 여부를 검토한다.
   [GitHub 공식 안내](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)

URL은 자격 증명이다. 코드·문서·이슈·채팅에 원문을 남기지 않는다.
서버의 `.env`나 별도 Discord 봇 토큰은 필요하지 않다.
Secret이 없으면 두 워크플로 모두 안내를 남기고 정상적으로 건너뛴다.

## 알림 문구와 이벤트

| 이벤트 | 메시지 |
| --- | --- |
| 이슈 생성 | `🆕 이슈 생성: #번호 제목` 다음 줄에 `담당자: @담당자 · 작성자: @작성자` |
| PR 생성 | `🆕 PR opened: #번호 제목 by @작성자` |
| PR 머지 | `✅ PR merged: #번호 제목 by @작성자` |
| PR 충돌 | `⚠️ PR conflict: #번호 제목 by @작성자` |

- 번호·제목은 해당 이슈 또는 PR로 연결된다. 담당자가 여러 명이면 모두 표시하고, 없으면
  `미지정`으로 표시한다. `by`는 PR 작성자이며 머지 실행자를 뜻하지 않는다.
- 이슈는 생성 시, PR은 Draft 여부와 관계없이 생성 시 한 번 알린다.
  재오픈·리뷰 준비 전환·일반 편집과 머지하지 않은 종료에는 기본 알림을 보내지 않는다.
- 제목의 줄바꿈은 공백으로 바꾸고 Markdown 특수문자를 이스케이프한다.
  500자를 넘는 제목은 말줄임하며 링크는 유지한다.
- 발신 이름은 `GitHub`다. `@작성자`는 GitHub 계정 표기이며 Discord 계정 매핑은 없다.
  `allowed_mentions.parse`를 비워 실제 사용자·역할·`@everyone` 멘션을 차단한다.
- 여러 충돌 메시지는 가능한 한 줄 단위로 나누며 메시지당 UTF-16 기준 2,000자를 넘지 않는다.

## 충돌 검사

- PR 생성·재오픈·커밋 갱신·대상 브랜치 변경 시 해당 PR만 조회한다.
  제목·본문만 편집한 경우는 건너뛴다. 포크 PR과 Draft PR도 검사한다.
- `main`·`dev` push 시 해당 브랜치로 향하는 열린 PR을 페이지별로 모두 조회한다.
  GitHub REST API에서 개별 PR의 `mergeable`을 다시 확인하며 단순 CI 실패나 리뷰 대기는
  충돌로 취급하지 않는다.
- `mergeable`이 미정인 PR만 6초 간격으로 최대 5회 조회한다.
  계속 미정이면 Actions 경고를 남기고 해당 PR은 알리지 않는다.
- 닫힌 PR은 제외한다. 검사가 일어날 때 충돌 중이면 다시 알리며,
  중복 방지용 라벨·코멘트·외부 상태 저장소는 사용하지 않는다.
- 예약 검사는 없다. 미정 상태가 남으면 다음 대상 이벤트에서 다시 확인한다.

## 실행과 보안 경계

두 워크플로는 기존 CI와 별도로 실행된다. 알림 전송 실패가 기존 빌드·배포 결과를 바꾸지 않으며,
알림 워크플로 자체는 실패로 표시된다. 알림 검사를 브랜치의 필수 CI로 추가하지 않는다.

`pull_request_target`은 포크 PR과 충돌 중인 PR에서도 동작하도록 사용한다.
체크아웃은 이벤트의 `github.sha`를 명시하며, 이 이벤트에서는 기본 브랜치의 신뢰된 커밋이다.
**PR head·merge ref로 바꾸거나 PR 코드를 실행하는 단계를 추가하지 않는다.**
체크아웃 액션은 확인한 전체 커밋 SHA로 고정하고 웹후크 Secret은 확인·전송 단계에만 전달한다.
Git 자격 증명은 체크아웃 뒤 보관하지 않는다. 권한은 기본 알림의 `contents: read`,
충돌 검사의 `contents: read`·`pull-requests: read`만 사용한다.

GitHub API 주소는 고정하며 웹후크는 Discord의 HTTPS 주소만 허용한다.
네트워크 연결·응답 대기에는 30초, 각 작업에는 5분 제한을 둔다.
Discord에는 `wait=true`로 전송 결과를 확인한다. HTTP 오류는 상태 코드만 남기며,
응답 본문·URL·토큰·Authorization 헤더는 출력하지 않는다.

## 검증

로컬 모의 테스트는 실제 GitHub·Discord에 요청하지 않는다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s .github/scripts/tests -v
actionlint .github/workflows/discord-notify.yml .github/workflows/discord-pr-conflict.yml
git diff --check
```

테스트는 이벤트별 전송 조건·문구·담당자, 특수문자·멘션·긴 메시지, 충돌 계산 재조회·페이지 처리,
Secret 누락과 비밀값을 포함하지 않는 실패 처리를 확인한다.
`actionlint`는 선택한 로컬 검증 도구이며 프로젝트 런타임 의존성이 아니다.

실제 전송 검증은 Secret 등록·기본 브랜치 반영 후 테스트 이슈와 PR로 수행한다.
이슈 생성, Draft PR 생성, 머지 없이 종료, 정상 머지, 테스트 브랜치 간 충돌과 기준 브랜치
변경을 차례로 확인하고 해당 Actions 실행 결과와 Discord 메시지를 대조한다.
운영 코드에 테스트용 충돌을 머지하지 않는다.
실제 채널 전송 확인 전까지는 로컬 모의 테스트 통과와 연동 완료를 구분해 보고한다.

## 관리 파일

- [기본 알림 워크플로](../.github/workflows/discord-notify.yml)
- [충돌 알림 워크플로](../.github/workflows/discord-pr-conflict.yml)
- [공용 메시지·전송 스크립트](../.github/scripts/discord_notify.py)
- [모의 테스트](../.github/scripts/tests/test_discord_notify.py)
