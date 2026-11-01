# 알림 inbox 저장 계약 — #28

> #28은 앱 내 `notifications`와 실제 업무 변경의 원자적 연결을 다룬다.
> APNs protocol, device registration, push preference, retry wire/schema는 이 문서의 범위가 아니며 [이슈 #48](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/48)이 소유한다.

[알림 Spec](notification.md)과 [알림 inbox API](notification-api.md)의 저장 기준이다.
번치·번들 업무의 실제 잠금과 멱등성은 [번들 기능 계약](bundle.md)·[번들 API](bundle-api.md) 및 [역할·Stage 계약](bunch-role-stage.md)을 따른다.

## 1. 트랜잭션 경계

- 실제 업무 변경을 적용한 서비스가 같은 DB 트랜잭션에서 수신자별 `notifications`를 직접 저장한다.
- 업무 서비스가 이미 성공한 요청을 재생하면 저장된 업무 결과만 반환한다. notification writer를 다시 호출하거나 별도 이벤트 원장을 조회하지 않는다.
- 기존 번치·번들 잠금을 먼저 잡고, 활동 합산 때 해당 수신자의 열린 notification 행을 잠근다. 새 행·읽음·합산·취소의 실패는 업무 변경과 함께 롤백한다.
- 이 PR은 `push_jobs`, `push_devices`, `push_preferences` 테이블이나 임시 큐를 추가하지 않는다. 기기가 없는 사건은 앱 inbox만 남기며, 나중에 등록한 기기로 소급 전송하지 않는다.

## 2. `notifications`

V12는 V3 `bunch_join_notifications`를 `notifications`로 rename하고 확장한다. 30일 보관 범위의 기존 ID·`created_at`·identity sequence를 유지하며,
`NEW_MEMBER`를 `MEMBER_JOINED`로 변환하고 이관 당시의 닉네임으로 title/body snapshot을 채운다. 과거 push나 이중 가입 알림은 만들지 않는다.

| 컬럼 | 계약 |
| --- | --- |
| `id bigint` | identity PK |
| `recipient_member_id bigint` | `members` FK, CASCADE; 현재 수신 회원 |
| `bunch_id bigint` | `bunches` FK, CASCADE |
| `type varchar(40)` | 아래 21개 enum; DB CHECK 유지 |
| `dedupe_key varchar(200)` | 수신자별 업무 회차 식별자 |
| `title text`, `body text` | 발생 당시 문구 snapshot |
| `target jsonb` | `kind,bunchId,bundleId?,noticeId?,targetBunchId?` |
| `activity_data jsonb` | nullable; 행위자 snapshot·사진 수·첫 행위자 ID |
| `group_until timestamptz` | nullable; `DRAFT_ACTIVITY`의 고정 10분 종료 시각 |
| `created_at timestamptz` | 발생 시각, UTC |
| `read_at timestamptz` | nullable; 회원별 읽음 시각 |

`UNIQUE (recipient_member_id, dedupe_key)`를 둔다. 목록 인덱스는
`(recipient_member_id, bunch_id, created_at DESC, id DESC)`이며 SQL에서 최신 100개를 제한한 뒤 page를 적용한다.
30일 cutoff는 조회·미확인 집계·open·정리에서 동일하게 사용한다.

### type과 target 호환성

아래 21개 값은 wire/DB 호환성을 위해 유지한다. #28이 실제 행을 생성하는 값은 `1~7`, `9`, `10`뿐이다.
`8`, `11~21`은 producer·fake routing 없이 enum/check/target 저장 호환성만 유지한다. `MEMBER_REMOVED`와 `BUNCH_DELETED`는 앱 inbox 행을 만들지 않는 push-only 호환 값이다.

| 번호 | type | 번호 | type | 번호 | type |
| --- | --- | --- | --- | --- | --- |
| 1 | `DRAFT_PARTICIPANT_ADDED` | 8 | `CANDIDATE_CREATED` | 15 | `BUNCH_DELETED` |
| 2 | `DRAFT_ACTIVITY` | 9 | `CHARACTER_GROWN` | 16 | `BUNCH_VISIBILITY_CHANGED` |
| 3 | `COMMENT_REMINDER` | 10 | `MEMBER_JOINED` | 17 | `BUNCH_FOLLOWED` |
| 4 | `DRAFT_PARTICIPANT_REMOVED` | 11 | `CO_OWNER_GRANTED` | 18 | `FOLLOW_REQUESTED` |
| 5 | `DRAFT_READY_TO_PUBLISH` | 12 | `CO_OWNER_REVOKED` | 19 | `FOLLOW_REQUEST_APPROVED` |
| 6 | `DRAFT_PARTICIPANTS_INSUFFICIENT` | 13 | `OWNER_TRANSFERRED` | 20 | `SOCIAL_COMMENT_CREATED` |
| 7 | `BUNDLE_PUBLISHED` | 14 | `MEMBER_REMOVED` | 21 | `BUNDLE_LIKED` |

저장 호환성을 유지하는 target kind는 `BUNCH_HOME`, `MEMBERS`, `ROLE_PERMISSIONS`, `DRAFT`, `DRAFT_LIST`,
`BUNDLE`, `BUNDLE_COMMENTS`, `CANDIDATES`, `OTHER_BUNCH_PROFILE`, `ACCESS_NOTICE`다.
외부 번치 관계와 접근 안내의 producer는 이 PR에서 만들지 않으며, 저장된 target JSON을 접근 증거로 사용하지 않는다.

## 3. `DRAFT_ACTIVITY` 합산 저장

실제 사진 추가 성공 뒤 `BundleService`가 한 트랜잭션에서 한 번 호출한다. `mutationKey`는 기존 BundleService 성공 기록의 실제 요청 식별자이며,
`DRAFT_ACTIVITY:{bundleId}:{mutationKey}`가 dedupe key가 된다. 원래 요청의 replay는 BundleService가 반환하고 activity를 새로 만들지 않는다.

같은 수신자·초안의 열린 행을 잠근 뒤 다음 규칙을 적용한다.

- 첫 행은 `group_until = created_at + 10 minutes`로 저장한다. 창은 연장하지 않는다.
- `now < group_until`인 경우에만 기존 행의 `body`, `activity_data`, `read_at = null`을 갱신한다. `id`, `title`, `created_at`은 유지한다.
- `activity_data`에는 행위자별 닉네임 snapshot, 누적 `photoCount`, `firstActorId`를 저장한다. JSONB object key 순서에 의존하지 않는다.
- 창 종료 후 활동은 새 행이다. worker가 notification을 재생성·재합산하지 않으며 별도 합산 원장을 두지 않는다.

## 4. V13 bundle/participant 상태

#55의 역할 기반은 V11 역할·버전 migration을 제공한다. 기존 V12 `notifications` migration은 수정하지 않고,
이 PR의 새 V13 migration만 아래 상태를 추가한다. V11→V12→V13 순서를 지키며 이미 적용된 V11/V12 파일을 고치지 않는다.

| 테이블 | 컬럼 | 의미 |
| --- | --- | --- |
| `bundles` | `ready_notified_at timestamptz nullable` | 해당 초안의 최초 발행 가능 알림을 기록하는 사실. 한 번만 설정한다. |
| `bundle_participants` | `reminder24_notified_at timestamptz nullable` | 참여 후 24시간 리마인드 저장 사실 |
| `bundle_participants` | `reminder72_notified_at timestamptz nullable` | 참여 후 72시간 리마인드 저장 사실 |
| `bundle_participants` | `reminders_cancelled_at timestamptz nullable` | 명시적으로 취소한 리마인드 상태. 현재는 코멘트 작성 시 기록하며, 참여 행 삭제·발행/삭제 상태·번치 이탈은 worker eligibility로 막는다. |

V13은 이미 내용이 있는 `bundle_comments` 참여자의 `reminders_cancelled_at`을 채워 기존 완료 상태를 보존하고,
빈 코멘트나 코멘트가 없는 참여자에는 cancellation timestamp를 쓰지 않는다. 취소되지 않은 참여 행을 위한 due 조회 인덱스를 추가하며,
별도 예약 테이블·큐·알림 행의 reminder 상태는 만들지 않는다. DB column은 `reminder24_notified_at`/`reminder72_notified_at`,
도메인 property는 `reminder24SentAt`/`reminder72SentAt`이다.

### reminder worker 원자성

- worker는 `bundle_participants.joined_at` 기준으로 24/72시간 후보를 조회하고, 현재 `DRAFT`, 활성 계정·활성 번치 Membership, 미작성 코멘트, 취소되지 않은 상태를 확인한다.
- 전송 시각이 늦어도 기존 번치→bundle 순서로 잠근 뒤 참여 행을 다시 읽어 **실행 직전에** 같은 조건을 다시 검사한다. 조건을 잃었으면 `COMMENT_REMINDER`를 저장하지 않는다.
- 유효한 24/72 시점에는 해당 `*_notified_at`과 notification 행을 같은 트랜잭션에서 기록한다. 각 시점은 참여 회차별 최대 한 번이다.
- 코멘트 작성은 `reminders_cancelled_at`을 같은 업무 트랜잭션에서 기록한다. 참여 행 삭제는 상태를 함께 제거하고,
  발행·초안 삭제·번치 이탈은 현재 worker eligibility에서 제외한다. 모든 참여 행에 cancellation timestamp를 물리적으로 기록하는 계약은 아니다.

### 최초 ready와 Stage

`publishBlockers`가 비어지는 실제 전이에서 bundle lock 아래 `ready_notified_at`이 null인지 확인하고 type 5와 함께 설정한다.
조건이 깨졌다가 다시 충족되어도 두 번째 type 5를 만들지 않는다.

발행은 type 7을 저장한다. 발행 전후 `BundleStagePolicy`의 실제 Stage가 상승한 경우에만 type 9를 저장하며,
삭제에 따른 하락·기준 설정 변경·기존 데이터 이관은 type 9를 만들지 않는다.

## 5. 보관·읽음·#48 출시 게이트

Retention은 `created_at <= clock.instant() - 30 days`인 행을 주기 삭제한다. 정리 전에도 같은 cutoff로 목록·unread·open을 제한한다.
`read-all`은 해당 cutoff 안의 미확인 행을 한 UPDATE로 처리하고, 동시 활동 합산은 DB 행 잠금·갱신 순서를 따른다.

기기별 job이 필요한 운영 연결은 [이슈 #48](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/48)에서 소유한다. #28 producer의 출시 조건은 다음과 같다.

- 실제 업무 변경·`notifications`·현재 바인딩된 모든 기기의 `push_jobs`를 한 DB 트랜잭션에서 원자적으로 저장한다.
- 활동 합산 시 아직 전송되지 않은 현재 기기 job의 payload도 같은 트랜잭션에서 갱신한다. notification만 보고 job을 재생성하지 않는다.
- 당시 기기가 없으면 job을 만들지 않으며, 나중에 등록한 기기로 과거 notification을 소급 전송하지 않는다.

기기 등록·개인 설정·APNs payload/인증·retry 횟수와 오류별 wire 계약은 #48 범위로 남긴다. 앱 inbox의 동작과 실제 iOS 수신·표시는 별도 검증이다.

주기 실행은 기존 `SchedulingConfig`를 사용한다. 기본 운영 설정은 활성화되어 있고,
`async-job.worker.enabled=false`로 전역 스케줄링을 끄면 독촉·보관 정리도 중지된다.
자동 테스트는 이 설정으로 주기를 끄고 `runOnce`/정리 메서드를 직접 실행한다.
