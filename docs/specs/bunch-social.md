# 번치 팔로우 요청·승인·목록 계약 (#51 작업 중)

2026-10-31 승인한 번치 계약의 팔로우 저장·상태 전이·목록만 구현한다.
기준은 2026-11-01 고정 사본의 `bunch.md` 5.10.3·5.11,
`bunch-api.md` 1·2·6절, `bunch-schema.md` 4·5절이다.
고정 사본의 manifest 기준 dev는 `b2b8e71`이다. 이 문서는 실제 팔로우 API와 같은 변경으로 관리하며,
별도 문서 PR을 선행 조건으로 삼거나 전체 번치·알림 Spec을 복제하지 않는다.
기존 생성·초대·번들 서비스와 역할 파일을 변경하지 않는다.

**#51 전체 완료·출시 가능 상태가 아니다.** 차단·신고·외부 프로필/멤버/번들 DTO,
공개 전환, 홈 집계, 17·18·19번 알림·전송 대기는 아직 구현하지 않았다.
차단·신고 API나 가짜 집계/권한 필드를 등록하지 않는다.
차단된 대상·행을 거르는 검사가 없는 현재 목록을 완성 기능으로 공개하지 않는다.

## API와 저장

기본 경로는 `/api/v1/bunches`다. 기존 인증·응답 envelope를 재사용하며
활성 계정·가입 완료·활성 Redis 세션과 선택한 viewer의 활성 Membership을 확인한다.
OWNER/MEMBER 모두 실행할 수 있고 CO_OWNER 통합 후에도 역할 제한 없이 동작한다.
다른 소속 번치에 대한 행동도 viewer 명의이며 `viewerId == targetId` 행동은 400이다.

| 메서드·경로 | 입력 | data |
| --- | --- | --- |
| PUT /{viewerId}/following/{targetId} | 없음 | `{followState,followId}` |
| DELETE /{viewerId}/following/{targetId} | 필수 query `followId` | `{followState,followId,cancelled}` |
| GET /{viewerId}/follow-requests | `page=0,size=20` | `{items:[{id,bunch,requestedAt}],page,size,totalElements,hasNext}` |
| POST /{viewerId}/follow-requests/{requestId}/decision | `{decision:APPROVE 또는 REJECT}` | `{requestId,applied,state}` |
| GET /{targetId}/followers | `viewerBunchId?,page,size` | RelationItem 페이지 |
| GET /{targetId}/following | `viewerBunchId?,page,size` | RelationItem 페이지 |

- `followState`는 NONE/REQUESTED/FOLLOWING이다. 목록 각 행은 조회 대상과의 관계가 아닌
  **viewer→행 번치**의 상태·ID다. 자기 viewer 행에는 NONE/null이다.
- `bunch`는 `id,name,imageUrl,characterType,visibility`만 포함한다.
  RelationItem은 여기에 `followState,followId,followedAt`을 가진다.
- 페이지 size는 1~50이다. 음수 page, 잘못된 size, JPA offset 상한 초과는 COMMON_001이다.
  승인 목록은 `approved_at DESC,id DESC`, 요청은 `created_at DESC,id DESC`다.
  목록·합계·카드·viewer 상태는 REPEATABLE_READ의 동일 snapshot에서 읽는다.
- viewer 생략은 활성 소속 0개 회원만 가능하다. 선택한 viewer 외의 대상 소속을 합치지 않는다.
  외부 비공개 목록은 viewer→target 승인 관계가 있어야 한다. 본인 viewer 목록은 열 수 있다.
- 소속 0개의 명의 행동/소속이 있는데 viewer 생략은 BUNCH_010,
  다른 viewer 명의는 AUTH_002, 없는 외부 조회 대상은 BUNCH_009다.
  다른 번치에 속한 requestId는 RESOURCE_001이다.

