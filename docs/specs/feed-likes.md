# 번치 공동 좋아요 — #89

FEED-04·NOTI-02, 원문 6.1.3·6.2.6과 승인된 보완 정책을 구현한다.
접근 기준은 [공통 피드 계약](feed-api.md), 수신 문구와 푸시는
[알림](notification.md)·[푸시](push.md) 계약을 재사용한다.
선행은 #87 / PR #96이며 기반 커밋은 `943d198`이다.

## HTTP 계약

활성 로그인 session·가입 완료 계정과 기존 `success/data/error` envelope를 사용한다.

| 메서드·경로 | 동작 |
| --- | --- |
| `PUT /api/v1/bundles/{bundleId}/like` | 서버 current 번치 명의로 좋아요 상태를 true로 설정 |
| `DELETE /api/v1/bundles/{bundleId}/like` | 같은 상태를 false로 설정. 처음부터 없으면 성공 no-op |
| `GET /api/v1/bundles/{bundleId}/like` | 접근 가능한 발행 번들의 현재 집계 조회 |

요청 본문·Idempotency-Key는 없다. toggle 대신 명시적 상태를 요청하므로 동일 PUT/DELETE의
연속 재시도는 중복 증가·감소하지 않는다. 반대 동작과의 경합은 잠금 후 처리 순서로 결정한다.
과거 요청이 반대 동작 이후 다시 도착하면 새 상태 요청으로 처리한다.
acting/viewer 번치는 클라이언트가 선택할 수 없으며 서버 current를 사용한다.
응답의 `currentBunchId`를 통해 클라이언트는 번치 전환 이전 응답을 구분할 수 있다.

```json
{"success":true,"data":{"bundleId":123,"currentBunchId":45,"likedByCurrentBunch":true,"likeCount":3},"error":null}
```

- current의 ACTIVE 멤버는 OWNER/CO_OWNER/MEMBER 모두 설정·취소할 수 있다.
  누른 개인은 외부에 노출하지 않고, 다른 멤버도 취소할 수 있다.
- 우리 번들의 멤버 전용 발행본에도 상세 접근이 허용되면 가능하다.
  피드의 모든 ACTIVE 소속 제외는 적용하지 않는다. 다른 소속의 권한은 current에 빌려주지 않는다.
- 가입 완료·활성 계정의 소속 0개 GET은 허용하며 `currentBunchId:null`,
  `likedByCurrentBunch:false`다. PUT/DELETE는 `409 BUNCH_010`이다.
- 공통 접근 검사는 반복 요청·취소에도 매번 적용한다. 숨김·삭제·미발행·외부 멤버 전용은
  `404 RESOURCE_001`, 양방향 차단은 `404 BUNCH_009`, 비공개 번치 미승인은 `403 AUTH_002`다.
  가입·세션·입력 오류와 AccessNotice는 기존 공통 계약을 따른다.

## 집계 서비스와 소유 경계

`SocialLikeService.summaries(userId: Long, bundleIds: Collection<Long>)`는
`Map<Long, SocialLikeSummary(likeCount: Long, likedByCurrentBunch: Boolean)>`를 반환한다.
C의 상세 및 B의 카드 조립에서 한 번 호출한다. 입력은 최대 500개·양수 ID이며 빈 입력도
계정 자격을 검사한 후 빈 map이다. 접근 불가능한 번들은 map에서 제외하고, 접근 가능한
좋아요 0개 번들은 0/false를 채운다. 상세 접근 오류가 필요한 단건 API는 `summary`를 사용한다.

집계는 DB GROUP BY와 A의 `VISIBLE_BUNCH` 조건을 재사용하므로 반응한 번치 자체의
양방향 차단·프로필 신고·번치 운영/신고 숨김을 count 이전에 제외한다.
이는 `visibleBunchIds`와 같은 정책이며 반응한 비공개 번치의 승인 팔로우는 요구하지 않는다.
대상 번들 자체의 접근 검사와 별개다. active=true만 센다. 요청 간 결과를 캐시하지 않는다.
조회는 REPEATABLE_READ로 current·접근 후보·집계를 같은 snapshot에서 읽는다.
모든 좋아요 행이나 작성 번치를 메모리로 읽어 필터링하지 않으므로 반응 수에 비례한 N+1은 없다.

D는 기존 C 소유 BundleService/BundleAccessService·DTO 조립을 수정하지 않는다.
NotificationService.open/PushWorker의 A 접근 정책 연결은 부모 통합 소유다.

## 저장·동시성·알림

V24 `social_bundle_likes`는 bigint identity ID, bunch/bundle FK CASCADE,
`active`, `first_liked_at`, `updated_at`과 `(bunch_id,bundle_id)` UNIQUE를 갖는다.
활성 집계용 `(bundle_id,bunch_id) WHERE active` 인덱스를 둔다.
취소는 active만 false로 바꾸며 행과 최초 시각을 보존한다. 최초 이력을 알림 30일 보관이나
push job 보관에 의존하지 않는다. 번들 영구 삭제와 행동 번치 삭제는 FK로 행을 정리한다.
발행본 내용 수정은 좋아요를 바꾸지 않는다.

변경은 회원 잠금 → current/대상 번치 ID 오름차순 잠금 → 번들 잠금 순이다.
대기 후 번치·소속을 다시 읽고 `requireInteraction`으로 현재 상세 접근을 재검사한다.
사전 선택한 current와 달라졌으면 다른 번치로 쓰기를 바꾸지 않고 거부한다.
같은 번치의 멤버·서로 다른 번치의 반응과 차단·공개 범위·발행본 변경은 이 잠금 경계에서
직렬화하며 DB UNIQUE도 중복 행을 막는다. 별도 Redis 카운터나 분산 락은 없다.

최초 행 생성에만 `NotificationWriter.bundleLiked`를 호출한다. 행동 번치와 소유 번치가 같으면
알림이 없다. 수신자는 발행 참여자 중 현재 ACTIVE 소속·활성 계정이며 행동자는 제외한다.
제목은 「{행동 번치 이름} 번치가 우리 번들을 좋아해요」, 본문은 당시 번들 제목이다.
`BUNDLE_LIKED:{행동 번치 ID}:{번들 ID}`를 dedupe key로 사용한다.
target은 BUNDLE, bunchId/targetBunchId는 소유 번치, sourceBunchId는 행동 번치다.
이 source는 알림 발생 후 차단 등의 late access 재검사에 사용한다.

업무·inbox·현재 기기의 실제 PushJobWriter job은 같은 DB 트랜잭션에 저장하고
어느 저장이든 실패하면 좋아요 최초 이력까지 롤백한다. 개인 푸시 설정은 inbox를 막지 않는다.
기기 없음·권한 없음·푸시 설정 off 시 job을 만들지 않고 이후 재시도·재좋아요에 소급 생성하지 않는다.
취소·재좋아요에는 최초 수신자가 달라져도 알림을 새로 만들지 않는다.
정확히 한 번의 APNs 최종 표시 보장은 기존 푸시 계약 범위 밖이다.

## 검증 기준

실제 PostgreSQL/Redis에서 역할별 공동 상태·다른 멤버 취소·서버 current·소속 0개,
가입·양방향 차단·공개/승인·신고/운영 숨김·배치 집계·동시 PUT/취소,
참여자 수신 범위·기기별 job·설정·원자 롤백·재알림 금지·FK 정리와 migration 재실행을 검증한다.
기존 공통·알림·번들 회귀는 전체 clean build로 검증한다.
실제 iOS/WebView·APNs 수신/복귀는 수동 검증 대상으로 남는다.
