# 번치 역할·버전·활동기록 계약 (#47)

2026-10-31 승인 번치 계약 중 #47 구현 범위를 기록한다. 전체 홈·프로필 일괄 저장,
나가기·내보내기(#25), 알림 저장·전송(#28)은 각 이슈에서 연결한다. 역할 변경 API는
#49의 `BunchMembershipManagementService`와 역할 관리 계약을 따른다.
번들 REST·STOMP 계약은 [번들 API](bundle-api.md), 생성·초대는
[로그인·온보딩 API](login-onboarding-api.md)를 함께 따른다.

## 역할과 기존 응답 호환

- `OWNER`는 메인 관리자 1명, `CO_OWNER`는 여러 명 가능한 공동 관리자, `MEMBER`는 일반 멤버다.
- 활성 OWNER의 중복은 부분 유일 인덱스로 막는다. 마지막 OWNER 소멸 방지는 번치 잠금을 잡는
  서비스 트랜잭션의 책임이다. V11은 기존 번치의 ACTIVE OWNER가 정확히 1명인지 검사하고,
  OWNER 0명·복수 또는 활성 소속 없는 번치가 하나라도 있으면 전체 실패한다. 임의 승계하지 않는다.
- 기존 `GET /api/v1/bunches/{bunchId}/members`의 배열·필드는 유지한다.
  OWNER→CO_OWNER→MEMBER, 역할 안에서 닉네임 C collation ASC→Membership ID ASC다.
  비활성 소속·탈퇴 계정은 제외한다. 새 역할 관리 API를 이 변경에 포함하지 않는다.
- 번치 이미지 즉시 변경·초기화는 OWNER만 가능하다. CO_OWNER도 초대 발급·내부 조회가 가능하다.

## 저장·버전

V11은 기존 적용 파일을 변경하지 않고 다음 필드를 추가한다.

| 대상 | 필드·제약 |
| --- | --- |
| bunches | profile_version, membership_version bigint NOT NULL DEFAULT 0, 각각 >=0 |
| bunch_memberships | role CHECK OWNER/CO_OWNER/MEMBER |
| bunch_memberships | ended_at timestamptz nullable, role_granted_by bigint nullable (members FK SET NULL) |
| bunch_memberships | last_activity_at timestamptz nullable |

기존 비활성 소속의 ended_at은 migration 시각으로 채우며 알림을 소급 생성하지 않는다.
활동시각은 last_used_at으로 추정하지 않으므로 기존 행은 null이다.

- 새 번치 생성과 첫 OWNER 생성은 하나의 트랜잭션이며 membershipVersion은 1이 된다.
  기존 번치는 0으로 시작하고 새 참여 성공마다 한 번 증가한다. 생성·참여 성공 재생,
  ALREADY_MEMBER·초대 발급·최근 사용·조회는 증가시키지 않는다. 참여 성공 재생은 번치 잠금 후
  소속을 새로 반영하여 잠금 전에 읽은 값으로 동시 업로드 활동시각을 덮어쓰지 않는다.
- 프로필 이미지 실제 교체와 이미지가 있던 상태의 초기화는 profileVersion을 한 번 증가시킨다.
  이미지 없는 상태의 초기화는 무변경 성공이다. 저장 실패 시 이미지 참조와 버전 모두 롤백한다.
- 후속 역할·인계·이탈 API는 같은 번치 잠금 아래 실제 변경 트랜잭션에서 membershipVersion을
  갱신해야 한다. 새 API·미사용 범용 변경 프레임워크는 추가하지 않는다.

이 버전은 현재 생성·참여·이미지 API의 실제 변경에서 이미 기록된다. 프로필 일괄 저장은 #52,
역할 관리 API는 #49에서 버전 노출·충돌 검사까지 제공한다. `expectedMembershipVersion`은
실제 역할 변경 때만 증가한 `membershipVersion`과 대조한다.
`endedAt`·`roleGrantedBy`는 현재 API의 조회·변경 대상이 아니지만 승인된 소속 스키마의 필수 컬럼이다.
V11의 기존 종료 소속 시각 백필·nullable 권한 부여자 FK 계약과 JPA 매핑을 함께 유지한다.
이 필드를 위한 별도 조회·변경 헬퍼나 임시 API는 만들지 않는다.

## #49 역할 관리 연결 계약

`GET /api/v1/bunches/{bunchId}/memberships`는 `membershipId`, `memberId`, 현재 역할,
`isMe`, `joinedAt`, 역할별 action과 본인 소속·`membershipVersion`을 반환한다. 기존
`/members` 배열 응답은 호환성을 위해 유지한다. `OWNER`만 `CO_OWNER` 지정·해제와
`POST /owner-transfer`를 실행할 수 있으며 OWNER는 항상 정확히 한 명이다.

`PUT /memberships/{membershipId}/role`은 `actorMembershipId`,
`expectedMembershipVersion`, 목표 `CO_OWNER`/`MEMBER`만 받는다. 이 요청에는
`Idempotency-Key`를 추가하지 않는다. 동일 대상의 동일 목표 역할은 상태·멤버십 ID로
추가 효과 없이 성공시키고, 실제 변경만 `membershipVersion`과 `roleGrantedAt`/`roleGrantedBy`를
갱신한다. 다른 번치의 대상 Membership ID는 404, 오래된 실제 변경 버전은 409다.

`POST /owner-transfer`만 UUID `Idempotency-Key`가 필수다. V16의
`bunch_action_requests`와 `BunchActionRequest`/`BunchActionRequestRepository`는 이
기능이 실제 사용하는 성공 재생 기록의 소유자다. `PROFILE`, `OWNER_TRANSFER`, `LEAVE`,
`DELETE` 작업만 허용하며, #52 프로필 저장·#25 이탈·후속 #50 삭제는 같은 단순 테이블과
Repository를 재사용한다. 성공 재생 조회는 `expiresAt > now`만 허용하고 정확히 30일이
지난 `expiresAt <= now` 행은 인계 잠금 전에 정리한다. 인계는 먼저 가입 완료와 입력 형식을
확인한 뒤 성공 재생을 번치 존재·현재 역할 검사보다 먼저 조회하고, 재생이 없을 때만 번치
잠금 아래 같은 key를 다시 조회해 동시 중복 적용을 막는다. 그래서 번치 본체가 삭제된 뒤에도
현재 세션의 같은 성공 인계 요청은 최소 결과를 재생하지만, 새 key는 삭제된 번치의 권한을
우회하지 않는다. `deleteExpiredByBunchId`는 이 실제 인계 사용과 후속 공유 작업이 재사용할
최소 만료 청소 함수다. 기존 부모 구현 호환용 단순 조회
`findByMemberIdAndBunchIdAndOperationAndRequestKey`는 유지하고, #49 인계는
`findActiveByMemberIdAndBunchIdAndOperationAndRequestKey(..., now)`를 사용한다.
범용 명령·권한·재시도 프레임워크는 만들지 않는다.

역할이 실제 변경되면 11/12/13번 `Notification`을 같은 DB 트랜잭션에 일반 저장한다.
수신자는 변경된 대상이며 실행자는 제외하고, `target.kind`는 `ROLE_PERMISSIONS`다.
실제 notification 저장 행을 #48의 `PushJobWriter.enqueue`에 전달해 현재 기기의 전송 대기도
같은 트랜잭션에서 기록한다. 알림 또는 job 저장 실패는 역할 변경·버전·재생 기록과 함께
롤백된다. 기기 없는 과거 알림의 소급 전송은 없으며 요청 replay는 알림·job을 만들지 않는다.

## 실제 활동

사진 새 저장·공동 업로더 신규 연결, 실제 초안 내용 편집, 코멘트 저장 성공은 해당 ACTIVE
Membership의 lastActivityAt을 같은 트랜잭션에서 갱신한다. 성공 재생·동일 내용 저장·조회·최근 사용·
presence·활동 신호·발행/삭제·역할 변경은 활동으로 세지 않는다.
발행 코멘트 수정은 기존 작성자 권한을 유지하며, 다른 사람의 코멘트를 관리자가 수정하지 못한다.

## 통합·검증 경계

- V11 적용·재실행·JPA validate, 기존 데이터 보존·이상 OWNER 전체 실패·동시 OWNER 중복 거부를
  실제 PostgreSQL에서 검증한다. 버전·동시 참여·실패 롤백·활동 갱신도 검증한다.
- 번들 권한은 DRAFT/PUBLISHED별 판정과 DTO/STOMP가 일치해야 한다.
  Stage는 PUBLIC/PRIVATE 전체 현재 발행 수 0/2/6/10 경계의 1~4단계이며 삭제 시 하락한다.
  초안은 updatedAt DESC,id DESC다. 기존 BundlePage 형태를 유지한다.
  현재 Stage 제공 경로는 기존 번들 summary API다. 홈·외부 프로필 API 자체는 #51·#52의 범위다.
- #28 생산자는 이 브랜치의 BundleService/InvitationService 변경과 병합 시 함께 대조한다.
  migration 번호·순서는 최종 dev 통합 직전에 다시 확인한다. CO_OWNER를 모르는 구서버와 혼합 운영하지 않는다.
- 실제 iOS의 역할 배지·Stage·권한 변경 화면은 앱 준비 후 별도 수동 검증한다.
