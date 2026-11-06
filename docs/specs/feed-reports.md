# 번들·댓글 신고

이슈 #91(F), FEED-07, 승인 원문 6.2.10·6.2.14와 6.2.11의 역할 규칙.
[피드 공통 계약](feed-api.md), [댓글 계약](feed-comments.md),
[프로필 신고](bunch-social.md)의 #75 정책을 함께 따른다.
이번 범위는 콘텐츠 신고이며 기존 번치 프로필 신고 API를 보존한다.

## HTTP 계약

| 요청 | 의미 |
| --- | --- |
| `POST /api/v1/bundles/{bundleId}/reports` | 발행 번들 신고 |
| `POST /api/v1/bundles/{bundleId}/comments/{commentId}/reports` | 소셜 댓글 또는 1단계 답글 신고 |

인증된 가입 완료 ACTIVE 개인 계정과 서버가 선택한 현재 ACTIVE 번치가 필요하다.
요청에서 reporter 번치·실행 멤버·검토 상태·접수 시각은 받지 않는다.
경로 ID는 양수다. 댓글은 반드시 경로의 번들에 속해야 한다.

```json
{"reason":"OTHER","detail":"  신고 이유  "}
```

`reason`: `SPAM`, `INAPPROPRIATE`, `HARASSMENT`, `PERSONAL_INFORMATION`, `OTHER` 중 하나 필수.
`detail`은 trim 후 최대 200 grapheme이며 빈 값은 null로 저장한다. OTHER는 trim 후 1자 이상 필수다.
공백 외의 줄바꿈·이모지는 보존한다. 다른 사유도 상세를 전달하면 동일한 길이 제한을 적용한다.

성공은 HTTP 200, 공통 `success/data/error` 봉투의 `data: {"reportId": 123}`다.
매 성공마다 새 ID, 사유·상세·`RECEIVED` 상태·실행 멤버·시각을 독립 저장한다.
멱등 키는 사용하지 않으며 네트워크 재시도도 새 접수로 한도를 사용한다.
클라이언트는 성공 시 신고 번들을 이전 화면에서 제거하거나 신고 댓글을 즉시 숨긴다.
3번째 신고의 `BUNCH_011`이면 시트를 닫고 「이미 신고가 접수되어 검토 중이에요.」를 표시한다.

| 조건 | HTTP / 오류 |
| --- | --- |
| 사유·상세·양수 ID 위반 | 400 `COMMON_001` (JSON 역직렬화 실패는 `COMMON_002`) |
| 인증 없음 / 비활성 계정 / 가입 중 | 401 `AUTH_001` / 401 `AUTH_001` / 403 `AUTH_015` |
| 계정 없음 / 현재 ACTIVE 번치 없음 | 404 `USER_001` / 409 `BUNCH_010` |
| 우리 번들 OWNER 신고 / 본인 댓글 신고 | 403 `AUTH_002` |
| 번들 소유 번치와 양방향 차단·삭제 | 404 `BUNCH_009` |
| 비공개 외부 번치 미승인 | 403 `AUTH_002` |
| 삭제·비발행·전역 숨김·외부 멤버 공개 번들 / 댓글 불일치·삭제·전역 숨김·작성 번치 차단 | 404 `RESOURCE_001` |
| 같은 reporter+kind+target의 24시간 세 번째 접수 | 409 `BUNCH_011` |

현재 번치 notice 등 공통 접근 오류는 feed-api 계약을 유지한다.
우리 번들 CO_OWNER/MEMBER는 신고할 수 있고 OWNER는 할 수 없다. 외부 번들에는 어떤 역할도 신고 가능하다.
댓글 작성자의 개인 ID로 자기 신고를 금지한다. 같은 번치라도 다른 멤버가 쓴 댓글은 신고할 수 있다.

## 고정 구간·동시성·원자성

