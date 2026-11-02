# 홈·전환·프로필 일괄 저장 계약 — #52

이 문서는 #52가 실제로 제공하는 경계를 짧게 기록한다. 제품 정책의 원문과 전체 기능 색인은
[번치 색인](bunch.md), 인증·공통 응답은 [가입·온보딩 API](login-onboarding-api.md),
역할·버전은 [역할 계약](bunch-role-stage.md)을 따른다. 홈·프로필 API와 잠금·저장 계약은 이 문서에서 함께 관리한다. 알림 문구·대상·중복 기준은
[알림 Spec](notification.md)과 [알림 API](notification-api.md)를 따른다.

## 제공 API

| API | 구현 | 계약 요약 |
| --- | --- | --- |
| `GET /api/v1/members/me/bunches` | [BunchListController](../../src/main/kotlin/com/bunch/bunch/controller/BunchListController.kt) | 서버 현재 번치를 기존 `defaultBunchId`와 활성 소속으로 한 read snapshot에서 반환한다. query로 현재 번치를 덮어쓰지 않는다. |
| `GET /api/v1/bunches/{bunchId}/home` | [BunchHomeController](../../src/main/kotlin/com/bunch/bunch/controller/BunchHomeController.kt) | 프로필·권한·멤버/소셜 수·초안/발행 수·Stage·미확인 수를 같은 PostgreSQL snapshot에서 반환한다. |
| `GET /api/v1/bunches/{bunchId}/public-profile` | [BunchHomeController](../../src/main/kotlin/com/bunch/bunch/controller/BunchHomeController.kt) | `/home`의 legacy alias. 동일한 `BunchHomeResponse`와 현재 번치 판정을 사용한다. |
| `GET /api/v1/bunches/{bunchId}/members` | [BunchHomeController](../../src/main/kotlin/com/bunch/bunch/controller/BunchHomeController.kt) | canonical `List<BunchMemberResponse>`; 접근 가능한 ACTIVE 멤버만 반환한다. |
| `GET /api/v1/bunches/{bunchId}/public-members` | [BunchHomeController](../../src/main/kotlin/com/bunch/bunch/controller/BunchHomeController.kt) | `/members`의 legacy wrapper `{items:[BunchMemberResponse]}`다. |
| `GET /api/v1/bunches/{bunchId}/published-bundles` | [BunchHomeController](../../src/main/kotlin/com/bunch/bunch/controller/BunchHomeController.kt) | 기존 public bundle card/page를 재사용하며 INTERNAL은 모든 PUBLISHED, EXTERNAL은 PUBLIC PUBLISHED만 반환한다. |
| `GET /api/v1/bunches/{bunchId}/published-bundles/{bundleId}` | [BunchHomeController](../../src/main/kotlin/com/bunch/bunch/controller/BunchHomeController.kt) | 목록과 같은 접근 범위의 기존 public bundle detail이다. |
| `GET /api/v1/bunches/{bunchId}/public-bundles[/{bundleId}]` | [BunchHomeController](../../src/main/kotlin/com/bunch/bunch/controller/BunchHomeController.kt) | `/published-bundles`의 legacy alias이며 DTO·페이지·공개 범위가 같다. |
| `PUT /api/v1/bunches/{bunchId}/last-used` | [BunchController](../../src/main/kotlin/com/bunch/bunch/controller/BunchController.kt) | 회원의 전역 current 전환. ACTIVE 소속을 명시적으로 최근 사용으로 만든다. |
| `PUT /api/v1/bunches/{bunchId}/profile` | [BunchProfileController](../../src/main/kotlin/com/bunch/bunch/controller/BunchProfileController.kt) | OWNER만 이름·소개·공개 범위·이미지를 `KEEP/REPLACE/RESET`으로 원자 저장한다. `Idempotency-Key`와 `expectedProfileVersion`이 필수다. |
| `GET /api/v1/bunches/name-availability` | [BunchController](../../src/main/kotlin/com/bunch/bunch/controller/BunchController.kt) | `excludeBunchId`는 본인 OWNER 소속만 허용하고 자기 이름만 제외한다. 저장 시 유일성 제약을 다시 검사한다. |

프로필 multipart의 `profile` part는 JSON이고 `photo`는 `REPLACE`에서만 필수다. `KEEP`와
`RESET`에는 빈 파일 part도 허용하지 않는다. 이름은 기존 `ProfileText`의 trim+NFC/코드 포인트
규칙, 소개는 기존 grapheme 규칙을 재사용한다. wire JPEG는 기존 저장소의 5 MiB·정사각형·
최대 1024 규칙을 사용한다. 앱의 원본 선택 10,000,000 bytes 규칙은 [사진 기반 계약](photo-foundation.md)의
소유 범위이며 서버 wire 제한과 혼동하지 않는다.

