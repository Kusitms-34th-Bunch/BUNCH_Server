# 번들 장소 목록 계약

이슈 #27, DRAFT-17·BUNDLE-04·FEED-10. 2026-11-07 승인한 피드 원문
6.2.3·6.2.12·6.3.1과 보완 결정을 따른다. 기존 장소 1개·검색 공급자 선행 계약을 대체한다.
#107은 장소 ID 의도 처리와 사용자 lease 없는 저장을 추가한다. 아래 계약이 이전 lease 설명보다 우선한다.
2026-11-12 승인한 4.3.10 동시 편집 보완에 따라 의도 요청의 행 ID 보존·중복/정원 거부를 적용한다.
사진·위치가 움직이는 과정의 실시간 공유는 후속이다. 이번에는 놓은 뒤 최종 위치 순서만 저장·전파한다.
공개 접근은 [피드 공통 계약](feed-api.md), 초안 프로토콜은 [번들 API](bundle-api.md)를 재사용한다.

## 저장·입력

장소는 선택 입력이며 최초 `locations: []`, `locationVersion: 0`이다. 장소 없이도 발행할 수 있다.
최대 3개이고 추가 순서를 `position`으로 저장한다. 순서 변경도 지원한다.
사진 EXIF·기기 현재 위치·좌표는 사용하거나 저장하지 않는다.

| 입력 | 검증·정규화 |
| --- | --- |
| provider | trim 후 소문자. ASCII `[a-z0-9_-]` 1~32자 |
| externalId | trim 후 1~128 Unicode code point. 대소문자 유지 |
| name | trim 후 비어 있지 않은 1~200 grapheme |
| address | 필드 필수. 주소가 없으면 null, 문자열이면 trim 후 비어 있지 않은 1~500 grapheme |
| position | 0부터 시작하는 연속 정수. 목록이 `[0, ..., size-1]`이어야 함 |

