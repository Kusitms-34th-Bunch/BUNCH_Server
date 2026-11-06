# 개인 댓글·답글 계약

FEED-05, NOTI-02 / #90. 선행 #87, PR #96. 공통 접근은 [feed-api](feed-api.md),
정책 기준은 승인된 6.2.6~9와 2026-11-07 보완 결정이다.
기존 `bundle_comments`는 참여자 코멘트이며 이 API와 무관하다.

## API

기본 경로 `/api/v1/bundles/{bundleId}/comments`. 모든 요청은 활성 JWT 세션을 요구한다.
현재 번치는 서버가 선택한다. 소속 0개도 공개 댓글을 읽을 수 있지만 쓰기는 BUNCH_010이다.

| 메서드·경로 | 입력 | 결과 |
| --- | --- | --- |
| GET 기본 경로 | size 기본20/최대50, cursor 선택 | 원댓글 최신순 페이지 |
| GET `/{commentId}/replies` | 같은 페이지 입력 | 해당 원댓글의 답글 오래된순 페이지 |
| POST 기본 경로 | Idempotency-Key(UUID), `{content, parentId?}` | 등록된 댓글/답글 |
| DELETE `/{commentId}` | 없음 | 삭제된 자리. 작성자만, 재삭제 멱등 |

응답은 공통 success/data/error 형식이다. 페이지는 `items, nextCursor, hasNext, totalCount`.
원댓글 항목에 `replyCount`를 주고 모든 답글은 별도 답글 API로 끝까지 읽는다. 임의 개수의
답글을 내장해 잘라내지 않는다. `totalCount`는 번들의 원댓글+답글 전체 수다.
정렬 키는 createdAt/id이고 커서는 번들·부모·현재 번치에 묶인다. 번치 전환 후 커서는 400.
각 페이지는 최신 접근·비노출 조건을 LIMIT 전에 재확인한다. 커서는 접근 권한을 부여하지 않는다.

항목: id/bundleId/parentId/content/deleted/createdAt/author/badge/replyCount/canDelete/canReply/canReport.
author는 memberId/nickname/imageUrl, badge는 bunchId/name/characterType(작성 시 배지 이름·분위기).
닉네임·사진은 현재 계정에서 읽고 탈퇴 계정은 작성자/null로 익명화한다.
삭제 자리는 content=삭제된 댓글입니다, author=작성자, 사진·회원ID·배지·날짜=null,
쓰기 메뉴 없음. 답글 없는 원댓글도 남고 원댓글 삭제 시 기존 답글은 유지한다.

주요 오류: 입력/커서 오류 COMMON_001(400), payload가 다른 멱등 키 REQUEST_001(409),
삭제된 부모/2단계 답글 BUNDLE_002(409), 없는/숨긴/다른 번들의 댓글 RESOURCE_001(404),
타인 삭제/자기 신고 AUTH_002(403). 인증·가입·번들 접근 오류는 공통 feed-api 계약을 따른다.

## 저장·동시성·권한

- trim 후 1~200 grapheme(\X), 내부 줄바꿈 보존. 수정 API 없음.
- `social_comments`(V25)에 개인 작성자, acting Bunch, 작성 시 배지, 부모, 삭제 시각을 저장한다.
  부모는 같은 번들의 원댓글만 가능하며 DB FK/트리거로 강제한다.
- 작성자만 soft delete하며 본문을 비운다. 번치 관리자도 타인 삭제 불가.
- 이미 삭제된 부모는 BUNDLE_STATE_CONFLICT(409). 검증 성공 후 삭제가 경합하면 답글 허용:
  부모를 삭제 상태로 조건부 삽입하거나 부모 행 쓰기 잠금으로 재검증하지 않는다.
- Idempotency-Key는 작성자+작성 번치+번들 범위에서 유일하다. 같은 정규화 내용·부모는
  기존 결과를 반환하고 다른 payload는 IDEMPOTENCY_CONFLICT(409). 삭제 후 재시도도 부활하지 않는다.
  매 재시도에도 최신 접근을 검사한다. 등록·알림·푸시 작업은 같은 트랜잭션이다.
- 생성은 member → 관련 Bunch ID 오름차순 → bundle 잠금 후 현재 번치·소속·접근 재확인.
  삭제는 작성자 계정과 댓글 잠금 후 현재 접근을 재확인하며 부모 삭제와 답글 삽입을 분리한다.
- 번들 영구 삭제 시 댓글은 FK cascade로 제거한다. 작성 번치 삭제는 acting FK를 null로
  바꾸고 외부 번들의 개인 댓글·답글을 보존하며 배지를 숨긴다. 숨김 기록은 A 보존 정책을 따른다.

## 비노출·집계·내부 인터페이스

삭제 자리도 전체 댓글 수와 replyCount에 포함한다. acting Bunch의 양방향 차단·프로필 신고·
운영 숨김, 댓글 자체의 현재 번치 신고·운영 숨김은 제외한다. 숨겨진 원댓글의 하위는 모두
제외한다. 같은 조건을 목록·답글·집계에 적용한다. 비공개 acting Bunch의 팔로우는 요구하지 않는다.

`SocialCommentService.counts(userId, bundleIds): Map<Long, Long>`은 최대500개를 받아
상세 접근 가능한 번들만 집계하고 0도 반환한다. 피드 전용 ACTIVE 소속 제외는 하지 않는다.
`SocialCommentService.reportTarget(userId, bundleId, commentId)`는 F 전용으로 자기댓글을
거부하고 댓글의 reporter 숨김만 무시해 재신고를 허용한다. 번들 접근·작성 번치 차단/프로필신고·
전역 운영 숨김과 원댓글 숨김은 계속 확인한다. 반환은 id/bundleId/authorMemberId/actingBunchId/parentId.
신고 저장·quota는 F 소유이며 이 메서드는 본문 조회에 사용하지 않는다.

## 알림

다른 번치의 댓글·답글은 현재 ACTIVE 번들 참여자에게, 답글은 ACTIVE 원댓글 작성자에게도 보낸다.
원댓글 작성자는 원댓글 acting Bunch의 ACTIVE 소속일 때 받는다. 행동자는 제외하고 겹치는
수신자는 답글 알림 한 건만 받는다. `SOCIAL_COMMENT_CREATED` 타입을 재사용하므로 답글도
댓글 푸시 설정을 따른다. 같은 번치 댓글은 참여자 알림이 없지만 답글 알림은 적용한다.
각 target은 bunchId=수신 inbox, targetBunchId=실제 번들 소유 Bunch,
sourceBunchId=이번 댓글 작성 Bunch, bundleId=실제 번들이다.
NotificationService.open/PushWorker의 late access 검사는 부모 통합 소유다.

## 검증 기준·한계

실제 PostgreSQL/Redis에서 입력·접근·작성자 권한·페이지·비노출/집계·동시 멱등·부모삭제 경합,
DB 제약/Flyway 재실행/JPA validate·알림/푸시 원자성을 검증한다.
실제 iOS/WebView/APNs와 부모 소유 late-access 통합 검증은 이 변경 단독으로 완료를 주장하지 않는다.
