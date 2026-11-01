# #28 알림 공통 기반 1차 구현 경계

기준은 origin/dev `b2b8e71dc12d82c73e68c72fca2690a74db5a538`와 2026-11-01 제공된
고정 승인 사본이다. 사본 manifest의 SHA256을 검증했다. 알림 정책은 [Spec](notification.md),
입출력은 [API](notification-api.md), 저장·전송 목표는 [스키마](notification-schema.md)를 따른다.
타 도메인 계약 전체 정렬과 #28 전체 완료는 이 1차 작업의 완료와 구분한다.

## 구현된 범위

- V12는 V3 `bunch_join_notifications`를 `notifications`로 이름 변경·확장한다. 남은 30일 행의
  ID/발생 시각·identity sequence를 유지하고 `NEW_MEMBER`를 `MEMBER_JOINED`로 바꾼다.
  이관 시의 개인 닉네임으로 문구를 고정한다. 과거 푸시·이중 알림 저장은 없다.
- InvitationService의 실제 Membership 생성 트랜잭션에서 NotificationWriter를 직접 호출한다.
  기존 회원/번치 잠금·참여 요청 성공 재생을 유지한다. 새 참여 회차당 기존 활성 멤버에게만 저장하고
  행동자는 제외한다. 이미 참여·성공 요청 재생에는 호출하지 않는다. 알림 쓰기 실패 시 참여도 롤백한다.
- 현재 개인/번치의 목록·개별 읽음·모두 읽음·open 4개 API를 제공한다. 보관 범위는
  `created_at > now - 30 days`이고 정확히 30일 지난 행부터 만료다. 정리 전에도 동일 필터를 적용한다.
  목록은 SQL에서 최신 100개로 제한하고 그 안에서 페이지를 제공한다. 미확인 수는 같은 보관 범위 전체다.
- 모두 읽음은 미확인 보관 행에 대한 한 UPDATE다. 병합과는 DB 행 잠금/갱신 순서를 따르며
  새 삽입/뒤이은 활동은 미확인으로 남는다. 반복 개별 읽음/open은 최초 readAt을 보존한다.
- open은 본인의 보관 알림인지 확인하고 현재 소속·번들 소속/상태·초안 참여 여부를 재검사한다.
  볼 수 없으면 읽음을 유지한 200 `available:false,target:null`이다. 알림 payload를 접근 권한으로 쓰지 않는다.
  BUNCH_HOME/MEMBERS/ROLE_PERMISSIONS/DRAFT_LIST/CANDIDATES는 현재 소속을 확인한다.
  OTHER_BUNCH_PROFILE은 #51의 실제 차단/외부 접근 검사 연결 전까지 이동을 허용하지 않는다.
  ACCESS_NOTICE는 앱 내 알림 대상이 아니며 14/15 알림 행은 DB CHECK로 거부한다.
- 만료 행 정리는 NotificationRetention이 주기 실행하며, 기존 앱 스케줄링 설정을 따른다.
  테스트는 스케줄러 대신 주입한 Clock과 직접 실행으로 검증한다.

## 활동 합산과 예약 조회 계약

`NotificationWriter.draftActivity`는 실제 사진 추가 변경 후 **같은 트랜잭션에서 한 번만** 호출하는
도메인 진입점이다. 이 1차에서는 BundleService에 연결하지 않는다. 독립 이벤트 발행 API나 가짜 생산자는 없다.
요청 재생 판단은 번들 생산자의 기존 성공 기록이 소유한다. 이미 적용한 mutation을 다시 호출해 합산하지 않는다.
`mutationKey`는 그 실제 변경의 식별자이며 첫 행의 dedupe key를 만든다. 병합 이벤트를 별도 원장에 기록하지 않는다.

기존 번치→번들 잠금을 재사용한 뒤 해당 수신자/초안의 열려 있는 notification 한 행을 잠근다.
수신자는 현재 활성 소속이면서 참여 중인 다른 회원이다. 첫 발생부터 `group_until = created_at + 10분`이며
`now < group_until`에만 병합한다. 창을 연장하지 않고 ID/title/created_at을 유지하며 body/activity_data를 갱신하고
read_at을 비운다. 사진 수와 구별된 행동자 snapshot을 저장한다. JSONB object key 순서와 무관하도록
activity_data.firstActorId로 처음 표시할 행동자를 유지한다. 앱 내 행은 즉시 조회된다.

`group_until`은 해당 활동 행의 푸시 earliest-available 시각이다. #48은 현재 기기별 미전송 push_jobs와
같은 행을 묶어 이 시각 이후 조회/전송하며, 병합 때 그 job payload를 같은 트랜잭션에서 갱신해야 한다.
notifications만 조회해 이미 전송한 행을 다시 전송하거나 worker가 알림을 재생성/재합산하면 안 된다.
발송 완료/취소 사실은 push_jobs 소유다. 이 1차는 push_jobs나 별도 예약 큐·전송 worker를 만들지 않는다.

COMMENT_REMINDER의 24/72시간 완료 사실은 실제 participant, 최초 발행 가능 사실은 bundle 소유다.
#47 기반 통합 후 그 필드·실제 변경 지점·worker를 연결한다. 이 1차에는 참여/번들 예약 필드와 독촉 worker가 없다.
알림 행에 참여별 독촉 완료 상태를 대신 두지 않는다.

## 후속 통합·출시 조건

| 대상 | 필요한 후속 작업 |
| --- | --- |
| #47 → #28 | V11 역할/버전 기반 통합 후 V11→V12 순서 재검증. BundleService 1~7/9 실제 생산자와 참여별 24/72h·취소·최초 발행 가능을 연결한다. |
| #48 → #28 | 기기/설정/전송 대기와 실제 업무 변경·notifications·현재 기기별 job의 원자 저장을 검증한다. 활동 병합 시 미전송 job 갱신도 포함한다. |
| #49/#25/#50/#51/#52 | 각 실제 업무 트랜잭션에서 역할/이탈/삭제/소셜/공개 범위 생산자를 연결하고 외부 open 접근 검사를 연결한다. |
| #26 및 소셜 쓰기 | 후보·댓글·최초 좋아요 생산자를 실제 업무에 연결한다. enum 등록이 생산자 구현 완료를 의미하지 않는다. |

현재 초기 통합 상태에는 푸시 기기 등록 기능이 없다. 기기가 없는 사건에는 전송 행을 만들지 않으며
나중에 등록한 기기에 과거 알림을 소급 전송하지 않는다. 이 상태의 앱 내 알림은 독립 검증 대상이다.
**기기 등록/전송 설정이 생긴 운영 환경에서는 업무 성공 시 현재 기기별 전송 대기까지 함께 저장하는
#48 연결 없이 이 생산자를 출시하지 않는다.** 임시 async_jobs·비동기 이벤트·fake adapter로 이 경계를 우회하지 않는다.
로컬 검증 성공을 운영 출시 승인이나 #28 전체 완료로 표시하지 않는다.

V11 placeholder는 없으며 V10→V12 숫자 건너뜀을 실제 Flyway로 검증한다. 통합 때 V11→V12를 다시 검증하고
이미 공유 환경에 적용된 migration은 수정하지 않는다. 새 의존성·기존 V1~V10 수정은 없다.

실제 iOS SDK/WebView·알림 화면·푸시 권한/복귀·APNs Sandbox/Production은 앱/키를 이용한 별도 수동 검증이다.
이 1차의 PostgreSQL/Redis·HTTP·모의 시각 자동 테스트가 이를 대체하지 않는다.