문자열의 NUL은 거부한다. 목록 내 정규화한 `(provider, externalId)`는 중복할 수 없다.
입력 배열 순서와 관계없이 position 오름차순으로 저장·응답한다.
`com.bunch.place.domain.PlaceDetails.of`를 광고·콕하기에서도 공통 검증에 재사용한다.
잘못된 값은 `IllegalArgumentException`이며 유스케이스에서 `INVALID_INPUT`으로 변환한다.
검색 공급자 결과의 진위 검증은 후속이다. 이번에는 구조 검증한 장소 입력을 저장한다.
최신 4.3.10의 검색·공급자 호출은 프론트가 맡고 서버는 결과값만 받는다.
서버 검색 API는 추가하지 않는다. 주소 없는 지역은 `address: null`로 저장하며
상세 입력·호환성은 [#126 장소 검색 계약](place-search.md)을 따른다.

## HTTP

기본 경로: `/api/v1/bunches/{bunchId}/bundles/{bundleId}/locations`.
기존 `success / data / error` 응답과 활성 JWT session 인증을 사용한다.

- `GET`: DRAFT는 가입 완료 ACTIVE 번치 멤버만 조회한다. PUBLISHED는
  `FeedAccessService.readPublishedBundle`의 공개·팔로우·차단·신고·운영 숨김을 매번 검사한다.
  가입 완료자는 소속 0개여도 공개 발행본을 조회할 수 있다. 경로 번치 불일치는 404다.
  읽기 트랜잭션은 REPEATABLE_READ로 버전과 장소 목록을 같은 스냅샷에서 읽는다.
  두 조회 사이에 일괄 편집이 커밋되어도 이전 버전과 새 장소를 섞어 반환하지 않는다.
- `PUT`: **DRAFT 참여자만** 전체 목록을 교체한다. 삭제할 장소를 제외하고 전송하며 전체 삭제는 빈 목록이다.
  `expectedVersion`으로 전체 목록의 덮어쓰기를 방지하며 사용자 lease는 요구하지 않는다.
  기존 `leaseToken`은 선택 호환 필드로만 받고 검증하지 않는다. `X-Request-Id`는 선택 UUID다.
  PUBLISHED는 OWNER라도 이 경로로 저장할 수 없고 `BUNDLE_002`로 거부한다.

```json
{
  "locations": [
    {"provider": "kakao", "externalId": "123", "name": "애월 한담해변", "address": "제주시 애월읍", "position": 0}
  ],
  "expectedVersion": 0,
  "leaseToken": "17c66e40-4aae-4b1a-baa9-f2f75519d55a"
}
```

`data`는 `BundleLocationState`다. 장소 행 id는 서버 발급값이며 요청에서 받지 않는다.

```json
{
  "bundleId": 101,
  "revision": 3,
  "locationVersion": 1,
  "locations": [
    {"id": 201, "provider": "kakao", "externalId": "123", "name": "애월 한담해변", "address": "제주시 애월읍", "position": 0}
  ]
}
```

`BundleSnapshot`에도 `locations/locationVersion`을 추가한다. 기존 단일 `location:null`은
장소 데이터가 아니다. 외부 번치 상세·카드의 단일 location/위경도 DTO 정렬은 C(#88),
피드 카드는 B(#95)가 아래 조회 계약으로 연결한다.

| 오류 | 조건 |
| --- | --- |
| COMMON_001 / 400 | 목록 크기·중복·순서·문자열·UUID 검증 실패 |
| COMMON_002 / 400 | 잘못되거나 필수 필드가 누락된 JSON |
| AUTH_001 / 401 | 인증·활성 session 없음 |
| AUTH_002 / 403 | ACTIVE 소속·초안 참여 권한 없음 |
| RESOURCE_001 / 404 | 없는 번들·경로 불일치·숨겨진 발행본 |
| BUNDLE_002 / 409 | 발행본 개별 저장 시도 |
| BUNDLE_003 / 409 | version 불일치. detail: `{locations: [...], version: 현재값}` |

내보내짐/삭제 안내와 외부 발행본 정책 오류는 기존 번들·피드 공통 코드를 유지한다.

## 위치 의도 요청 (#107)

`POST /api/v1/bunches/{bunchId}/bundles/{bundleId}/locations/intents`는 ACTIVE 초안 참여자만 호출한다.
`BundleLocationIntent`는 `com.bunch.bundle.domain`의 독립 enum이다.
`expectedVersion`·`leaseToken` 없이 서버가 번치→번들을 잠근 뒤 최신 목록에 적용한다.
`X-Request-Id`는 추적용 UUID이며 중복 실행 방지 키가 아니다.

| intent | 입력 | 최신 목록 적용 |
| --- | --- | --- |
| ADD | `locations`: 1~3개 장소, position 제외 | 중복·정원 검증 후 요청 전체를 선택 순서대로 뒤에 추가. 하나라도 위반하면 전체 거부 |
| REPLACE | `targetLocationId`, `location`: 장소 1개 | 기존 행 ID·순서를 유지하며 장소 교체. 정규화 키 중복은 거부 |
| REMOVE | `targetLocationId` | 대상 삭제. 이 번들에서 이미 삭제한 대상은 no-op |
| MOVE | `targetLocationId`, 선택 `previousLocationId`·`nextLocationId` | 대상을 뺀 최신 목록에서 next 앞, 없으면 previous 뒤, 둘 다 없으면 끝에 배치 |

각 intent에 해당하지 않는 입력 필드는 거부한다. 요청 배열은 **최대 3개**만 받고 초과는 부분 처리 없이
`INVALID_INPUT`으로 거부한다. 이름·주소 등은 기존 PlaceDetails 검증을 그대로 적용하며,
모든 입력의 구조 검증 후 정규화한 외부 키 중복, 최신 목록 정원 순서로 검사한다.
요청 내부의 중복도 합쳐서 수락하지 않는다. target·anchor ID는 양수이고 target 자신이나 같은 두 anchor를 받을 수 없다.

존재하는 ID는 대상 번들 소속을 검증한다. 없는 ID도 **이 번들에서 삭제한 기록이 있는지** 확인한다.
알 수 없는 ID·다른 번들의 현재/삭제 ID는 `RESOURCE_NOT_FOUND`이며 목록을 변경하지 않는다.
같은 번들에서 삭제한 target의 REMOVE는 no-op이다. MOVE는 `LOCATION_REMOVED` 충돌로 거부하며
클라이언트는 드래그 표시를 제거하고 「다른 멤버가 삭제한 위치예요.」를 안내한 뒤 최신 목록을 반영한다.
삭제된 target의 REPLACE는 새 키이고 정원이 남으면 **새 행 ID로 끝에 추가**하며,
정원이 찼거나 같은 키가 있으면 아래 충돌로 거부한다.
삭제된 anchor는 건너뛰고 남아 있는 anchor를 사용한다. 둘 다 사라지면 끝에 배치한다.
현재 anchor 사이가 인접하지 않아도 최신 next 위치를 우선하여 적용한다.

같은 행을 두 참여자가 교체하면 서버가 나중에 처리한 요청의 장소가 남는다. REPLACE는
`targetLocationId`를 유지하므로 첫 교체 후 도착한 두 번째 교체·삭제·MOVE도 같은 줄에 적용한다.
교체 후 삭제하면 그 줄이 삭제되고, 삭제 후 교체하면 정원·중복 검증을 거쳐 끝에 새로 추가한다.
드래그 도중 같은 행의 장소가 바뀌어도 MOVE는 변경된 장소를 사용자가 놓은 위치로 옮긴다.
이 서버 처리 순서는 기기 시각을 사용하지 않으며 기존 번치→번들 잠금으로 직렬화한다.

의도 요청의 정원·중복·삭제된 이동 대상은 모두 HTTP 409 `BUNDLE_002`와
`error.detail.reason`으로 구분한다. 기존 전체 PUT의 입력 검증 오류는 `COMMON_001`을 유지한다.

| reason | 조건·클라이언트 처리 |
| --- | --- |
| `LOCATION_CAPACITY_EXCEEDED` | 최신 목록에 적용하면 3개 초과. 전체 거부하며 정원 안내 |
| `LOCATION_DUPLICATE` | 요청 내부 또는 현재 목록과 정규화한 외부 키 중복. 「이미 추가한 위치에요.」 안내 |
| `LOCATION_REMOVED` | 같은 번들에서 이미 삭제한 행을 MOVE. 「다른 멤버가 삭제한 위치예요.」 안내 |

REPLACE 대상 자신의 키도 중복으로 거부하며, 입력한 이름·주소가 달라도 기존 데이터를 보존한다.
실패 요청은 행 ID·순서·이름·주소·핀과 revision·locationVersion·updatedAt·활동 시각을 바꾸지 않고
LOCATION 이벤트를 발행하지 않는다. 동시 요청의 승자가 저장된 뒤 후속 요청이 정원·중복에 걸리면
그 후속 요청 전체를 거부하고 먼저 저장된 목록을 보존한다. 부분 수락은 없다.

삭제 ID 출처는 기존 `bundle_requests`에 `operation=LOCATION_REMOVED`,
`request_key=UUID(0, locationId)`, 해당 `bundle_id`와 함께 남긴다. 원문 장소나 토큰은 남기지 않는다.
기존 동기 핀 정리 callback을 실행한 뒤 **실제로 삭제하는 행에만** 같은 트랜잭션에서 기록하므로
기존 PUT·발행본 일괄 수정의 삭제도 같은 검증 근거를 남기며 실패 시 함께 롤백한다.
의도 REPLACE로 장소만 교체한 행은 살아 있으므로 삭제 이력을 남기지 않는다.
이 기록은 새 이벤트 재생 체계나 클라이언트 오프라인 큐가 아니다.
전환 이전에 삭제되어 기록이 없는 ID는 알 수 없는 ID로 처리하므로 클라이언트는 최신 GET으로 복구한다.

응답은 `{bundleId, revision, locationVersion, changed, acceptedCount, locations}`다.
`acceptedCount`는 ADD에서 실제 새로 추가한 수이고 REPLACE/REMOVE/MOVE는 변경되면 1, no-op이면 0이다.
성공한 의도 요청은 200과 최종 목록을 반환하며, 정원·중복·삭제된 이동 대상 충돌은 409다.
`changed=false`이면 내용 revision·locationVersion·updatedAt·활동 시각과 LOCATION 이벤트를 갱신하지 않는다.
다른 요청은 DB 잠금으로 직렬 적용하며 한 요청 안에서는 선택 순서를 유지한다.

```json
{
  "intent": "ADD",
  "locations": [
    {"provider": "kakao", "externalId": "123", "name": "애월 한담해변", "address": "제주시 애월읍"}
  ]
}
```

발행 후 도착한 의도 요청은 OWNER도 `BUNDLE_STATE_CONFLICT`로 거부한다.
클라이언트는 성공 응답의 acceptedCount와 locations를 반영하고, 충돌 시 reason에 맞는 안내 후 최신 GET으로 복구한다.
로컬 드래그 중에는 해당 목록의 원격 변경을 보류하고 놓거나 취소하면 최신 결과를 반영한다.
저장은 손을 뗄 때 MOVE 1회이며 취소할 때는 저장하지 않는다. 발행·삭제·권한 상실은 즉시 처리한다.
재연결은 기존 GET snapshot 복구를 사용하며 서버에 반영되지 않은 오프라인 입력을 재생하지 않는다.
권한·추천 날짜 칩 갱신은 [번들 갱신 신호](bundle-refresh.md)를 따른다.

## 잠금·이벤트·발행본 연결

초안 저장은 기존 번치→번들 DB 잠금 순서를 따른다. 사용자 lease의 보유·만료·토큰은
위치 저장의 전제가 아니며 다른 참여자의 편집을 차단하지 않는다. 내부 DB 잠금으로 정원과 원자성을 보장한다.

실제 장소·메타데이터·순서 변경은 locationVersion과 revision을 각각 한 번 증가시키며
updatedAt과 편집자의 번치 활동을 갱신한다. 정규화 후 같은 요청은 버전·시간·이벤트를 변경하지 않는다.
기존 전체 `PUT`의 stale version 재요청은 no-op 여부와 관계없이 거부한다.
의도 요청은 최신 목록에 적용하므로 version을 받지 않는다.
커밋 후 기존 번들 topic에 `LOCATION` 이벤트를 전달한다. 공통 envelope의 revision/requestId와
`data: {locations: [...], version: locationVersion}`를 포함한다. 롤백하면 전달하지 않는다.
별도 STOMP 내용 저장 명령은 없고 재연결은 GET snapshot으로 복구한다.
CREATED/PUBLISHED 공통 content에도 locations/locationVersion을 포함한다.

`BundleLocationService.replaceLocked(bundle, inputs): BundleLocationChange`는
**기존 쓰기 트랜잭션 필수(MANDATORY)**인 내부 API다. C는 OWNER·expectedRevision·상태·전체 발행 조건을
검사하고 번치→번들을 잠근 후 호출한다. 반환값은 changed와 최종 locations다.
실제 변경 시 locationVersion만 증가시키고 aggregate revision·시간·최종 이벤트는 C가 한 번 갱신한다.
다른 필드 저장이 실패하면 장소도 함께 롤백한다. 이 메서드는 단독 HTTP 경로가 아니다.

## 콕하기·배치 조회

전체 PUT·발행본 일괄 수정은 같은 `(provider, externalId)`가 남아 있으면 이름·주소·순서가 바뀌어도
행 ID를 보존하며 새 키에는 새 ID를 발급한다. 의도 REPLACE는 다른 장소로 바꾸어도 행 ID를 유지한다.
ADD 또는 이미 삭제된 행의 REPLACE에는 새 ID를 발급하며 이전 삭제 ID를 재사용하지 않는다.

편집으로 제거/교체할 장소가 있으면 **기존 행의 장소 키 변경 또는 행 삭제 전**, 같은 트랜잭션에서
`com.bunch.bundle.dto.BundleLocationsRemovedEvent(bundleId, locationIds)`를 동기 발행한다.
G(#92)는 동기 `@EventListener`와 MANDATORY 트랜잭션으로 이 ID를 source로 가진 콕만 삭제해야 한다.
AFTER_COMMIT·비동기 listener는 사용하지 않는다. 기존 listener가 flush할 수 있으므로 장소 키를 먼저 변경하지 않는다.
REPLACE의 이벤트 ID는 삭제 이력이 아닌 **이전 장소의 핀 출처 정리 대상**이다.
교체된 행 ID를 새 장소의 콕 출처로 다시 사용할 수 있다. 다른 번들·광고 출처의 같은 장소 콕은 유지한다.
listener 실패는 장소와 나머지 번들 편집을 함께 롤백시킨다.

**번들 자체 삭제·번치 cascade에는 이 편집 이벤트를 발행하지 않는다.**
G는 source location/bundle FK를 `ON DELETE SET NULL`로 두고 장소 스냅샷을 보존한다.
V27에는 미래 pinned place 테이블이나 임시 writer를 만들지 않는다.

`BundleLocationService.listForBundles(bundleIds)`는 최대 500개의 양수 ID를 받아 한 번의 장소 쿼리로
`Map<Long, List<BundleLocationResponse>>`를 반환한다. 목록은 position 오름차순이다.
빈 번들/없는 ID도 요청 키에 빈 목록으로 포함하며 빈 입력은 빈 map이다.
**내부 조립 API이므로 자체 권한 검사는 없다.** B/C는 접근 검사·페이지 필터가 끝난 ID만 전달한다.
G는 쓰기 잠금·`FeedAccessService.requireInteraction` 후 대상 장소 ID의 번들 소속을 검증한다.

## V27·검증 범위

V27은 bundles.location_version과 bundle_locations를 추가하고 lease target CHECK를 확장한다.
location의 bundle FK는 ON DELETE CASCADE다. position 0~2 CHECK와 `(bundle_id, position)` UNIQUE로
최대 3개를 보장한다. 순서 swap 시 행 ID 보존을 위해 이 UNIQUE만 DEFERRABLE INITIALLY DEFERRED다.
`(bundle_id, provider, external_id)` UNIQUE는 즉시 검사한다. 이름·주소는 grapheme 경계 보존을 위해
TEXT로 저장하고 애플리케이션에서 상한을 검사한다. 적용된 마이그레이션은 수정하지 않는다.

자동 검증 범위는 입력 경계·HTTP 오류 reason·권한·동시 저장·legacy version/lease 비의존·정원/중복 전체 거부·
동일 행 연속/동시 교체·교체/삭제 양쪽 도착 순서·삭제 ID 출처·동시 삭제/이동·교체 후 이동·
행 ID 보존·교체된 행의 삭제 이력 미생성·실제 핀 정리와 동기 listener 실패 롤백·
실제 STOMP 커밋/롤백/재연결·기존 발행 조건·PostgreSQL migration·JPA validate다.
C의 실제 일괄 편집과 G의 실제 콕 writer 연결은 각각의 구현 통합 시 추가 검증한다.
검색 공급자·실제 iOS/WebView 연동은 후속이며 자동 검증과 구분한다.


## 콕 목록 연계 — #125

현재 번치 전체 목록·멤버 필터·개수·원본 접근 상태는 [콕 계약](pinned-places.md)이 소유한다.
목록의 장소 이름·주소는 최초 핀 스냅샷을 사용하며 현재 번치명·번들 제목만 최신으로 조립한다.
장소 제거 listener·번들 삭제 FK·provider+externalId 중복 보존은 위 계약을 그대로 재사용한다.
콕 시트 K8의 주소 없는 지역은 #126의 입력·검색 공급자 계약 정렬이 선행한다.
#125에서 PlaceDetails·DB의 빈 주소 금지 검증을 임의로 완화하지 않는다.
