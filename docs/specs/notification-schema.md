# 알림·푸시 DB·전송 계약

[알림 Spec](notification.md)·[알림 API](notification-api.md)의 미구현 목표 설계다.
2026-10-31 번치 스키마에서 알림 소유 구조를 옮겼으며 테이블·컬럼·제약·전송 정책은 유지한다.

## 1. 소유 범위와 연계

- notification은 앱 내 알림·푸시 작업·기기·개인 설정을 소유한다. 기존 가입 알림을 이관해 재사용한다.
- bunch의 소속·역할·접근 불가 안내·삭제 트랜잭션은 [번치 스키마](bunch-schema.md)가 소유한다.
  번치 업무에서 이 계약의 저장을 같은 트랜잭션으로 호출하며 새 범용 이벤트 계층은 만들지 않는다.
- bigint PK는 identity, 시각은 timestamptz(UTC), enum은 varchar+CHECK다. nullable 표기 없는 필드는 NOT NULL이다.
  적용된 Flyway 파일은 수정하지 않고 구현 시 최신 dev의 다음 번호를 쓴다. 별도 의존성·브로커를 추가하지 않는다.
- 기존 회원→번치→bundle/Photo 순서와 같은 종류 ID 오름차순 잠금을 지킨다. 공개 전환은 대상 번치만 잠그고
  상대 번치를 뒤늦게 잡지 않는 [번치 잠금 규칙](bunch-schema.md#5-원자성잠금파일-정리)을 따른다.

## 2. 알림·푸시 — 4개 테이블

업무 서비스가 **같은 트랜잭션에서 수신자별 notifications와 기기별 push_jobs를 직접 저장**한다.
대상은 번치당 최대 8명이며 저장 실패는 업무와 함께 롤백한다. 중간 이벤트·수신 확인·활동 그룹 원장은 만들지 않는다.

### notifications

기존 V3 `bunch_join_notifications`를 이 테이블로 이름 변경·확장해 재사용한다.
기존 ID·발생 시각을 유지하고 NEW_MEMBER를 MEMBER_JOINED로 매핑한다. 남은 30일 범위의 행에
문구·target·dedupe_key를 채운 뒤 기존 가입 전용 컬럼/제약을 제거하고 일반 알림 제약을 적용한다.
과거 이름 snapshot은 없으므로 이관 시 표시 가능한 이름으로 채우고 이후에는 고정한다. 과거 푸시는 만들지 않는다.
같은 기능 PR에서 가입 알림 저장 경로를 전환해 이중 저장을 없앤다.

`id bigint PK`, `recipient_member_id` → members CASCADE, `bunch_id` → bunches CASCADE,
`type`, `dedupe_key varchar(200)`, `title text`, `body text`, `target jsonb`,
`activity_data jsonb nullable`(행위자 snapshot·사진 수), `group_until timestamptz nullable`,
`created_at`, `read_at timestamptz nullable`.
UNIQUE `(recipient_member_id,dedupe_key)`, 목록 INDEX `(recipient_member_id,bunch_id,created_at DESC,id DESC)`.
30일 지난 행은 조회/집계에서 제외하고 주기 삭제한다. 최신 100개까지만 표시한다.

일반 dedupe_key는 종류·업무 대상 ID·변경 회차다. 이미 적용된 업무의 재시도는 생성 코드를 다시 실행하지 않는다.
활동은 같은 수신자·초안의 열려 있는 알림 행에 최초 알림부터 10분간 합산한다.
기존 번치/초안·알림 행 잠금으로 보호하고 ID·제목·created_at은 유지하며 본문 갱신과 read_at=null을 적용한다.
같은 알림의 미전송 push_jobs를 갱신하고 group_until 뒤 발송한다. worker는 알림을 재생성·합산하지 않는다.
모두 읽음은 본인·현재 번치의 보관 알림을 한 UPDATE로 처리한다. 동시 활동과는 행 갱신 순서를 따른다.
UPDATE에 포함되지 않은 새 삽입과 모두 읽음 뒤 병합된 활동은 안 읽음으로 남는다.
이탈자의 해당 번치 알림은 제거한다. 내보내짐·번치 삭제는 앱 내 행 없이 안내·push_jobs만 쓴다.

### push_jobs

`id UUID PK`(apns-id), `device_id bigint nullable` → push_devices SET NULL,
`recipient_member_id` → members CASCADE, `session_id UUID`, `bunch_id bigint`(FK 없음),
`notification_id bigint nullable` → notifications SET NULL, `dedupe_key varchar(200)`, `type`, `payload jsonb`,
`status` PENDING / SENT / FAILED / CANCELLED, `attempts integer DEFAULT 0 CHECK 0~5`,
`available_at`, `expires_at`(발생+24시간), `sent_at timestamptz nullable`, `last_error_code varchar(80) nullable`, `created_at`.
UNIQUE `(recipient_member_id,device_id,dedupe_key)`, 조회 INDEX `(status,available_at,id)`.

삭제/내보내기 전 기기별 payload와 안내 target을 저장해 본체 삭제 뒤에도 전송한다.
기기가 없으면 만들지 않으며 이후 등록으로 소급 발송하지 않는다. worker는 현재 자격·기기 귀속을 확인한다.
14/15번은 당시 수신자에게 소속 없이도 허용한다. 재참여해 사라진 내보내짐 안내의 미전송 job은 같은 트랜잭션에서 취소한다.

### push_devices

`id bigint PK`, `installation_id UUID UNIQUE`, `member_id` → members CASCADE, `session_id UUID`,
`environment` SANDBOX / PRODUCTION, `topic`, `token_ciphertext bytea`, `token_hash char(64)`,
`authorization_status`, `registered_at`.
UNIQUE `(environment,topic,token_hash)`, INDEX `(member_id,session_id)`.
JDK 기능과 서버 비밀 키로 토큰을 암호화하고 원문을 응답·로그에 남기지 않는다.
별도 키 버전·회전 프로토콜을 이 기능에 추가하지 않는다.
같은 설치/토큰의 새 로그인은 귀속을 바꾸고 이전 세션의 미전송 job을 취소한다.
로그아웃·탈퇴·해제는 기기 행과 토큰을 지운다. 별도 기기 버전 없이 member·session·hash·등록 시각으로 검사한다.
오래된 APNs 응답으로 새 등록을 지우지 않도록 **시도 당시 hash·등록 시각과 같은 행만** 무효화한다.

### push_preferences

`member_id bigint PK` → members CASCADE, `enabled boolean DEFAULT true`, `types jsonb`, `updated_at`.
types는 종류별 boolean override이며 행/키가 없으면 true다. PATCH로 받은 종류만 바꾸고 나머지는 유지한다.
동시 PATCH도 받은 키만 DB에서 합쳐 저장해 다른 종류의 설정을 덮어쓰지 않는다.
설정 버전·재시도 원장 없이 마지막 저장을 적용한다. OS 권한과 개인 설정은 별개이며 앱 내 알림은 유지한다.

### 예약·최초 알림은 도메인 상태에 기록

- bundles의 `ready_notified_at nullable`로 최초 발행 가능 알림 1회를 기록한다.
- bundle_participants에 nullable `reminder24_notified_at`, `reminder72_notified_at`, `reminders_cancelled_at`을 둔다.
  주기 작업이 참여+24/72시간·미작성·DRAFT를 조회하고 번들 잠금 아래 재검증한다.
  알림/job과 완료 시각을 함께 커밋한다. 코멘트 작성은 남은 독촉을 취소하고 제외/삭제는 참여 행이 사라진다.
  초안 참여에서 제외된 뒤 다시 추가되는 경우는 새 참여 행이다. 번치 재참여와 구분하며 별도 예약 테이블을 만들지 않는다.
- 좋아요 최초 알림 여부는 해당 관계에 보관하고 취소는 비활성화로 처리해 최초 여부를 유지한다.
  피드 기능 구현 때 추가하며 미구현 좋아요를 위한 공통 guard 테이블을 만들지 않는다.

### 단일 주기 worker

현재 단일 서버의 겹치지 않는 주기 실행 하나를 사용한다. 별도 분산 claim·lease를 만들지 않는다.
DB 트랜잭션 밖에서 전송하며 직전에 세션·설정·OS 권한·소속/차단·기기 귀속을 검사한다.
attempts를 먼저 증가시키고 성공은 SENT, 일시 실패는 15분 뒤 재시도(최대 5회·24시간),
영구 실패·횟수 초과·만료는 FAILED, 자격 상실은 CANCELLED다. 종료 job은 30일 뒤 정리한다.
프로세스 중단·응답 유실로 다시 전송될 수 있으며 최종 기기 표시 exactly-once는 보장하지 않는다.
다중 서버 전환 때만 worker 중복 실행 방식을 검토한다.

## 3. 검증

| 검증 | 기준 |
| --- | --- |
| migration/JPA | 기존 가입 알림 ID/시각·30일 데이터 이관, 소급 푸시 없음, 생산자 전환·이중 저장 없음, 적용/재실행/validate |
| 알림 | 업무 재시도 중복 없음, 활동 병합·모두 읽음 두 순서, 30일/100개, 최초/독촉 완료 사실 보존 |
| 푸시 | 단일 worker 재시작·횟수/만료, 이전 기기 귀속/토큰 응답 격리, 삭제 후 전송 대기 보존 |
| 업무 연계 | 알림/전송 대기 저장 실패 시 업무와 전체 롤백, 삭제 후 독립 job 보존, 재참여 시 안내 job 취소 |

실제 PostgreSQL·Redis·모의 APNs와 전체 build를 검증한다. 실제 iOS 권한·다중 기기·수신/복귀는 별도 수동 증거다.