## 통합 조회와 현재 번치 계약

홈·외부 프로필·멤버·발행본은 아래 공통 읽기 계약을 따른다. 이전 `PublicProfile` 응답과
query의 `viewerBunchId`·`currentBunchId` 선택 정책을 대체한다. 이 절의 `currentBunchId`는 요청 대상이 아니라 인증 회원 계정의 서버 현재 번치다.

### 현재 번치와 접근 범위

- 서버 현재 번치는 인증 회원의 `ACTIVE` 소속 중 `lastUsedAt DESC, membership.id DESC`로
  고른다. 동률도 같은 순서를 유지하며, 비활성 소속은 제외한다. 여러 로그인 세션에서 같은
  계정 현재 번치를 공유한다.
- GET의 `viewerBunchId`와 목록의 `currentBunchId` query는 현재 번치 선택을 덮어쓰지 않는다.
  호환을 위해 입력이 남아도 서버는 선택 근거로 사용하지 않는다. 사용자의 명시적 현재 번치
  전환은 `PUT /api/v1/bunches/{bunchId}/last-used`만 수행하는 전역 전환이다.
- 번치 생성·참여의 성공 처리와 create/join 성공 replay(`ALREADY_MEMBER` 결과 포함)는 기존
  `lastUsedAt` 갱신을 유지한다. 이탈 후 유효한 최근 소속으로 돌아가는 fallback도 기존 lifecycle
  계약을 따른다. 홈·소셜 GET과 알림 `open`만 `lastUsedAt`을 바꾸지 않는다.
- `accessScope`는 `INTERNAL` 또는 `EXTERNAL`이다. 대상 번치가 서버 현재 번치이고 회원의
  해당 `ACTIVE` 소속이 있을 때만 `INTERNAL`이다. 다른 자신의 소속 번치라도 현재 번치가
  아니면 `EXTERNAL`이며, 대상 소속의 역할·권한을 현재 명의에 합치지 않는다.
- ACTIVE 소속이 하나도 없으면 `currentBunchId:null`인 `EXTERNAL` 문맥으로 공개 대상의
  기본 프로필을 읽을 수 있다. 비공개 미승인 대상은 기본 정보·count·실제 stage만 제공하고
  세 read flag는 `false`다. 대상 차단·삭제 등 기존 외부 unavailable 판정은 유지한다.

### 홈·프로필 canonical/alias와 `BunchHomeResponse`

- `GET /api/v1/bunches/{bunchId}/home`이 canonical endpoint다.
  `GET /api/v1/bunches/{bunchId}/public-profile`은 이행용 alias이며 **동일한
  `BunchHomeResponse`**를 반환한다. 두 경로의 접근 판정·필드 nullability·현재 번치 결정은 같다.
- 응답은 기존 `BunchHomeResponse` 필드에 `currentBunchId`, `accessScope`,
  `canViewBundles`, `canViewMembers`, `canViewRelations`, `followState`, `followId`,
  `isCurrentBunch`를 포함한다. `followState`·`followId`는 서버 현재 번치→대상 번치
  관계이며, 현재 번치가 없거나 자기 대상이면 각각 `NONE`·`null`이다. `isCurrentBunch`는
  `currentBunchId == bunch.id`일 때만 `true`이고, 다른 자신의 ACTIVE 소속을 조회한
  `EXTERNAL` 응답에서는 `false`다.
- 모든 nullable 필드는 내부·외부 모두 JSON `null`로 직렬화하고 생략하지 않는다. `permissions`와
  세 read flag는 nullable이 아니며 항상 객체/boolean을 명시한다. `INTERNAL`의 세 read flag는
  모두 `true`이고, `EXTERNAL`은 공개 또는 승인 follower일 때만 해당 범위를 `true`로 한다.
- 공통 응답 필드의 접근 계약은 다음과 같다.

| 필드 | `INTERNAL` | `EXTERNAL` |
| --- | --- | --- |
| `currentBunchId` | 서버가 고른 현재 번치 ID | 서버가 고른 현재 번치 ID, 소속 0개면 `null` |
| `accessScope` | `INTERNAL` | `EXTERNAL` |
| `bunch` | 기존 `BunchHomeProfileResponse` | 같은 객체·필드 |
| `myMembership` | 현재 번치의 기존 `MyMembershipResponse` | `null` |
| `permissions` | 현재 소속의 기존 권한 판정 | 아래 외부 표시 규칙의 명시적 boolean |
| `profileVersion` | 값 반환 | `null` |
| `membershipVersion` | 값 반환 | `null` |
| `memberCount` | ACTIVE 멤버 수 | 공개 가능한 현재 ACTIVE 멤버 수 |
| `followerCount` / `followingCount` | 기존 승인·차단 필터 count | 동일한 공개 count 정책 |
| `draftCount` | 기존 실제 초안 집계 | `null` |
| `publishedCount` | 실제 PUBLISHED 전체 개수 | `null` |
| `stage` | 실제 PUBLIC/PRIVATE PUBLISHED 전체 개수 기준 | 같은 실제 단계(0/2/6/10) |
| `unreadCount` | 현재 번치 inbox의 보관 미확인 수 | `null` |

