# 번치 DB·트랜잭션 계약

2026-10-29 [제품 정책](bunch.md)·[API](bunch-api.md)의 **미구현 목표 설계**다.
같은 날 YAGNI 정리에 따라 기존 기능 재사용과 최소 저장 구조로 줄였다. 기능 코드·migration은 추가하지 않았다.
현재 단일 서버를 기준으로 하며 새 의존성·브로커·범용 이벤트 계층을 도입하지 않는다.

## 1. 기존 기반 재사용

- dev `159937b6`의 V5 이미지/Photo 기반과 PR #32의 번들·lease를 재사용한다.
  #32 병합 뒤 최신 dev의 다음 빈 Flyway 번호를 사용하고 적용된 migration은 수정하지 않는다.
- 번치·소속·관계는 bunch, 초안·발행본은 bundle, 업로더/파일은 photo·image,
  알림·푸시는 notification이 소유한다. global 기술 코드가 기능 DTO에 의존하지 않게 한다.
- 새 bigint PK는 identity, 시각은 timestamptz(UTC), enum은 varchar+CHECK다.
  nullable 표기 없는 필드는 NOT NULL이다. 테이블은 해당 기능 구현 때만 만든다.

## 2. 기존 번치·소속 확장

| 대상 | 변경 |
| --- | --- |
| bunches | `profile_version bigint DEFAULT 0`, `membership_version bigint DEFAULT 0`, 모두 CHECK >=0 |
| bunch_memberships.role | OWNER / CO_OWNER / MEMBER. 활성 OWNER는 메인 관리자 1명 |
| bunch_memberships | `ended_at timestamptz nullable`, `role_granted_by bigint nullable`(members FK SET NULL), `last_activity_at timestamptz nullable` |

기존 이름·생성 키·활성 소속 유일성·최근 사용 인덱스와 이미지 자산 FK를 유지한다.
profile_version은 프로필 실제 변경, membership_version은 참여·이탈·역할 변경 때만 증가한다.
성공 재생·동일 값 저장·last-used 변경은 증가시키지 않는다. Stage와 각종 개수는 저장하지 않는다.
Stage는 내부·외부 모두 PUBLIC/PRIVATE 전체 발행 수에서 같은 기준으로 계산한다. 외부 표시용 저장값은 만들지 않는다.
LEFT·REMOVED 이력은 종료 상태로 두고 유효 초대에 의한 재참여마다 새 Membership을 MEMBER로 생성한다.
회원→번치 잠금 아래 현재 활성 소속·정원·소속 한도를 확인하고 joined_at은 이번 참여 시각으로 기록한다.
발행본의 기존 member_id 기여·코멘트는 유지하고 현재 활성 소속과 개인 프로필을 조회해 표시를 복구한다.
기여 행·파일을 복제하거나 작성자 복원용 snapshot/컬럼·별도 복구 원장을 만들지 않는다.
삭제된 Photo·업로더 연결·초안·초안 코멘트·편집권과 이전 관리자 역할은 복구하지 않는다.

메인 중복은 아래 부분 유일 인덱스, 0명 방지는 기존 번치 잠금·서비스 트랜잭션으로 보장한다.

```sql
CREATE UNIQUE INDEX uk_bunch_active_owner
    ON bunch_memberships (bunch_id)
    WHERE status = 'ACTIVE' AND role = 'OWNER';
```

인계는 기존 OWNER를 MEMBER로 바꾼 뒤 후임을 OWNER로 만들고 함께 커밋한다.
역할/소속 변경의 모든 경로가 이 규칙을 공유하며 별도 DB trigger는 만들지 않는다.
last_activity_at은 사진 업로드·초안 내용 편집·코멘트 저장 성공 때만 갱신한다.
조회·last-used·presence·heartbeat는 제외한다. 계정 탈퇴 자동 승계에만
기여 횟수 DESC→활동 DESC NULLS LAST→가입일 ASC→Membership ID ASC를 쓴다.

### 전환 기준

기존 번치의 ACTIVE OWNER가 정확히 1명인지 먼저 검사한다. 0명/복수 OWNER 또는 활성 소속 0개면
migration 전체를 실패시키고 임의 승계하지 않는다. 실제 활동을 last-used로 추정하지 않아 초기 활동은 null이다.
종료 시각 복원이 불가능한 비활성 소속은 migration 시각을 쓰되 안내/알림을 소급 생성하지 않는다.
CO_OWNER 도입은 서버·앱과 함께 전환하며 구버전 서버와 혼합 운영하지 않는다.

## 3. 접근 불가 안내·한정된 요청 재생