#75의 `BunchReportPolicy`를 기존 프로필 신고와 콘텐츠 신고가 실제로 함께 사용한다.
첫 정상 접수의 DB 마이크로초 정밀도 `t0`부터 `[t0, t0+24h)` 동안 최대 2건이다.
두 번째 접수·거부는 구간을 연장하지 않는다. 정확히 24시간부터 다음 정상 접수가 새 구간을 연다.
상태를 REVIEWING/ACTIONED/DISMISSED로 바꿔도 구간 내 횟수는 그대로다.
집계 키는 `(reporter_bunch_id, target_kind, target_id)`다. 종류·대상·신고 번치가 다르면 별개다.
프로필 신고 이력과도 독립이다. 같은 번치의 모든 실행 멤버를 합산한다.

쓰기 잠금 순서는 실행 멤버 → 관련 번치 ID 오름차순(신고 번치·번들 소유 번치·댓글 작성 번치)
→ 번들 → 댓글이다. 기존 소셜 쓰기의 member/Bunch 잠금 순서를 재사용한다.
잠금을 기다리기 전에 읽은 영속성 상태를 비운 뒤 현재 ACTIVE 소속·역할·대상 접근을 재검사한다.
현재 번치가 예비 검사 이후 바뀌면 `BUNCH_010`으로 재시도를 요구한다.
동일 신고 번치 잠금 아래 최신 구간 조회·횟수 검사·새 신고 저장·숨김을 한 트랜잭션으로 처리한다.
첫 접수 경합에서도 최대 2건만 성공하며 실패하면 접수·한도·숨김이 모두 롤백된다.
양방향 차단/접근 거부는 quota 검사 전에 판정한다. 차단/해제로 이력·사용 횟수를 초기화하지 않는다.

번들은 `FeedAccessService.requireBundleReportTarget`을 사용해 그 번들의 reporter 숨김만 무시한다.
댓글은 실제 `SocialCommentService.reportTarget`을 사용해 해당 댓글의 reporter 숨김만 무시한다.
부모 숨김·번들 숨김·프로필 신고·전역 숨김·차단·공개범위를 우회하지 않는다.
일반 조회·댓글 작성 등에 이 신고 전용 접근을 사용하지 않는다.

## V26 저장과 비노출

`social_content_reports`는 reporter 번치 FK(CASCADE), 실행 멤버 FK(RESTRICT),
`target_kind`(BUNDLE/COMMENT)·양수 `target_id`, 사유·상세·검토 상태·검토 시각,
`created_at`·`window_started_at`을 저장한다. `(reporter,kind,target,window DESC,id DESC)` 인덱스를 둔다.
대상은 다형 ID이므로 대상 FK와 영구 pair UNIQUE는 없다.
번들/댓글 삭제 뒤에도 타깃 신고 이력·숨김을 유지한다. 타깃 ID를 재사용하지 않는다.
reporter 번치 삭제 시 그 번치가 제출한 신고와 숨김만 제거한다.

성공한 같은 트랜잭션에서 `FeedContentHideRepository.hideForReporter`를 호출한다.
재신고는 신고 row는 늘리고 reporter 숨김 row는 최초 하나를 유지한다.
다른 번치·소속 0개 조회에는 해당 reporter 숨김을 적용하지 않는다.
원댓글 신고 시 답글도 목록·댓글 수·답글 N에서 제외되며 소프트 삭제 자리보다 숨김이 우선한다.
신고 자체로 전역 숨김·차단·정지·알림을 만들지 않는다.
운영 숨김/해제는 feed-api의 null-reporter DB 행만 관리하며 별도 운영 인증/API/자동 제재는 없다.
전역 숨김 해제 후에도 reporter 숨김·삭제·접근 조건은 유지한다.

## 검증 기준과 범위

실제 PostgreSQL·Redis에서 정상/실패 HTTP와 권한, 두 종류 재신고와 고정 경계,
멤버 동시 접수, 차단 우선순위, 한도/숨김 롤백, 조회/집계와 운영 숨김,
V25→V26→V27 적용·재실행·JPA validate, 기존 #75 회귀와 전체 build를 검증한다.
실행 로그는 PR/완료 보고에 남긴다. iOS/WebView 수동 동작·실제 APNs는 별도 검증이다.
