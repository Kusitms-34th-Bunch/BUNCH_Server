# 번치 팔로우·차단·신고·외부 조회 계약 (#51)

2026-10-31 승인한 번치 계약의 5.10·5.11과 API·스키마 6·4절을 실제 기능·테스트와 함께 반영한다.
별도 문서 PR #56을 선행 조건으로 삼거나 전체 번치·알림 Spec을 복제하지 않는다.
역할·Stage는 #55, 실제 알림 저장은 #57의 `39af8b0`과 읽음 정밀도 수정 `8fbf45a`를 병합해 재사용한다.

## 공통 입력·권한

기본 경로는 `/api/v1/bunches`다. 기존 인증·응답 envelope를 재사용하며 활성 계정·가입 완료·
활성 Redis 세션을 요구한다. 성공은 200이고 데이터 없는 성공은 `data:null`이다.
ID는 양의 정수, nullable 필드는 JSON null, 시각은 UTC ISO-8601이다.

읽기 요청은 인증 회원의 서버 current Bunch를 사용하며 `viewerBunchId` query로 이를 덮어쓰지 않는다.
같은 개인이 대상에도 속해 있어도 대상이 서버 current가 아니면 `EXTERNAL` 정책을 적용한다.
팔로우·승인·거절·신고·차단·해제 같은 명의 행동은 기존 경로의 `viewerId`를 actor Bunch로
사용하며, 읽기 query를 actor 전환 수단으로 사용하지 않는다. actor=target의 행동은 400이다.
팔로우·승인·거절·신고는 모든 활성 멤버, 차단·해제는 OWNER/CO_OWNER만 가능하다.
차단 목록은 모든 활성 멤버가 볼 수 있다. 변경은 회원→번치 ID 오름차순 잠금 아래 소속·역할을 재확인한다.
잠금 전 읽었던 Membership 역할을 기다린 뒤 그대로 신뢰하지 않는다.

페이지는 `page=0,size=20`, size 1~50이다. 음수 page·잘못된 size·JPA offset 상한 초과는 400이다.
형태는 `{items,page,size,totalElements,hasNext}`이며 목록·합계·Stage·current 상태는 동일 snapshot에서 읽는다.

| HTTP/code | 의미 |
| --- | --- |
| 400 COMMON_001 | 형식·페이지·자기 명의 행동·신고 상세 길이 오류 |
| 403 AUTH_015 | 가입 미완료 |
| 403 AUTH_002 | 현재 명의·역할 거부·비공개 미승인 콘텐츠 목록 |
| 404 RESOURCE_001 | 다른 번치의 requestId·번들 또는 비공개/초안 번들 상세 |
| 404 BUNCH_009 | 없는 외부 대상·양방향 차단. 차단 방향은 공개하지 않는다 |
| 409 BUNCH_010 | 활성 소속 0개라 현재 명의가 필요한 행동을 수행할 수 없음 |

## 팔로우·차단·신고 API

| 메서드·경로 | 입력 | data |
| --- | --- | --- |
| PUT /{viewerId}/following/{targetId} | 없음 | `{followState,followId}` |
| DELETE /{viewerId}/following/{targetId} | 필수 query `followId` | `{followState,followId,cancelled}` |
| GET /{viewerId}/follow-requests | `page,size` | `{id,bunch,requestedAt}` 페이지 |
| POST /{viewerId}/follow-requests/{requestId}/decision | `{decision:APPROVE 또는 REJECT}` | `{requestId,applied,state}` |
| GET /{targetId}/followers | `page,size` | 서버 current 기준 RelationItem 페이지 |
| GET /{targetId}/following | `page,size` | 서버 current 기준 RelationItem 페이지 |
| PUT /{viewerId}/blocks/{targetId} | 없음 | null |
| DELETE /{viewerId}/blocks/{targetId} | 필수 query `blockId` | null |
| GET /{viewerId}/blocks | `page,size` | `{id,bunch,blockedAt}` 페이지 |
| POST /{viewerId}/reports | `{targetBunchId,reason,detail?}` | `{reportId}` |

- `bunch`는 `id,name,imageUrl,characterType,visibility`다.
  RelationItem은 `{bunch,followState,followId,followedAt}`이며 상태는 **서버 current Bunch→행 번치** 기준이다.
  current가 없거나 자기 current 행이면 NONE/null이다. 관계는 승인 시각 DESC,id DESC, 요청·차단은 생성 시각 DESC,id DESC다.
- 공개 대상은 즉시 APPROVED/FOLLOWING, 비공개는 PENDING/REQUESTED다. 기존 PUT 재실행은 같은 ID다.
  승인만 상태를 바꾸고 거절·취소는 삭제한다. 재요청은 새 ID다. 별도 과거 요청 원장은 없다.
