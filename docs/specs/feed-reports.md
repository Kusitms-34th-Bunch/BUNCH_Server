# 번들·댓글 신고

이슈 #91(F), FEED-07, 승인 원문 6.2.10·6.2.14와 6.2.11의 역할 규칙.
[피드 공통 계약](feed-api.md), [댓글 계약](feed-comments.md),
[프로필 신고](bunch-social.md)의 #75 정책을 함께 따른다.
이번 범위는 콘텐츠 신고이며 기존 번치 프로필 신고 API를 보존한다.

2026-11-22 사용자 결정(#207)은 이전 원문·보완 계약의 신고 비노출 정책을 대체한다.
BUNCH/BUNDLE/COMMENT 신고는 누구의 콘텐츠 노출·집계·접근도 바꾸지 않는다.
접수 한도·사유·차단·공개 범위 검사와 #206의 현재 번치 정책은 유지한다.

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
클라이언트는 성공 시 접수 안내만 표시하며 콘텐츠를 제거·숨기거나 이전 화면으로 이동하지 않는다.
3번째 신고의 `BUNCH_011`이면 시트를 닫고 「이미 신고가 접수되어 검토 중이에요.」를 표시한다.

| 조건 | HTTP / 오류 |
| --- | --- |
| 사유·상세·양수 ID 위반 | 400 `COMMON_001` (JSON 역직렬화 실패는 `COMMON_002`) |
| 인증 없음 / 비활성 계정 / 가입 중 | 401 `AUTH_001` / 401 `AUTH_001` / 403 `AUTH_015` |
| 계정 없음 / 현재 ACTIVE 번치 없음 | 404 `USER_001` / 409 `BUNCH_010` |
| 우리 번들 OWNER 신고 / 본인 댓글 신고 | 403 `AUTH_002` |
| 번들 소유 번치와 양방향 차단·삭제 | 404 `BUNCH_009` |
| 비공개 외부 번치 미승인 | 403 `AUTH_002` |
| 삭제·비발행·레거시 전역 BUNCH/BUNDLE 숨김·외부 멤버 공개 번들 / 댓글 불일치·삭제·작성 번치 차단/레거시 전역 BUNCH 숨김 | 404 `RESOURCE_001` |
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
동일 신고 번치 잠금 아래 최신 구간 조회·횟수 검사·새 신고 저장을 한 트랜잭션으로 처리한다.
첫 접수 경합에서도 최대 2건만 성공하며 실패하면 접수·한도가 모두 롤백된다.
양방향 차단/접근 거부는 quota 검사 전에 판정한다. 차단/해제로 이력·사용 횟수를 초기화하지 않는다.

번들은 `FeedAccessService.requireBundleReportTarget`, 댓글은
`SocialCommentService.requireReportable`로 접근·대상별 권한을 검증한다.
신고 이력과 non-null reporter 숨김은 일반 조회·재신고 모두에서 무시한다.
차단·공개 범위·삭제와 레거시 전역 BUNCH/BUNDLE 숨김은 계속 검사한다.
검증 성공 후 입력 target ID로 신고를 저장하며 신고 전용 검사를 일반 조회·쓰기에 사용하지 않는다.

## V26 신고 이력 보존

`social_content_reports`는 reporter 번치 FK(CASCADE), 실행 멤버 FK(RESTRICT),
`target_kind`(BUNDLE/COMMENT)·양수 `target_id`, 사유·상세·검토 상태·검토 시각,
`created_at`·`window_started_at`을 저장한다. `(reporter,kind,target,window DESC,id DESC)` 인덱스를 둔다.
대상은 다형 ID이므로 대상 FK와 영구 pair UNIQUE는 없다.
번들/댓글 삭제 뒤에도 타깃 신고 이력을 유지한다. 타깃 ID를 재사용하지 않는다.
reporter 번치 삭제 시 그 번치가 제출한 신고는 기존 FK 정책으로 제거한다.
신고 접수는 숨김을 저장하지 않으며 재신고도 매번 신고 row만 추가한다.
레거시 숨김의 호환·보존 경계는 [공통 계약](feed-api.md)을 따른다.

## 신뢰된 운영 검토 후 삭제 (#207)

위반 확인 후 호출자가 운영자를 사전에 인증한 신뢰된 운영 경계에서만 아래 내부 메서드를 호출한다.
메서드 자체는 운영자 인증을 제공하지 않는다. 별도 심사 워크플로·검토 상태/결과 writer·
관리자 HTTP API·인증·화면이나 자동 제재를 추가하지 않는다.

| 내부 메서드 | 삭제 효과 |
| --- | --- |
| `SocialCommentService.deleteForModeration(commentId)` | 일반 댓글 삭제와 같은 soft delete: 본문을 비우고 `deletedAt`을 설정. 「삭제된 댓글입니다」 자리·답글·전체 댓글 수와 답글 N의 삭제 자리 포함 규칙 유지 |
| `BundleService.deleteForModeration(bundleId, requestId)` | 일반 번들 삭제와 같은 생애주기 재사용: 댓글 리마인더 취소 → revision 증가·`DELETED` 이벤트 → hard delete/FK cascade → 커밋 후 이미지 참조 해제. 사진함 원본·보존 대상 핀은 기존 삭제 계약 유지 |

운영 댓글 삭제와 같은 트랜잭션에서 `NotificationWriter.redactModeratedComment(commentId)`가
해당 댓글의 기존 `SOCIAL_COMMENT_CREATED` 알림 `body`와 `push_jobs.payload.aps.alert.body`를
「삭제된 댓글입니다」로 교체한다. 기존 미리보기 정리이며 새 알림이나 신고 결과 통지가 아니다.

검토·삭제 조치는 새 숨김으로 대체하지 않는다. 신고 접수·검토 결과·삭제 조치 알림을
만들지 않으며 신고자에게 결과를 통지하지 않는다. 위 `DELETED`는 번들 동기화 이벤트다.
BUNCH 신고에 대한 새 번치 삭제·정지 처분이나 일반 수정·삭제의 현재 번치 전환 문제는 범위 밖이다.

## V52 전환 계약

`V52__report_visibility_and_moderated_comments.sql`은 신고에서 파생된 모든 non-null
`reporter_bunch_id` 숨김 행을 제거한다. 별도 신고 이력인 `bunch_profile_reports`와
`social_content_reports`는 그대로 보존한다.
기존 전역 COMMENT 숨김(`reporter_bunch_id IS NULL`)의 실제 소셜 댓글·답글은 `content=''`로 비우고
`deletedAt`을 설정한다. 이미 삭제된 댓글은 삭제 시각을 유지하며, 변환 뒤 해당 전역 COMMENT
숨김 행을 제거하기 전에 해당 댓글의 기존 알림·푸시 미리보기도 같은 문구로 교체한다.
이후 전역 COMMENT 숨김 행은 대상이 없는 행까지 제거한다. 답글·집계 자리는 보존하고 신고 이력은 변경하지 않는다.
구버전 앱 배포 중 다시 남는 non-null reporter 숨김도 모든 조회에서 무시하며 댓글 삭제로 변환하지 않는다.
기존 null 전역 BUNCH/BUNDLE 숨김은 변경 없이 과거 호환용으로 남긴다.
적용된 기존 마이그레이션은 수정하지 않는다.

## 검증 기준과 범위

실제 PostgreSQL·Redis에서 정상/실패 HTTP와 권한, 두 종류 재신고와 고정 경계,
멤버 동시 접수, 차단 우선순위, 한도 롤백, 세 종류 신고 전후의 조회·집계·알림 불변,
레거시 프로필 신고/non-null 숨김 무시, 전역 BUNCH/BUNDLE 숨김 호환,
운영 댓글 삭제의 자리·답글 보존·기존 미리보기 교체 원자성과 번들 일반 삭제 생애주기 재사용,
V52 변환·파생 non-null 숨김 제거·이미 삭제된 댓글·고아 숨김 정리·신고 이력 보존·재실행·JPA validate,
기존 #75 회귀와 전체 build를 검증 대상으로 둔다.
실행 로그는 PR/완료 보고에 남긴다. iOS/WebView 수동 동작·실제 APNs는 별도 검증이다.