V13은 `bunch_follows`만 추가한다. 두 번치 FK는 CASCADE, 생성 회원은 RESTRICT,
승인 회원은 nullable/SET NULL이다. 방향별 쌍 UNIQUE, 자기 관계 금지,
PENDING의 approved_at 없음/APPROVED의 approved_at 필수 CHECK와 세 목록 인덱스를 둔다.
V11(#47)·V12(#28) 예약 번호를 채우는 placeholder는 만들지 않는다.

## 상태 전이와 경합

- 공개 대상의 새 관계는 APPROVED, 비공개는 PENDING이다. PUT 재실행은 기존 ID·생성자를 유지한다.
- 첫 승인만 APPROVED로 바꾸고 첫 거절·취소는 삭제한다. 재요청은 새 ID다.
- 지난 requestId는 새 요청을 변경하지 않는다. 이미 승인된 ID는 늦은 거절에도
  `applied:false,state:APPROVED`, 없어진 ID는 `applied:false,state:UNAVAILABLE`이다.
- DELETE는 followId가 현재 행과 같을 때만 삭제한다. 그 사이 승인됐다면 cancelled=FOLLOWING이다.
  다른 ID이면 현재 상태·ID와 cancelled=NONE을 반환한다.
- 일반 변경은 회원 잠금→번치 ID 오름차순 잠금→활성 소속 재검증→관계 읽기/변경 순서다.
  승인 전 좌표만 조회하고 잠금 후 관계를 읽어 영속성 컨텍스트의 이전 상태를 사용하지 않는다.

## 최소 구현 판단

| 항목 | 판단·근거 |
| --- | --- |
| Controller→Service→JPA | 실제 HTTP 흐름과 업무 트랜잭션·영속성의 경계다. 중간 adapter·별도 relation service는 추가하지 않는다 |
| PENDING/APPROVED 한 테이블 | 요청/승인의 중복 저장 없이 승인만 전이하고 거절·취소는 삭제한다. 과거 요청 원장은 없다 |
| 생성·승인 회원/시각 | 승인 스키마의 실제 업무 기록이다. 새 관계에서만 생성자를 쓰고 수동 승인에서만 승인자를 바꾼다 |
| 카드·페이지·관계 DTO | 실제 세 목록의 공통 출력과 개인정보 최소화에 필요하다. Stage·권한·집계의 가짜 값을 추가하지 않는다 |
| 좌표 조회·잠금 후 상태 조회 | 기다리는 동안 취소·재요청·승인이 일어날 수 있어 필요하다. 이전 ID·새 행을 분리하는 검증을 유지한다 |
| 잠금 전·후 소속 검증 | 권한 없는 상대 정보 조회를 막고, 대기 중 소속 종료도 재검사한다. 중복처럼 보여도 삭제하지 않는다 |
| REPEATABLE_READ·목록 인덱스 | 실제 목록·합계·관계 상태의 같은 snapshot과 확정 정렬에 필요하다 |
| 공개 전환 전용 진입점 | 운영 호출부가 없어 제거했다. #52의 실제 저장 호출부·같은 트랜잭션·경합 테스트와 함께 구현해야 한다 |

불필요한 필드·미사용 분기·미래 차단/신고 stub·범용 bus·outbox·새 의존성은 추가하지 않는다.

## 후속 통합 조건

1. #47의 실제 역할·Stage·권한 기반과 통합한다. 필요한 계약은 실제 기능·테스트와 함께 반영한다.
   #55의 `699f084`를 확인했지만 이 브랜치에 임의 병합하지 않았다. 닫히는 #56을 선행 조건으로 삼지 않는다.
2. #28의 실제 NotificationWriter/저장 모델과 #48의 push job이 준비되면
   `follow`의 **새 행 저장**, `decide(APPROVE)`의 **실제 전이**,
   실제 공개 전환의 **이번 승인 행** 위치에서 같은 업무 트랜잭션에 연결한다.
   17은 새 팔로우 성립 시 대상 번치, 18은 새 PENDING 시 대상 번치,
   19는 수동/자동 승인 시 요청 번치의 유효 멤버가 대상이다.
   직접 행동 회원을 제외하고 외부 알림에 개인 실행자 정보를 표시하지 않는다.
   관계 ID·알림 종류·수신 회원으로 중복을 방지하며 no-op에는 생성하지 않는다.
   알림/job 저장 실패 시 관계·프로필까지 롤백하는 테스트가 필요하다.
   #57의 `5d3cc97` writer는 memberJoined/draftActivity만 있어 17~19를 연결하지 않았다.
3. 차단/해제는 OWNER/CO_OWNER만 가능하게 구현한 뒤 두 방향 관계 제거와
   조회 대상·각 행의 viewer 차단 필터를 목록/합계/외부 상세에 함께 적용한다.
   신고·외부 공개 DTO·실제 전체 발행 수 Stage도 선행 기반에 연결한다.
   차단/신고/프로필을 제공하는 것처럼 응답하는 임시 구현은 없다.
4. #52의 실제 프로필 저장에서 회원→대상 번치 잠금·권한 검증 뒤 PUBLIC 전환과 대기 승인을
   같은 트랜잭션으로 구현한다. 상대 번치를 뒤늦게 잠그지 않고 이번 승인에만 알림을 생성한다.
   여러 대기 전환·기존 승인 기록 보존·실패 전체 롤백·상대 잠금 중 처리와
   신규 요청/수동 승인/취소/차단 경합을 검증해야 한다. 미사용 진입점만으로 이를 완료 처리하지 않는다.
   #50은 실제 번치 삭제와 관계 정리를 연결하고 삭제/팔로우 경합을 검증한다.
5. 실제 V11→V12→V13을 통합 전 새 DB에서 적용·재실행·JPA validate한다.
   V13을 먼저 적용한 이 1차 테스트 DB에 나중에 V11/V12를 덧붙여 운영 이력으로 삼지 않는다.
   통합 시 최신 dev/열린 PR에서 번호 충돌을 다시 확인한다.

## 검증 범위

- BunchSocialIntegrationTest: 상태 전이·이전 ID·일반 멤버·다른 소속 명의·목록 정렬·권한,
  중복/반대 방향 요청·승인/거절/취소 경합, 이전 ID를 읽고 잠금에서 기다리는 동안 새 요청이 생기는 경합,
  대기 목록 페이지/동일 시각 정렬, 생성·승인·취소 저장 실패와 호출자 트랜잭션 실패의 롤백.
- BunchSocialHttpIntegrationTest: 실제 JWT/Redis 세션, 계약 경로·DTO·오류·페이지와 권한.
- BunchSocialMigrationIntegrationTest: 임의 분리 schema에서 V10→V13 실제 적용,
  재실행·제약·FK·인덱스. Spring 전체 컨텍스트는 JPA validate도 수행한다.
- 테스트는 별도 PostgreSQL의 `bunch_issue51_test`와 별도 Redis 컨테이너에서 실행한다.
  실행 명령·통과 결과는 작업 완료 보고에 기록한다. 별도 lint/정적 분석 설정은 없다.
- 실제 iOS/WebView·푸시 수신, #47/#28/#48 및 차단/공개 전환/외부 프로필 통합 검증은 남아 있다.