- 이미 승인된 requestId의 늦은 거절은 `applied:false,state:APPROVED`, 사라진 ID는
  `applied:false,state:UNAVAILABLE`이다. 첫 성공은 APPROVED/REJECTED와 applied=true다.
  잠금 전 좌표만 조회하고 잠금 후 행 상태를 읽어 오래된 JPA 상태가 새 요청을 변경하지 않게 한다.
- DELETE는 현재 followId와 같을 때만 삭제한다. 대기 중 승인됐다면 cancelled=FOLLOWING이다.
  다른 ID이거나 차단으로 삭제됐다면 현재 상태·ID와 cancelled=NONE을 반환한다.
- 차단은 방향별 한 행이지만 접근 제한은 양방향이다. 양쪽 팔로우·요청 제거와 함께 커밋한다.
  해제는 현재 자기 blockId만 삭제하고 과거 ID는 no-op이다. 상대 차단이 남으면 계속 비노출이다.
  해제해도 관계를 복구하지 않는다. 차단·해제·신고 알림은 만들지 않는다.
- 차단된 대상의 외부 조회·팔로우를 거부하며, 관계 목록의 각 행도 current와 양방향 차단을 검사한다.
  이 필터를 DB 페이지 조회·count에 같은 조건으로 적용한다. 내부 번치 API의 소속 권한은 별개다.
- reason은 SPAM/INAPPROPRIATE/HARASSMENT/PERSONAL_INFORMATION/OTHER다.
  detail은 trim 후 200 grapheme 이하이며 OTHER는 공백 아닌 1자 이상 필수다.
  그 외 빈 상세는 null이다. 번치 쌍의 재신고는 최초 ID·사유·상세를 유지하며 중복 접수하지 않는다.
  이미 접수된 유효 요청은 이후 어느 쪽이 차단해도 최초 ID를 반환한다.
  신고의 피드 제외는 신고 행 존재로 판정할 계약이며 실제 일반 피드 구현에서 연결한다.
  신고만으로 자동 제재·차단하지 않는다.

## 외부 프로필·발행본 API — 통합 읽기 계약

| 메서드·경로 | 입력 | data |
| --- | --- | --- |
| GET /{targetId}/home | 없음 | `BunchHomeResponse` |
| GET /{targetId}/members | 없음 | `List<BunchMemberResponse>` |
| GET /{targetId}/published-bundles | `page,size` | `BunchSocialPage<BunchPublicBundleCard>` |
| GET /{targetId}/published-bundles/{bundleId} | 없음 | `BunchPublicBundleDetail` |

`/home`은 [홈·프로필 계약](bunch-home-profile.md)의 `BunchHomeResponse`를 반환하는
유일한 프로필 조회 경로다. `accessScope`는 서버 current가 target의 ACTIVE 소속일 때만
`INTERNAL`, 그 외(대상에 본인 ACTIVE 소속이 있어도 current가 아니면 포함)는 `EXTERNAL`이다.
`viewerBunchId`는 API 입력 계약에 없으며 query로 현재 선택을 덮어쓸 수 없다.

`INTERNAL`이면 PUBLISHED 상태의 PUBLIC/PRIVATE 발행본을 모두 조회하고, `EXTERNAL`이면
접근 flag가 허용하는 PUBLIC PUBLISHED만 조회한다. Stage는 두 범위 모두 전체 PUBLISHED
개수의 0/2/6/10 기준을 사용하지만 외부 `publishedCount`는 `null`이다.

`/members`는 `{memberId,nickname,imageUrl,role}`만 가진 `BunchMemberResponse` 배열을
직접 반환한다. `role`은 `OWNER→CO_OWNER→MEMBER`를 유지하고 메인/공동 관리자 배지는 [PRD 정렬 커밋 `d898c21`](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/commit/d898c21691201c1768f5f90e9e336d4cadd369e1)에
기록된 2026-10-29 제품 승인 근거를 따른다. `memberId`는 목록 연결용 기술 ID 예외이며,
출생연도·성별·가입일·계정 상태·세션·관리 action·개인 프로필 이동·사진함·초안·PRIVATE 발행본·
편집권은 반환하지 않는다.