- `canViewBundles`, `canViewMembers`, `canViewRelations`는 응답에 항상 명시한다. 공개 대상
  또는 서버 현재 번치가 대상의 승인된 follower인 경우 `true`, 비공개 미승인 외부 조회는
  모두 `false`다. 사진함·초안·PRIVATE 발행본·편집권은 어떤 외부 flag로도 열리지 않는다.
- `permissions`의 내부 값은 기존 현재 소속 권한을 유지한다. 외부에서는 target의 역할을
  노출하거나 사용하지 않는다. `canBlock`만 서버 현재 번치의 `ACTIVE` membership 역할이
  `OWNER` 또는 `CO_OWNER`이면 표시 가능한 다른 번치 대상에 `true`이고, 현재 소속이 없으면
  `false`다. 외부의 나머지 여섯 target action(`canEditProfile`, `canDeleteBunch`,
  `canManageRoles`, `canTransferOwner`, `canInvite`, `canLeave`)은 항상 `false`다.

### 멤버·발행본 읽기

- canonical `GET /api/v1/bunches/{bunchId}/members`는 기존 최소 `List<BunchMemberResponse>`를
  그대로 반환한다. 필드는 `memberId`, `nickname`, `imageUrl`, `role`뿐이며 ACTIVE 멤버만
  포함한다. `GET /api/v1/bunches/{bunchId}/public-members`는 이 목록을 `{items: [...]}`로
  감싼 legacy wrapper이고 필드와 역할 정렬은 동일하다.
- 역할 필드는 `OWNER`, `CO_OWNER`, `MEMBER`를 유지하고, 앱의 메인/공동 관리자 배지는
  [PRD 정렬 커밋 `d898c21`](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/commit/d898c21691201c1768f5f90e9e336d4cadd369e1)에
  기록된 2026-10-29 제품 승인 정책의 근거를 따른다. `memberId`는 목록 연결을 위한 기술 ID
  예외이며 소셜 식별자(로그인 제공자 ID)는 노출하지 않는다. 출생연도·성별·가입일·계정 상태·
  세션·관리 action·개인 프로필 이동 정보는 멤버 목록과 외부 응답에 추가하지 않는다.
- canonical `GET /api/v1/bunches/{bunchId}/published-bundles`와 상세 경로는 기존
  `PublicBundleCard`/`PublicBundleDetail` 및 paginated page 형태를 재사용한다. `INTERNAL`은
  대상 번치의 모든 `PUBLISHED`를, `EXTERNAL`은 접근 가능한 `PUBLIC` `PUBLISHED`만
  `publishedAt DESC, id DESC`로 선택한다. legacy `public-bundles` 목록·상세 alias도 같은
  DTO·페이지·공개 범위를 사용한다.
- Draft/editor API와 `GET /api/v1/bunches/{bunchId}/bundles?status=DRAFT&page&size` 목록은
  이 통합 읽기 계약으로 바꾸지 않는다. 기존 경로, paginated page 형식, 현재 소속·참여·역할
  검사와 편집 권한을 그대로 유지한다.

### 알림 open과 정책 경계

- `OTHER_BUNCH_PROFILE` 알림을 open할 때 저장된 inbox `bunchId`나 target payload를 현재
  명의로 간주하지 않는다. 서버의 현재 번치와 대상 존재·양방향 차단·공개/승인 상태를 다시
  검사해 위와 같은 `BunchHomeResponse` 접근 범위를 적용한다. open도 현재 번치를 전환하지
  않는다.
- 외부 `publishedCount`는 항상 `null`이지만 `stage`는 내부와 같은 실제 전체 PUBLISHED
  기준으로 유지한다. 이 구분은 FEED의 외부 번들 수 표기와 Stage 계산을 혼동하지 않도록
  한다. 외부 개인정보 금지 정책은 기존 PRD·소셜 Spec보다 넓히지 않는다.

## 원자성·멱등성

