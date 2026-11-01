# 홈·전환·프로필 일괄 저장 계약 — #52

이 문서는 #52가 실제로 제공하는 경계를 짧게 기록한다. 제품 정책의 원문과 전체 기능 색인은
[번치 제품 Spec](bunch.md), 공개 API의 공통 규칙은 [번치 API](bunch-api.md), 잠금·스키마는
[번치 DB·트랜잭션 계약](bunch-schema.md)을 기준으로 한다. 알림 문구·대상·중복 기준은
[알림 Spec](notification.md)과 [알림 API](notification-api.md)를 따른다.

## 제공 API

| API | 구현 | 계약 요약 |
| --- | --- | --- |
| `GET /api/v1/members/me/bunches?currentBunchId` | [BunchListController](../../src/main/kotlin/com/bunch/bunch/controller/BunchListController.kt) | 활성 소속, 유효한 접근 안내, 실제 보관 알림 수를 한 read snapshot에서 반환한다. 현재 번치는 표시 순서만 앞세운다. |
| `GET /api/v1/bunches/{bunchId}/home` | [BunchHomeController](../../src/main/kotlin/com/bunch/bunch/controller/BunchHomeController.kt) | 프로필·권한·멤버/소셜 수·초안/발행 수·Stage·미확인 수를 같은 PostgreSQL snapshot에서 반환한다. |
| `PUT /api/v1/bunches/{bunchId}/profile` | [BunchProfileController](../../src/main/kotlin/com/bunch/bunch/controller/BunchProfileController.kt) | OWNER만 이름·소개·공개 범위·이미지를 `KEEP/REPLACE/RESET`으로 원자 저장한다. `Idempotency-Key`와 `expectedProfileVersion`이 필수다. |
| `GET /api/v1/bunches/name-availability` | [BunchController](../../src/main/kotlin/com/bunch/bunch/controller/BunchController.kt) | `excludeBunchId`는 본인 OWNER 소속만 허용하고 자기 이름만 제외한다. 저장 시 유일성 제약을 다시 검사한다. |

프로필 multipart의 `profile` part는 JSON이고 `photo`는 `REPLACE`에서만 필수다. `KEEP`와
`RESET`에는 빈 파일 part도 허용하지 않는다. 이름은 기존 `ProfileText`의 trim+NFC/코드 포인트
규칙, 소개는 기존 grapheme 규칙을 재사용한다. wire JPEG는 기존 저장소의 5 MiB·정사각형·
최대 1024 규칙을 사용한다. 앱의 원본 선택 10,000,000 bytes 규칙은 [사진 기반 계약](photo-foundation.md)의
소유 범위이며 서버 wire 제한과 혼동하지 않는다.

## 원자성·멱등성

- 외부 이미지 upload는 DB 잠금 밖에서 수행한다. upload 뒤 회원 완료 상태·OWNER·이름 유일성·버전을 번치 잠금 아래 재검사한다.
- 새 asset 참조와 이전 asset 해제 예약은 프로필 변경, 알림, 성공 결과 기록과 같은 DB transaction에 속한다. 실패한 원격 파일은 보상 삭제한다.
- 정규화된 입력과 `REPLACE` 이미지 bytes의 SHA-256을 `PROFILE` action request fingerprint로 저장한다. 성공 결과는 30일 재생하며 만료된 기록은 새 요청으로 정리한다.
- 같은 값 저장은 성공하지만 `profileVersion`·알림을 증가시키지 않는다. 다른 입력을 같은 키로 재생하면 `REQUEST_001`, 오래된 버전이면 `BUNCH_004`다.
- 공개 범위 변경은 실제 방향에 따라 type 16 알림을 만든다. `PRIVATE → PUBLIC`에서는 대상 번치만 잠근 상태에서 현재 PENDING follow를 승인하고, #51의 `NotificationWriter.followApproved`를 통해 type 19 및 기존 승인 문구/중복키를 재사용한다. follower 번치 lock을 뒤늦게 획득하지 않는다.

## 홈·전환 읽기

- 홈의 `draftCount/publishedCount/stage`는 [BundleService.summary](../../src/main/kotlin/com/bunch/bundle/service/BundleService.kt)의 실제 집계를 재사용한다. Stage 기준은 `0/2/6/10`이고 공개·비공개 발행본을 모두 센다.
- `followerCount/followingCount`는 #51의 승인 관계 및 양방향 block 필터를 적용한 repository count를 사용한다. 미구현 값을 0으로 채우지 않는다.
- `activeBunches`는 current 우선, 나머지는 `lastUsedAt DESC, membershipId DESC`다. 소속 0개도 정상 응답이며 default는 실제 유효한 최근 소속만 반환한다.
- #25의 `BunchAccessNoticeService.active`를 재사용한다. 현재 활성 소속이 전혀 없고 대상 번치의 본인 미확인·미만료 notice가 있으면 target을 조회하기 전에 `BUNCH_008`과 `{noticeId,reason}`을 반환하고, 없으면 기존 `FORBIDDEN`을 유지한다. 안내 endpoint는 #25 소유이며 이 PR에서 중복하지 않는다.

## 소유 경계와 선행

- #49/V16의 `BunchActionRequest`와 `PROFILE` operation을 재사용한다. 별도 action table/entity를 만들지 않는다.
- #51/V14의 `BunchFollow`, block 필터, social count, `NotificationWriter.followApproved`를 재사용한다.
- #25/V17의 AccessNotice entity/repository/service를 목록과 home의 최소 접근 검사에만 연결한다.
- profileVersion은 기존 V11 컬럼을 사용하므로 #52에는 V19 placeholder를 추가하지 않는다. 새 스키마가 실제로 필요해지는 경우에만 다음 마이그레이션을 추가한다.
- #48의 실제 `PushJobWriter`를 같은 transaction에서 사용한다. type 16 공개 범위 변경은 `NotificationRepository.saveAll → flush`로 반환된 행을 `PushJobWriter.enqueue(rows, now)`에 직접 넘기고, 공개 전환으로 생긴 type 19 승인 알림은 기존 `NotificationWriter.followApproved`가 저장·enqueue하는 경로를 재사용한다. actor는 두 경로에서 제외하며 fake adapter나 별도 큐를 만들지 않는다.

## 추적·검증

제품 색인의 47개 기능 ID와 최종 문서 링크는 부모가 관리하는 [번치 색인](bunch.md)을 따른다. 이 문서는 원문 1009줄을 복제하지 않고 #52의 실제 endpoint·코드·선행 migration만 추적한다.

관련 검증은 `BunchHomeProfileIntegrationTest`, 기존 name-availability 회귀, 실제 PostgreSQL/Redis의 V14/V15/V16/V17 Flyway 적용, 프로필 version/idempotency/image 보상 및 공개 전환·block 경합·실제 기기 등록 후 type 16/19 job·push_jobs 쓰기 실패 rollback 테스트를 포함한다. 실제 iOS SDK·APNs 수신은 환경 밖이며, #48의 worker 최종 lock-order 보정은 부모가 전달할 후속 SHA에서 별도로 기록한다.