PublicBundleCard는 `{id,bunchId,title,coverImageUrl,date,location,publishedAt}`다.
페이지는 `{items,page,size,totalElements,hasNext}`이며 `publishedAt DESC,id DESC`로 조회한다.
현재 내부 번들 모델에는 위치 저장이 없어 location은 null이며 EXIF 좌표를 추정해서 반환하지 않는다.
기존 nullable 필드(`bunch.imageUrl`, `coverImageUrl`, `date`, `location`, `publishedAt`,
contributor/comment의 `nickname`·`imageUrl`·`content`)는 외부에서도 JSON `null`을 유지한다.
nullable 객체 DTO는 `location:null`을 보존하기 위해 유지한다. null 전용 Kotlin 타입은 이 필드를 생략한다.
상세는 카드 필드에 photos `{id,imageUrl,position,caption}`, contributors `{memberId,nickname,imageUrl}`,
comments `{id,memberId,nickname,imageUrl,content,updatedAt}`를 추가한다.
상세도 목록과 같은 서버 current·차단·승인·공개 범위를 다시 확인한다.
나간 회원은 「나간 멤버」로 표시하며 탈퇴 계정 코멘트 원문은 노출하지 않는다.
같은 memberId로 재참여하면 현재 프로필 표시를 복구하고 기여·코멘트를 복제하지 않는다.
운영 숨김/제재 기능·소셜 댓글/좋아요 쓰기는 이 구현 범위 밖이다.

기존 `public-*` 조회 경로와 멤버 wrapper는 제공하지 않는다. 제거한 경로는 인증 후에도 404다.

## 저장·알림·통합 조건

V14는 실제 `bunch_follows,bunch_blocks,bunch_profile_reports` 세 테이블만 추가한다.
각 방향 번치 쌍 UNIQUE·자기 관계 금지, 번치 FK CASCADE·행동 회원 FK RESTRICT를 적용한다.
팔로우는 PENDING의 approved_at 없음/APPROVED의 approved_at 필수이며 승인 회원 FK는 SET NULL이다.
신고 상태 RECEIVED/REVIEWING/ACTIONED/DISMISSED·검토 시각은 승인 스키마 계약이다.
운영 심사 API나 미래 알림 테이블을 추가하지 않는다.

기존 팔로우 V13은 이슈 전용 테스트 DB에만 적용됐고 dev/main·배포 실행·확인한 공용 로컬 DB에
적용되지 않았음을 확인한 뒤 별도 일반 커밋 `97d3797`로 V14로 이동했다. #28의 V13은 수정하지 않았다.
구 V13 전용 DB를 수정/repair하지 않고 새 `bunch_issue51_v14_test`에서 V11→V12→V13→V14를 검증한다.
V15(#48)·V16(#49)·V17(#25) 예약을 침범하지 않는다.

실제 NotificationWriter를 새 관계·수동 승인 업무 트랜잭션에서 호출한다.
공개 즉시 팔로우 성립은17, 새 PENDING은18, 수동 승인 성립은17과19다.
17/18은 대상 번치,19는 요청 번치의 발생 시점 유효 멤버가 수신하고 직접 행동 회원은 제외한다.
문구는 승인 알림 원문을 쓰고 개인 실행자 정보는 외부 알림에 넣지 않는다.
관계 ID·종류·수신 회원으로 중복을 방지하며 재실행/no-op에는 저장하지 않는다.
알림 저장 실패 시 관계 생성·승인도 롤백한다. open은 현재 소속·대상 존재·양방향 차단을 다시 확인하며
사용할 수 없으면 읽음을 유지하고 `available:false`를 반환한다.

- #48은 현재 기기별 push job을 같은 업무/알림 트랜잭션에 저장하도록 연결해야 한다.
  이 브랜치는 가짜 전송 큐·adapter를 만들지 않으며 실제 APNs 전달 완료를 주장하지 않는다.
- #52가 실제 프로필 변경을 구현할 때 PUBLIC 전환·유효 대기 일괄 승인·17/19를 같은 트랜잭션으로 묶는다.
  대상 번치만 잠그고 상대 번치를 뒤늦게 잠그지 않는다. 호출 없는 자동 승인 함수를 선구현하지 않는다.
  그 실제 호출부와 함께 공개 전환/수동 승인/취소/차단 경합·전체 롤백을 검증해야 한다.
- #50의 실제 삭제 흐름에서는 FK 정리와 알림/job 보존 정책을 함께 통합 검증한다.
  이 브랜치의 CASCADE 테스트만으로 전체 삭제 흐름 완료로 보지 않는다.

## 검증

실제 PostgreSQL·Redis 전용 환경에서 API 인증·권한·이전 ID·정렬/페이지·비공개/차단 비노출,
중복 요청/신고·반대 방향 요청·승인/취소/차단 경합, 역할 강등 대기 재검사, 알림 수신/중복/롤백,
외부 전용 DTO·재참여 프로필·상세 재검사를 검증한다.
마이그레이션은 V10 데이터를 보존하며 V11→V12→V13→V14 적용·재실행·제약·JPA validate를 확인한다.
최종 명령과 결과는 PR·완료 보고에 남긴다. 별도 lint·정적 분석 도구는 미구성이다.
실제 iOS/WebView·APNs와 #52/#48/#50의 남은 통합은 자동 테스트와 구분한다.