### bunch_access_notices

`id bigint PK`, `recipient_member_id` → members CASCADE, `bunch_id bigint`, `membership_id bigint`(FK 없음),
`bunch_name varchar(120)`, `character_type`, `reason` REMOVED / DELETED, `occurred_at`, `expires_at`(발생+30일).
UNIQUE `(recipient_member_id,membership_id,reason)`, 목록 INDEX `(recipient_member_id,occurred_at DESC,id DESC)`,
만료 INDEX `(expires_at,id)`.

본체 삭제와 같은 트랜잭션에서 최소 이름·유형·수신자를 저장한다. 본인 나가기/직접 삭제는 만들지 않는다.
확인·만료·재참여 때 제거한다. 재참여와 해당 번치의 과거 내보내짐 안내 제거는 같은 트랜잭션이다.
번치 FK나 이미지 자산 참조를 두지 않아 파일은 보존하지 않고 기본 이미지를 쓴다.

### bunch_action_requests

프로필 저장·메인 인계·나가기·번치 삭제의 응답 유실 재시도에만 사용한다.
`id bigint PK`, `member_id` → members CASCADE, `bunch_id bigint`, `actor_membership_id bigint`(FK 없음),
`operation` PROFILE / OWNER_TRANSFER / LEAVE / DELETE, `request_key UUID`, `fingerprint char(64)`,
`result jsonb`, `created_at`, `expires_at`(성공+30일).
UNIQUE `(member_id,bunch_id,operation,request_key)`, 만료 INDEX `(expires_at,id)`.

업무와 성공 기록을 함께 커밋하고 완료 ID·버전·outcome만 저장한다. 멤버/파일/토큰 snapshot은 남기지 않는다.
이탈/삭제 후 같은 성공 요청은 최소 결과만 재생하며 새 키는 현재 권한을 검사한다.
기존 생성·참여·번들 요청 기록을 재사용하고 범용 멱등성 프레임워크는 만들지 않는다.
다른 PUT/DELETE·내보내기는 현재 상태·대상 ID·유일성·버전 검사로 반복을 처리한다.

## 4. 번치 간 관계

### bunch_follows — 팔로우와 대기 요청을 한 행으로 관리

`id bigint PK`, `follower_bunch_id`·`followed_bunch_id` → bunches CASCADE,
`status` PENDING / APPROVED, `created_by_member_id` → members RESTRICT,
`approved_by_member_id bigint nullable` → members SET NULL, `created_at`, `approved_at timestamptz nullable`.
UNIQUE `(follower_bunch_id,followed_bunch_id)`, 자기 자신 금지 CHECK,
APPROVED일 때만 approved_at이 존재하는 CHECK.
요청 목록 INDEX `(followed_bunch_id,status,created_at DESC,id DESC)`,
팔로워/팔로잉 목록은 각 방향 FK에 `(status,approved_at DESC,id DESC)` 인덱스를 둔다.

공개 대상은 APPROVED, 비공개는 PENDING으로 만든다. 승인만 상태 변경, 거절·취소는 삭제다.
재요청은 새 ID다. 승인/거절의 이전 ID는 사라졌거나 이미 APPROVED이면 no-op이며 새 요청을 대신 처리하지 않는다.
취소도 followId를 검사해 새 관계를 삭제하지 않는다. 별도 요청 이력 테이블은 만들지 않는다.
공개 전환은 대상 번치 잠금 아래 PENDING을 일괄 승인하고 상대 번치를 뒤늦게 잠그지 않는다.

### bunch_blocks

`id bigint PK`, `blocker_bunch_id`·`blocked_bunch_id` → bunches CASCADE,
`created_by_member_id` → members RESTRICT, `created_at`.
UNIQUE `(blocker_bunch_id,blocked_bunch_id)`, 자기 자신 금지 CHECK,
목록 INDEX `(blocker_bunch_id,created_at DESC,id DESC)`, 역방향 INDEX `(blocked_bunch_id,blocker_bunch_id)`.
차단 방향은 보존하되 접근은 양방향으로 막는다. 두 방향 follows를 함께 삭제하고,
해제는 자신의 blockId만 제거한다. 과거 팔로우·상대 차단·기존 댓글 원문은 변경하지 않는다.

### bunch_profile_reports

