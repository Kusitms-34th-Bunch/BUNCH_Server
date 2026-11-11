# 번들 장소 목록 계약

이슈 #27, DRAFT-17·BUNDLE-04·FEED-10. 2026-11-07 승인한 피드 원문
6.2.3·6.2.12·6.3.1과 보완 결정을 따른다. 기존 장소 1개·검색 공급자 선행 계약을 대체한다.
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
| address | trim 후 비어 있지 않은 1~500 grapheme |
| position | 0부터 시작하는 연속 정수. 목록이 `[0, ..., size-1]`이어야 함 |

문자열의 NUL은 거부한다. 목록 내 정규화한 `(provider, externalId)`는 중복할 수 없다.
입력 배열 순서와 관계없이 position 오름차순으로 저장·응답한다.
`com.bunch.place.domain.PlaceDetails.of`를 광고·콕하기에서도 공통 검증에 재사용한다.
잘못된 값은 `IllegalArgumentException`이며 유스케이스에서 `INVALID_INPUT`으로 변환한다.
검색 공급자 결과의 진위 검증은 후속이다. 이번에는 구조 검증한 장소 입력을 저장한다.

## HTTP

기본 경로: `/api/v1/bunches/{bunchId}/bundles/{bundleId}/locations`.
기존 `success / data / error` 응답과 활성 JWT session 인증을 사용한다.

- `GET`: DRAFT는 가입 완료 ACTIVE 번치 멤버만 조회한다. PUBLISHED는
  `FeedAccessService.readPublishedBundle`의 공개·팔로우·차단·신고·운영 숨김을 매번 검사한다.
  가입 완료자는 소속 0개여도 공개 발행본을 조회할 수 있다. 경로 번치 불일치는 404다.
  읽기 트랜잭션은 REPEATABLE_READ로 버전과 장소 목록을 같은 스냅샷에서 읽는다.
  두 조회 사이에 일괄 편집이 커밋되어도 이전 버전과 새 장소를 섞어 반환하지 않는다.
- `PUT`: **DRAFT 참여자만** 전체 목록을 교체한다. 삭제할 장소를 제외하고 전송하며 전체 삭제는 빈 목록이다.
  단독 편집도 STOMP의 `LOCATION` lease가 필요하다. `X-Request-Id`는 선택 UUID다.
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
| BUNDLE_004 / 409 | LOCATION lease 없음·만료·member/session/token 불일치 |

내보내짐/삭제 안내와 외부 발행본 정책 오류는 기존 번들·피드 공통 코드를 유지한다.

## 잠금·이벤트·발행본 연결

초안 저장은 기존 번치→번들→lease 잠금 순서를 따른다. LOCATION은 목록 전체의 단일 target이다.
기존 5초 heartbeat·15초 만료·연결별 private token·재획득 token 무효화 정책을 적용한다.
같은 회원/session의 다른 socket도 활성 lease를 빼앗을 수 없다.

실제 장소·메타데이터·순서 변경은 locationVersion과 revision을 각각 한 번 증가시키며
updatedAt과 편집자의 번치 활동을 갱신한다. 정규화 후 같은 요청은 버전·시간·이벤트를 변경하지 않는다.
stale version 재요청은 no-op 여부와 관계없이 거부한다.
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

같은 `(provider, externalId)`가 남아 있으면 이름·주소·순서가 바뀌어도 행 ID를 보존한다.
새 키에는 새 ID를 발급하며 제거한 키를 다시 추가해도 이전 출처 ID를 재사용하지 않는다.

편집으로 제거/교체할 장소가 있으면 **행 삭제 전**, 같은 트랜잭션에서
`com.bunch.bundle.dto.BundleLocationsRemovedEvent(bundleId, locationIds)`를 동기 발행한다.
G(#92)는 동기 `@EventListener`와 MANDATORY 트랜잭션으로 이 ID를 source로 가진 콕만 삭제해야 한다.
AFTER_COMMIT·비동기 listener는 사용하지 않는다. 다른 번들·광고 출처의 같은 장소 콕은 유지한다.
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

자동 검증 범위는 입력 경계·HTTP·권한·동시 저장·lease/token/version·ID 보존·동기 listener 실패 롤백·
실제 STOMP 커밋/롤백/재연결·기존 발행 조건·PostgreSQL migration·JPA validate다.
C의 실제 일괄 편집과 G의 실제 콕 writer 연결은 각각의 구현 통합 시 추가 검증한다.
검색 공급자·실제 iOS/WebView 연동은 후속이며 자동 검증과 구분한다.


## 콕 목록 연계 — #125

현재 번치 전체 목록·멤버 필터·개수·원본 접근 상태는 [콕 계약](pinned-places.md)이 소유한다.
목록의 장소 이름·주소는 최초 핀 스냅샷을 사용하며 현재 번치명·번들 제목만 최신으로 조립한다.
장소 제거 listener·번들 삭제 FK·provider+externalId 중복 보존은 위 계약을 그대로 재사용한다.
콕 시트 K8의 주소 없는 지역은 #126의 입력·검색 공급자 계약 정렬이 선행한다.
#125에서 PlaceDetails·DB의 빈 주소 금지 검증을 임의로 완화하지 않는다.