- 외부 이미지 upload는 DB 잠금 밖에서 수행한다. upload 뒤 회원 완료 상태·OWNER·이름 유일성·버전을 번치 잠금 아래 재검사한다.
- 새 asset 참조와 이전 asset 해제 예약은 프로필 변경, 알림, 성공 결과 기록과 같은 DB transaction에 속한다. 실패한 원격 파일은 보상 삭제한다.
- 정규화된 입력과 `REPLACE` 이미지 bytes의 SHA-256을 `PROFILE` action request fingerprint로 저장한다. 성공 결과는 30일 재생하며 만료된 기록은 새 요청으로 정리한다.
- 같은 값 저장은 성공하지만 `profileVersion`·알림을 증가시키지 않는다. 다른 입력을 같은 키로 재생하면 `REQUEST_001`, 오래된 버전이면 `BUNCH_004`다.
- 공개 범위 변경은 실제 방향에 따라 type 16 알림을 만든다. `PRIVATE → PUBLIC`에서는 대상 번치만 잠근 상태에서 현재 PENDING follow를 승인하고, #51의 `NotificationWriter.followApproved`를 통해 type 19 및 기존 승인 문구/중복키를 재사용한다. follower 번치 lock을 뒤늦게 획득하지 않는다.

## 홈·전환 읽기

- 내부 홈의 `draftCount/publishedCount/stage`는 [BundleService.summary](../../src/main/kotlin/com/bunch/bundle/service/BundleService.kt)의 실제 집계를 재사용한다. Stage 기준은 `0/2/6/10`이고 공개·비공개 `PUBLISHED`를 모두 센다. 외부 응답의 두 count는 `null`이고 stage만 같은 실제 기준으로 유지한다.
- `followerCount/followingCount`는 #51의 승인 관계 및 양방향 block 필터를 적용한 repository count를 사용한다. 미구현 값을 0으로 채우지 않는다.
- 목록의 `defaultBunchId`와 `activeBunches`는 인증 회원의 ACTIVE 소속을 `lastUsedAt DESC, membershipId DESC`로 계산한다. 소속 0개면 current와 default는 `null`이며, query 입력으로 순서를 바꾸지 않는다.
- 사용자의 명시적 current 전환은 `PUT /api/v1/bunches/{bunchId}/last-used`다. 생성·새 참여의
  성공 처리와 create/join 성공 replay(`ALREADY_MEMBER` 결과 포함)는 기존 `lastUsedAt` 갱신을
  유지하고, current 소속 종료 후 유효한 최근 소속 fallback도 기존 lifecycle 계약을 따른다.
  읽기와 notification `open`은 `lastUsedAt`을 바꾸지 않는다.
- 접근 안내는 #25의 `BunchAccessNoticeService.active`와 안내 endpoint를 재사용하며 이 문서에서
  중복하지 않는다. 대상 lookup 전에 본인 소유의 미확인·미만료 notice가 있으면 그 notice를
  우선 처리해 `BUNCH_008`과 `{noticeId,reason}`을 반환한다. acknowledged/expired notice가
  없으면 일반 `INTERNAL`/`EXTERNAL` 정책으로 계속하고, 대상에 다시 `ACTIVE`로 참여한 경우에는
  notice를 무시한다. notice가 없다는 이유로 통합 읽기에 구형 `FORBIDDEN`을 적용하지 않는다.

## 소유 경계와 선행

- #49/V16의 `BunchActionRequest`와 `PROFILE` operation을 재사용한다. 별도 action table/entity를 만들지 않는다.
- #51/V14의 `BunchFollow`, block 필터, social count, `NotificationWriter.followApproved`를 재사용한다.
- #25/V17의 AccessNotice entity/repository/service를 목록과 home의 최소 접근 검사에만 연결한다.
- profileVersion은 기존 V11 컬럼을 사용하므로 #52에는 V19 placeholder를 추가하지 않는다. 새 스키마가 실제로 필요해지는 경우에만 다음 마이그레이션을 추가한다.
- #48의 실제 `PushJobWriter`를 같은 transaction에서 사용한다. type 16 공개 범위 변경은 `NotificationRepository.saveAll → flush`로 반환된 행을 `PushJobWriter.enqueue(rows, now)`에 직접 넘기고, 공개 전환으로 생긴 type 19 승인 알림은 기존 `NotificationWriter.followApproved`가 저장·enqueue하는 경로를 재사용한다. actor는 두 경로에서 제외하며 fake adapter나 별도 큐를 만들지 않는다.

## 추적·검증

제품 색인의 47개 기능 ID와 최종 문서 링크는 부모가 관리하는 [번치 색인](bunch.md)을 따른다. 이 문서는 원문 1009줄을 복제하지 않고 #52의 실제 endpoint·코드·선행 migration만 추적한다.

관련 검증은 `BunchHomeProfileIntegrationTest`, 기존 name-availability 회귀, 실제 PostgreSQL/Redis의 V14/V15/V16/V17 Flyway 적용, 프로필 version/idempotency/image 보상 및 공개 전환·block 경합·실제 기기 등록 후 type 16/19 job·push_jobs 쓰기 실패 rollback 테스트를 포함한다. 실제 iOS SDK·APNs 수신은 환경 밖이며, #48의 worker 최종 lock-order 보정은 부모가 전달할 후속 SHA에서 별도로 기록한다.