`id bigint PK`, `reporter_bunch_id`·`target_bunch_id` → bunches CASCADE,
`reported_by_member_id` → members RESTRICT, `reason`, `detail text nullable`,
`status` RECEIVED / REVIEWING / ACTIONED / DISMISSED, `created_at`, `reviewed_at timestamptz nullable`.
UNIQUE `(reporter_bunch_id,target_bunch_id)`, 자기 자신 금지 CHECK.
재신고는 최초 행을 반환한다. detail 최대 200 grapheme·OTHER 필수는 서비스에서 검사한다.
피드 제외는 신고 존재로 판단하고 운영 처분·감사 API는 별도 운영 기능에서 구현한다.

## 알림 저장 연계

알림·푸시 4개 테이블, 가입 알림 이관, 활동 합산·예약 완료 사실과 worker는 [알림 스키마](notification-schema.md)가 소유한다.
번치 변경·소속 종료·안내와 알림/전송 대기를 같은 트랜잭션에서 처리하고 외부 APNs 호출은 커밋 뒤 수행한다.
접근 불가 안내·요청 성공 기록은 이 문서의 번치 소유 구조로 유지한다.

## 5. 원자성·잠금·파일 정리

- 현재 회원→번치→bundle/Photo 순서를 재사용하고 같은 종류는 ID 오름차순으로 잠근다.
  내보내기는 actor/target 회원을 먼저 정렬한다. 두 번치 관계 변경은 번치 ID 순서다.
  공개 전환 일괄 승인은 대상 번치만 잡고 상대 번치를 뒤늦게 잠그지 않는다.
- 나가기/내보내기는 #32 `BundleService.memberDeparted`와 #38 업로더 정리를 같은 트랜잭션에서 호출한다.
  생성자 초안은 삭제, 다른 초안의 대상 참여/코멘트/lease는 제거한다. 마지막 업로더 없는 Photo와 사진 0개 초안은 삭제한다.
  발행 이미지·기여·코멘트 본문은 유지한다. 실패하면 소속·후임·초대까지 전체 롤백한다.
- 내보내기는 초대 링크·코드를 함께 교체한다. 과거 참여 성공 재생이 종료 소속을 복원하지 않게 한다.
- 번치 삭제 전에 자산 ID·연결·수신자를 수집하고 하위 기록·양쪽 관계를 제거한다.
  안내·성공 기록·push_jobs는 독립해 남긴다. 공유 파일은 기존 restrictive FK와
  `ImageAssetService.releaseAfterCommit`으로 보호한다. cascade가 callback·socket 정리를 실행한다고 가정하지 않는다.
- 커밋 후 socket·파일을 정리한다. 지연 중에도 다음 요청의 권한 검증은 거부한다.
  외부 업로드/삭제·APNs·socket 전송을 DB 잠금 안에서 수행하지 않으며 외부 실패가 성공 업무를 되돌리지 않는다.
- 프로필은 사전 검사→잠금 밖 업로드→번치 잠금 아래 재검증·참조 교체다. 실패한 새 파일은 기존 방식으로 보상한다.
  이탈 후 도착지 last-used는 잠근 본인 회원 아래 남은 소속을 골라 갱신하고 도착지 번치를 추가 잠금하지 않는다.
  계정 탈퇴의 여러 번치 정리도 전체 잠금 대상을 먼저 정렬한다.

## 6. 구현·검증

#32 병합→역할/Stage→홈/프로필→#25·#38 이탈/삭제→소셜→알림·푸시 순으로 연결한다.
알림 생산자에는 필요한 저장 기반을 먼저 포함해 업무와 전송 대기 사이 유실을 막는다.
전체를 한 번에 구현하거나 미래 기능의 빈 테이블을 먼저 만들지 않는다.

| 검증 | 기준 |
| --- | --- |
| migration/JPA | 적용·재실행·validate, checksum/기존 데이터 보존, 잘못된 OWNER 수이면 전체 실패 |
| 권한·승계 | DB 중복 OWNER 거부, 동시 서비스 요청의 메인 1명 유지, 후임 없는 나가기 거부 |
| 이탈·이미지 | 정리 실패 전체 롤백, 재참여 Membership 보호, 발행/공유 파일 보존 |
| 재참여 복구 | LEFT/REMOVED→새 MEMBER, 발행 기여/코멘트의 현재 프로필 표시·중복 없음, 삭제 행/파일/역할 비복구, 과거 성공 요청으로 복원 금지, 안내/job 원자 정리 |
| 관계 | 단일 PENDING/APPROVED, 오래된 요청/취소 ID의 새 관계 변경 금지, 차단·공개 전환 경합 |

기능 구현 때 실제 PostgreSQL/Redis·전체 build를 검증한다. 알림/전송의 모의 APNs·실제 기기 검증은
[알림 스키마](notification-schema.md#3-검증)를 따른다. WebView·앱 연동은 별도 수동 증거다.
